package com.taikang.yantu.platform

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object PlatformHttpClientFixture {
    @JvmStatic fun main(args: Array<String>) {
        val previous = ProxySelector.getDefault()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ping") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            // Simulates a workstation proxy that cannot reach the private API.
            ProxySelector.setDefault(ProxySelector.of(InetSocketAddress("127.0.0.1", 9)))
            val http = PlatformHttpClient()
            val uri = URI.create("http://127.0.0.1:${server.address.port}/ping")
            check(http.route(uri).contains("直连"))
            val response = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() == 200)
            check(PlatformHttpClient.isPrivateHost("10.154.76.195"))
            check(!PlatformHttpClient.isPrivateHost("10.evil.example"))
            check(!PlatformHttpClient.isPrivateHost("8.8.8.8"))
        } finally {
            server.stop(0)
            ProxySelector.setDefault(previous)
        }
    }
}
