package com.taikang.yantu.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.taikang.yantu.platform.PlatformApi
import com.taikang.yantu.platform.YantuPlatformService
import com.taikang.yantu.skills.SkillScanner
import com.taikang.yantu.skills.SkillUpdateService
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

@Service(Service.Level.APP)
class ChatService : Disposable {
    private val platform = YantuPlatformService.get()
    private val api = PlatformApi(platform)
    private val scanner = SkillScanner()
    private val updates = SkillUpdateService(api, scanner)
    @Volatile private var projectRoot: Path? = null
    private val commands = Executors.newSingleThreadExecutor { task -> Thread(task, "yantu-chat-commands").apply { isDaemon = true } }
    private val worker = Executors.newCachedThreadPool { task -> Thread(task, "yantu-chat").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yantu-chat-watchdog").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private var sessions = JsonArray()
    private var messages = JsonArray()
    private var recommendations = JsonArray()
    private var skillUpdates = JsonArray()
    private var updateCandidates = emptyList<SkillUpdateService.Candidate>()
    private var updatesChecking = false
    private var updatesInstalling = false
    private var updateError: String? = null
    private var currentSessionKey: String? = null
    private var currentRunKey: String? = null
    private var running = false
    private var connection = "idle"
    private var error: String? = null
    private var sse: SseClient? = null
    private var watchdogTask: ScheduledFuture<*>? = null
    private var streamToken = 0L
    private val osType = when {
        System.getProperty("os.name").startsWith("Windows", true) -> "WINDOWS"
        System.getProperty("os.name").startsWith("Mac", true) -> "MACOS"
        else -> "LINUX"
    }

    @Synchronized fun state(): JsonObject = JsonObject().apply {
        val login = platform.snapshot()
        addProperty("type", "state")
        addProperty("authenticated", login.state == "已登录")
        addProperty("loginPending", login.state == "等待浏览器授权" || login.state == "正在请求授权")
        login.userCode?.let { addProperty("userCode", it) }
        addProperty("osType", osType)
        add("sessions", sessions.deepCopy()); add("messages", messages.deepCopy()); add("recommendations", recommendations.deepCopy())
        add("skillUpdates", skillUpdates.deepCopy()); addProperty("updatesChecking", updatesChecking); addProperty("updatesInstalling", updatesInstalling)
        updateError?.let { addProperty("updateError", it) }
        currentSessionKey?.let { addProperty("currentSessionKey", it) }
        currentRunKey?.let { addProperty("currentRunKey", it) }
        addProperty("running", running); addProperty("connection", connection)
        (error ?: login.message.takeIf { login.state == "错误" })?.let { addProperty("error", it) }
        addProperty("apiBaseUrl", login.apiBaseUrl)
        addProperty("webBaseUrl", login.webBaseUrl)
    }

    fun subscribe(listener: (JsonObject) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(state())
        return AutoCloseable { listeners.remove(listener) }
    }
    fun setProjectRoot(path: String?) { projectRoot = path?.let(Path::of) }
    @Synchronized fun currentOsType(): String = osType
    @Synchronized fun recommendedKeys(runKey: String): List<String> = recommendations.mapNotNull { item ->
        val row = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        if (row.string("runKey") != runKey || row.string("status") !in setOf("missing", "update")) return@mapNotNull null
        row.string("skillKey")
    }.distinct()
    fun refreshSkillStatus() {
        synchronized(this) { recommendations = scanner.withStatus(recommendations, scanner.scan(projectRoot)) }
        publish()
    }
    fun checkUpdates() {
        synchronized(this) { updatesChecking = true; updateError = null }; publish()
        try {
            val found = updates.check(projectRoot, osType)
            synchronized(this) { updateCandidates = found; skillUpdates = updates.public(found) }
        } catch (failure: Exception) { synchronized(this) { updateError = failure.message?.take(160) ?: "更新检查失败" } }
        finally { synchronized(this) { updatesChecking = false }; publish() }
    }
    fun dismissUpdates() { synchronized(this) { updateCandidates = emptyList(); skillUpdates = JsonArray(); updateError = null }; publish() }
    fun installUpdates(project: Project, keys: List<String>) {
        require(keys.isNotEmpty() && keys.size <= 100 && keys.all(SkillScanner::validKey))
        synchronized(this) { updatesInstalling = true; updateError = null }; publish()
        try {
            val installed = updates.install(project, synchronized(this) { updateCandidates }, keys, osType)
            if (installed.installed.isNotEmpty()) notice("info", "已更新：${installed.installed.joinToString("、")}")
            if (installed.skipped.isNotEmpty()) notice("info", "已跳过：${installed.skipped.joinToString("、")}")
            if (installed.failed.isNotEmpty()) notice("error", "更新失败：${installed.failed.joinToString("；").take(220)}")
            refreshSkillStatus()
            checkUpdates()
        } catch (failure: Exception) { synchronized(this) { updateError = failure.message?.take(160) ?: "更新失败" }; notice("error", updateError ?: "更新失败") }
        finally { synchronized(this) { updatesInstalling = false }; publish() }
    }
    private fun publish() { val snapshot = state(); listeners.forEach { runCatching { it(snapshot) } } }
    fun notice(level: String, message: String) {
        val event = JsonObject().apply { addProperty("type", "notice"); addProperty("level", level); addProperty("message", message.take(240)) }
        listeners.forEach { runCatching { it(event) } }
    }
    fun execute(command: () -> Unit) { commands.execute { runCatching(command).onFailure(::fail) } }

    fun bootstrap() {
        platform.restore()
        val authenticated = platform.snapshot().state == "已登录"
        val loadedSessions = if (authenticated) api.sessions() else JsonArray()
        val selected = synchronized(this) { currentSessionKey }
        synchronized(this) {
            error = null
            sessions = loadedSessions
        }
        publish()
        if (authenticated && selected != null && loadedSessions.any { it.asJsonObject.get("sessionKey")?.asString == selected })
            selectSession(selected)
        if (authenticated) worker.execute { checkUpdates() }
    }
    fun login() {
        worker.execute {
            runCatching {
                val polling = worker.submit {
                    while (platform.snapshot().state != "已登录" && platform.snapshot().state != "错误") {
                        publish(); Thread.sleep(500)
                    }
                    publish()
                }
                try { platform.beginLogin("") } finally { polling.cancel(true); publish() }
                if (platform.snapshot().state == "已登录") bootstrap()
            }.onFailure(::fail)
        }
    }
    fun logout() {
        closeStream()
        platform.logout()
        synchronized(this) {
            sessions = JsonArray(); messages = JsonArray(); recommendations = JsonArray()
            skillUpdates = JsonArray(); updateCandidates = emptyList(); updateError = null
            currentSessionKey = null; currentRunKey = null; running = false; connection = "idle"; error = null
        }
        publish()
    }
    fun newSession() {
        val session = api.createSession(osType)
        closeStream()
        synchronized(this) {
            sessions = JsonArray().apply { add(session); sessions.forEach(::add) }
            currentSessionKey = session.get("sessionKey").asString
            currentRunKey = null; messages = JsonArray(); recommendations = JsonArray(); running = false; error = null
        }
        publish()
        worker.execute { checkUpdates() }
    }
    fun selectSession(key: String) {
        val detail = api.session(key)
        closeStream()
        synchronized(this) {
            currentSessionKey = key; messages = detail.messages; recommendations = scanner.withStatus(detail.recommendations, scanner.scan(projectRoot))
            currentRunKey = detail.latestRunKey
            running = detail.latestRunStatus in setOf("PENDING", "RUNNING")
            connection = if (running) "connecting" else "idle"; error = null
        }
        publish()
        if (running && detail.latestRunKey != null) connectRun(detail.latestRunKey)
    }
    fun deleteSession(key: String) {
        api.deleteSession(key)
        val remaining = api.sessions()
        synchronized(this) {
            if (currentSessionKey == key) { closeStream(); currentSessionKey = null; currentRunKey = null; messages = JsonArray(); recommendations = JsonArray(); running = false }
            sessions = remaining
        }
        publish()
    }
    fun sendMessage(content: String) {
        require(content.trim().length in 1..10_000) { "消息长度需为 1–10000 字" }
        if (currentSessionKey == null) newSession()
        val sessionKey = synchronized(this) { currentSessionKey ?: error("会话未创建") }
        val localId = "local-${System.nanoTime()}"
        synchronized(this) {
            messages.add(JsonObject().apply { addProperty("id", localId); addProperty("role", "user"); addProperty("content", content.trim()) })
            running = true; error = null
        }
        publish()
        try {
            val runKey = api.sendMessage(sessionKey, content.trim(), osType).get("runKey").asString
            synchronized(this) { currentRunKey = runKey }
            connectRun(runKey)
        } catch (failure: Exception) {
            synchronized(this) {
                val remaining = JsonArray()
                for (message in messages) if (message.asJsonObject.get("id")?.asString != localId) remaining.add(message)
                messages = remaining; running = false; error = "发送失败，请重试"
            }
            publish(); throw failure
        }
    }
    fun cancelRun() { val key = synchronized(this) { currentRunKey } ?: return; api.cancelRun(key) }

    private fun connectRun(runKey: String) {
        closeStream()
        val token = synchronized(this) { ++streamToken }
        val client = SseClient(api, runKey, { event ->
            if (synchronized(this) { token == streamToken && currentRunKey == runKey }) onEvent(event, runKey)
        }, { status ->
            if (synchronized(this) { token == streamToken }) {
                synchronized(this) {
                    connection = status
                    if (status == "closed" && running) error = "流式连接中断，请点击重试恢复会话"
                }
                publish()
            }
        })
        sse = client
        worker.execute { client.run() }
        watchdogTask = watchdog.scheduleWithFixedDelay({
            val sessionKey = synchronized(this) { if (token == streamToken && running) currentSessionKey else null }
            if (sessionKey != null) runCatching { api.session(sessionKey) }.onSuccess { detail ->
                if (detail.latestRunKey == runKey && detail.latestRunStatus !in setOf("PENDING", "RUNNING", null)) {
                    commands.execute { if (synchronized(this) { token == streamToken && currentRunKey == runKey }) selectSession(sessionKey) }
                }
            }.onFailure { LOG.debug("run status check failed", it) }
        }, 3, 3, TimeUnit.SECONDS)
    }
    private fun onEvent(event: SseClient.Event, runKey: String) {
        val payload = runCatching { JsonParser.parseString(event.data).asJsonObject }.getOrNull() ?: return
        val type = event.event ?: payload.string("type") ?: payload.string("eventType") ?: return
        val data = payload.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: payload.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: payload
        var terminal = false
        synchronized(this) {
            when (type) {
                "message.delta" -> {
                    val delta = data.string("delta") ?: data.string("content") ?: data.string("text") ?: ""
                    val key = data.string("messageKey")
                    val last = messages.lastOrNull()?.asJsonObject
                    val target = if (last?.get("role")?.asString == "assistant" && (key == null || last.get("id")?.asString == key)) last
                        else JsonObject().apply { addProperty("id", key ?: "assistant-$runKey"); addProperty("role", "assistant"); addProperty("content", ""); addProperty("runKey", runKey); messages.add(this) }
                    target.addProperty("content", target.get("content").asString + delta)
                }
                "recommendation.completed" -> {
                    val items = data.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
                    for (item in scanner.withStatus(items, scanner.scan(projectRoot))) recommendations.add(item.asJsonObject.deepCopy().apply { addProperty("runKey", runKey) })
                }
                "run.completed", "run.failed", "run.cancelled", "done" -> {
                    running = false; connection = "closed"; sse?.close(); terminal = true
                    if (type == "run.failed") error = data.string("errorMessage") ?: "运行失败"
                }
            }
        }
        publish()
        if (terminal) {
            val sessionKey = synchronized(this) { currentSessionKey }
            if (sessionKey != null) commands.execute {
                runCatching { if (synchronized(this) { currentRunKey == runKey }) selectSession(sessionKey) }
                    .onFailure { LOG.warn("run completion refresh failed", it) }
            }
        }
    }
    private fun closeStream() { synchronized(this) { streamToken++; watchdogTask?.cancel(false); watchdogTask = null; sse?.close(); sse = null } }
    private fun fail(failure: Throwable) {
        LOG.warn("chat command failed type=${failure.javaClass.simpleName}", failure)
        synchronized(this) { error = failure.message?.take(200) ?: "操作失败"; running = false }
        publish(); notice("error", error ?: "操作失败")
    }
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    override fun dispose() { closeStream(); watchdog.shutdownNow(); commands.shutdownNow(); worker.shutdownNow(); listeners.clear() }
    companion object {
        private val LOG = Logger.getInstance(ChatService::class.java)
        fun get(): ChatService = ApplicationManager.getApplication().getService(ChatService::class.java)
    }
}
