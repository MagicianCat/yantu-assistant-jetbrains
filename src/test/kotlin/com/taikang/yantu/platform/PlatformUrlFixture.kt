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
            check(PlatformUrlResolver.external(base, "https://example.org/source?id=42").toString() == "https://example.org/source?id=42")
            check(PlatformUrlResolver.external(base, "/wiki?documentId=42").toString() == "$base/wiki?documentId=42")
            check(runCatching { PlatformUrlResolver.external(base, "javascript:alert(1)") }.isFailure)
            check(runCatching { PlatformUrlResolver.external(base, "file:///tmp/private") }.isFailure)
            check(runCatching { PlatformUrlResolver.external(base, "https://user:pass@example.org/") }.isFailure)
        } finally { server.stop(0) }
    }
}
