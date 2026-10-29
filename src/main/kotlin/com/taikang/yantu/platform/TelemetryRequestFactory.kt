package com.taikang.yantu.platform

import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

object TelemetryRequestFactory {
    fun post(baseUrl: String, path: String, body: JsonObject, token: String, idempotencyKey: String?): HttpRequest {
        require(path == "/telemetry/generations" || path == "/telemetry/skill-usage-events")
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        require(uri.isAbsolute && uri.scheme in setOf("http", "https"))
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        return builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
    }
}
