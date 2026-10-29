package com.taikang.yantu.platform

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object PlatformUrlResolver {
    fun detail(baseRaw: String, pathRaw: String): URI {
        require(baseRaw.isNotBlank()) { "请先配置平台网页地址" }
        require(pathRaw.length in 1..2048 && !pathRaw.startsWith("javascript:", true) && !pathRaw.startsWith("file:", true)) { "详情链接无效" }
        val base = URI.create(baseRaw.trimEnd('/') + "/")
        val target = base.resolve(pathRaw).normalize()
        require(base.scheme == target.scheme && base.host.equals(target.host, true) && base.port == target.port && target.userInfo == null) {
            "详情链接不属于已配置的平台地址"
        }
        return target
    }

    fun checkedDetail(baseRaw: String, pathRaw: String, client: PlatformHttpClient = PlatformHttpClient()): URI {
        val base = URI.create(baseRaw.trimEnd('/') + "/")
        var target = detail(baseRaw, pathRaw)
        repeat(5) {
            val response = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 300..399) return target
            val location = response.headers().firstValue("Location").orElseThrow { IllegalArgumentException("详情页重定向缺少目标") }
            target = detail(baseRaw, target.resolve(location).toString())
            require(base.scheme == target.scheme && base.host.equals(target.host, true) && base.port == target.port)
        }
        throw IllegalArgumentException("详情页重定向过多")
    }
}
