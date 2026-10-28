package com.taikang.yantu.platform

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
@State(name = "YantuAssistantPlatform", storages = [Storage("yantu-assistant-platform.xml")])
class YantuPlatformService : PersistentStateComponent<YantuPlatformService.Settings> {
    class Settings { var apiBaseUrl: String = "" }
    data class Snapshot(val apiBaseUrl: String, val state: String, val userCode: String?, val verificationUri: String?, val message: String)
    private data class Attempt(val deviceCode: String, val verifier: String, val expiresAt: Instant, val pollSeconds: Long, val uri: URI)
    private val client = PlatformHttpClient()
    private val random = SecureRandom()
    private val currentAttempt = AtomicReference<Attempt?>()
    private val restoreAttempted = AtomicBoolean(false)
    private val credentialKey = CredentialAttributes("com.taikang.yantu.assistant.jetbrains.refreshToken")
    @Volatile private var settings = Settings()
    @Volatile private var state = "未登录"
    @Volatile private var message = "请先配置平台 API 地址"
    @Volatile private var userCode: String? = null
    @Volatile private var verificationUri: String? = null
    @Volatile private var accessToken: String? = null

    override fun getState(): Settings = settings
    override fun loadState(state: Settings) { settings = state }
    fun snapshot(): Snapshot = Snapshot(settings.apiBaseUrl, state, userCode, verificationUri, message)

    fun configure(raw: String) {
        val uri = validateBase(raw)
        if (settings.apiBaseUrl == uri.toString().trimEnd('/')) return
        currentAttempt.set(null)
        accessToken = null
        PasswordSafe.instance.set(credentialKey, null)
        restoreAttempted.set(true)
        settings.apiBaseUrl = uri.toString().trimEnd('/')
        state = "未登录"
        message = "平台地址已保存，可以开始登录"
        userCode = null; verificationUri = null
    }

