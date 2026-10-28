package com.taikang.yantu.platform

import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Trusted loopback/RFC1918 API URLs bypass workstation proxies. Public HTTPS keeps the JVM route. */
class PlatformHttpClient {
    private val ordinary = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val direct = HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun route(uri: URI): String = if (isPrivateHost(uri.host)) "内网直连（绕过系统代理）" else "JVM 默认路由"

    fun <T> send(request: HttpRequest, bodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val client = if (isPrivateHost(request.uri().host)) direct else ordinary
        return client.send(request, bodyHandler)
    }

    companion object {
        fun isPrivateHost(host: String?): Boolean {
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            val parts = host?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168)
        }
    }
}
