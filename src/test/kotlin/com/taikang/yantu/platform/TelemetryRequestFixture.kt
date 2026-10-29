package com.taikang.yantu.platform

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

object TelemetryRequestFixture {
    @JvmStatic fun main(args: Array<String>) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val observed = AtomicReference<List<String>>()
        server.createContext("/api/v1/telemetry/skill-usage-events") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            observed.set(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Idempotency-Key"), body))
            exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.start()
        try {
            val payload = JsonObject().apply { addProperty("eventId", "fixture-uuid") }
            val request = TelemetryRequestFactory.post("http://127.0.0.1:${server.address.port}/api/v1",
                "/telemetry/skill-usage-events", payload, "fixture-access", "fixture-uuid")
            val response = PlatformHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() == 200)
            check(observed.get() == listOf("POST", "Bearer fixture-access", "fixture-uuid", payload.toString()))
            check(runCatching { TelemetryRequestFactory.post("http://localhost/api/v1", "/other", payload, "x", null) }.isFailure)
        } finally { server.stop(0) }
    }
}