    fun beginLogin(rawApiBaseUrl: String) {
        val address = rawApiBaseUrl.trim().ifBlank { settings.apiBaseUrl }
        require(address.isNotBlank()) { "请先输入平台 API 地址" }
        if (address != settings.apiBaseUrl) configure(address)
        if (currentAttempt.get() != null) throw IllegalStateException("已有授权正在等待，请稍后重试")
        val verifierBytes = ByteArray(48).also(random::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        state = "正在请求授权"; message = "正在连接平台"
        val body = JsonObject().apply {
            addProperty("clientName", "IntelliJ IDEA / 研途助手")
            addProperty("codeChallenge", challenge)
            addProperty("codeChallengeMethod", "S256")
        }
        val response = post("/auth/ide/authorizations", body)
        if (response.statusCode() !in 200..299) { fail("授权申请接口 HTTP ${response.statusCode()}（/auth/ide/authorizations）${responseOrigin(response)}"); return }
        val data = parseObject(response.body())
        val code = data.required("deviceCode")
        val shownCode = data.required("userCode")
        val fullUri = VerificationLink.create(data.required("verificationUri"), shownCode)
        val seconds = data.get("expiresIn")?.asLong?.coerceIn(30, 900) ?: 300L
        val interval = data.get("pollInterval")?.asLong?.coerceIn(2, 15) ?: 3L
        val attempt = Attempt(code, verifier, Instant.now().plusSeconds(seconds), interval, fullUri)
        currentAttempt.set(attempt)
        userCode = shownCode; verificationUri = fullUri.toString()
        state = "等待浏览器授权"; message = "请在浏览器中批准设备码"
        runCatching { BrowserUtil.browse(fullUri.toASCIIString()) }
            .onFailure { message = "浏览器未能自动打开，请复制授权链接并输入设备码" }
        while (currentAttempt.get() === attempt && Instant.now().isBefore(attempt.expiresAt)) {
            try { Thread.sleep(attempt.pollSeconds * 1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            if (currentAttempt.get() !== attempt) break
            val poll = post("/auth/ide/token", JsonObject().apply {
                addProperty("deviceCode", attempt.deviceCode); addProperty("codeVerifier", attempt.verifier)
            })
            if (poll.statusCode() == 202 || poll.statusCode() == 428) continue
            if (poll.statusCode() !in 200..299) { fail("令牌轮询接口 HTTP ${poll.statusCode()}（/auth/ide/token）${responseOrigin(poll)}"); break }
            acceptTokens(parseObject(poll.body()))
            break
        }
        if (currentAttempt.compareAndSet(attempt, null) && state == "等待浏览器授权") fail("设备授权已过期")
    }

    fun restoreOnceInBackground() {
        if (!restoreAttempted.compareAndSet(false, true) || settings.apiBaseUrl.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { restore() }.onFailure(::reportFailure) }
    }

    fun restore() {
        if (accessToken != null) return
        val token = PasswordSafe.instance.getPassword(credentialKey) ?: return
        val response = post("/auth/refresh", JsonObject().apply { addProperty("refreshToken", token) })
        if (response.statusCode() in 200..299) acceptTokens(parseObject(response.body()), token)
        else { PasswordSafe.instance.set(credentialKey, null); fail("登录已失效，请重新授权") }
    }

    fun testConnection(rawApiBaseUrl: String) {
        val address = rawApiBaseUrl.trim().ifBlank { settings.apiBaseUrl }
        require(address.isNotBlank()) { "请先输入平台 API 地址" }
        val base = validateBase(address).toString().trimEnd('/')
        val uri = URI.create("$base/auth/me")
        val started = System.nanoTime()
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        val elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis()
        val route = client.route(uri)
        state = if (response.statusCode() == 401 || response.statusCode() in 200..299) "平台可达" else "连接异常"
        message = "GET /auth/me：HTTP ${response.statusCode()}，${elapsed}ms，请求路由=$route${responseOrigin(response)}"
    }

    fun verifyApi() {
        if (accessToken == null) restore()
        var token = accessToken ?: throw IllegalStateException("尚未登录")
        var response = getMe(token)
        if (response.statusCode() == 401) {
            accessToken = null
            restore()
            token = accessToken ?: throw IllegalStateException("登录已失效")
            response = getMe(token)
        }
        if (response.statusCode() !in 200..299) throw IllegalStateException("平台 API 验证失败：HTTP ${response.statusCode()}")
        state = "已登录"; message = "受保护 API 验证成功"
    }
    private fun getMe(token: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(url("/auth/me")).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token").GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun logout() {
        currentAttempt.set(null)
        val refresh = PasswordSafe.instance.getPassword(credentialKey)
        PasswordSafe.instance.set(credentialKey, null)
        accessToken = null; userCode = null; verificationUri = null
        state = "未登录"; message = "已退出登录"
        restoreAttempted.set(true)
        if (refresh != null) runCatching { post("/auth/logout", JsonObject().apply { addProperty("refreshToken", refresh) }) }
    }

    fun reportFailure(error: Throwable) { fail(error.message?.take(160) ?: "平台请求失败") }
    private fun acceptTokens(data: JsonObject, fallback: String? = null) {
        accessToken = data.required("accessToken")
        val refresh = data.get("refreshToken")?.asString ?: fallback
        if (refresh != null) PasswordSafe.instance.set(credentialKey, Credentials("refresh", refresh))
        state = "已登录"; message = "设备授权成功"
        userCode = null; verificationUri = null
        currentAttempt.set(null)
    }
    private fun fail(text: String) { state = "错误"; message = text; currentAttempt.set(null); userCode = null; verificationUri = null }
    private fun responseOrigin(response: HttpResponse<*>): String {
        val server = response.headers().firstValue("Server").orElse("").take(40)
        val via = response.headers().firstValue("Via").orElse("").take(40)
        return buildString {
            if (server.isNotBlank()) append("，Server=$server")
            if (via.isNotBlank()) append("，Via=$via")
        }
    }
    private fun post(path: String, body: JsonObject): HttpResponse<String> {
        val request = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun url(path: String) = URI.create(settings.apiBaseUrl.trimEnd('/') + path)
    private fun parseObject(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
    private fun JsonObject.required(key: String): String = get(key)?.asString?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("平台响应缺少 $key")
    private fun validateBase(raw: String): URI {
        val uri = URI.create(raw.trim().trimEnd('/'))
        require(uri.userInfo == null && uri.fragment == null && uri.query == null && uri.host != null) { "平台地址格式无效" }
        require(uri.path.trimEnd('/') == "/api/v1") { "平台地址需以 /api/v1 结尾" }
        require(uri.scheme == "https" || (uri.scheme == "http" && PlatformHttpClient.isPrivateHost(uri.host))) { "平台地址需使用 HTTPS，内网地址可使用 HTTP" }
        return uri
    }

    companion object { fun get(): YantuPlatformService = ApplicationManager.getApplication().getService(YantuPlatformService::class.java) }
}
