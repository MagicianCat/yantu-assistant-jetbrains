package com.taikang.yantu.platform

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object SessionFixture {
    @JvmStatic fun main(args: Array<String>) {
        val empty = JsonObject().apply { addProperty("sessionKey", "session-1"); add("title", com.google.gson.JsonNull.INSTANCE) }
        val normalized = PlatformApi.normalizeSessions(JsonArray().apply { add(empty) })
        check(normalized.size() == 1)
        check(normalized[0].asJsonObject.get("title").asString == "新对话")
        check(!normalized[0].asJsonObject.has("createdAt"))
        check(runCatching { PlatformApi.normalizeSession(JsonObject()) }.isFailure)
    }
}
