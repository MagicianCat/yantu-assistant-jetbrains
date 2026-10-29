package com.taikang.yantu.platform

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

object PlatformUrlFixture {
    @JvmStatic fun main(args: Array<String>) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "https://example.org/evil")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            check(PlatformUrlResolver.checkedDetail(base, "/ok").path == "/ok")
            check(runCatching { PlatformUrlResolver.detail(base, "javascript:alert(1)") }.isFailure)
            check(runCatching { PlatformUrlResolver.detail(base, "https://example.org/evil") }.isFailure)
            check(runCatching { PlatformUrlResolver.checkedDetail(base, "/redirect") }.isFailure)
        } finally { server.stop(0) }
    }
}
