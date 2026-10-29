package com.taikang.yantu.platform

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder

class PlatformApi(private val transport: YantuPlatformService = YantuPlatformService.get()) {
    data class Detail(val messages: JsonArray, val recommendations: JsonArray, val latestRunKey: String?, val latestRunStatus: String?)
    fun sessions(): JsonArray {
        val value = transport.authorizedJson("GET", "/agent/sessions")
        val raw = when {
            value?.isJsonArray == true -> value.asJsonArray
            value?.isJsonObject == true -> array(value.asJsonObject, "content", "items", "records")
            else -> JsonArray()
        }
        return normalizeSessions(raw)
    }
    fun session(key: String): Detail {
        val row = transport.authorizedJson("GET", "/agent/sessions/${segment(key)}")?.asJsonObject ?: JsonObject()
        val messages = JsonArray()
        val recommendations = JsonArray()
        for (entry in array(row, "messages")) {
            val message = entry.obj() ?: continue
            normalizeMessage(message)?.let(messages::add)
            for (item in array(message.get("recommendation")?.obj(), "items")) {
                val normalized = item.obj()?.deepCopy() ?: continue
                message.string("runKey")?.let { normalized.addProperty("runKey", it) }
                recommendations.add(normalized)
            }
        }
        val latest = row.get("latestRun")?.obj()
        if (recommendations.size() == 0) for (item in array(row.get("latestRecommendation")?.obj(), "items")) {
            val normalized = item.obj()?.deepCopy() ?: continue
            latest?.string("runKey")?.let { normalized.addProperty("runKey", it) }
            recommendations.add(normalized)
        }
        return Detail(messages, recommendations, latest?.string("runKey"), latest?.string("status"))
    }
    fun createSession(osType: String): JsonObject {
        val value = transport.authorizedJson("POST", "/agent/sessions", JsonObject().apply {
            addProperty("profileKey", "skill-advisor"); add("context", context(osType))
        })?.obj() ?: error("会话响应无效")
        return normalizeSession(value.get("session")?.obj() ?: value)
    }
    fun deleteSession(key: String) { transport.authorizedJson("DELETE", "/agent/sessions/${segment(key)}") }
    fun sendMessage(key: String, content: String, osType: String): JsonObject =
        transport.authorizedJson("POST", "/agent/sessions/${segment(key)}/messages", JsonObject().apply {
            addProperty("content", content); add("context", context(osType))
        })?.obj()?.also { require(it.string("runKey") != null) } ?: error("运行响应无效")
    fun cancelRun(key: String) { transport.authorizedJson("POST", "/agent/runs/${segment(key)}:cancel") }
    fun openRunEvents(key: String, lastEventId: String? = null) = transport.openEvents("/agent/runs/${segment(key)}/events", lastEventId)
    fun createBundle(runKey: String, osType: String, skillKeys: List<String>): JsonObject =
        transport.authorizedJson("POST", "/agent/runs/${segment(runKey)}/bundle", JsonObject().apply {
            addProperty("platform", "CODEBUDDY"); addProperty("osType", osType)
            add("skillKeys", JsonArray().also { a -> skillKeys.forEach(a::add) })
        })?.obj() ?: error("Bundle 响应无效")
    fun createUpdateBundle(osType: String, ids: List<Long>): JsonObject =
        transport.authorizedJson("POST", "/bundles", JsonObject().apply {
            addProperty("platform", "CODEBUDDY"); addProperty("osType", osType)
            addProperty("includeDependencies", true)
            add("rootVersionIds", JsonArray().also { a -> ids.forEach(a::add) })
        })?.obj() ?: error("Bundle 响应无效")
    fun downloadBundle(id: String): ByteArray = transport.authorizedBytes("GET", "/bundles/${segment(id)}/download")
    fun checkUpdates(osType: String, keys: List<String>): JsonArray {
        val value = transport.authorizedJson("POST", "/skill-updates:check", JsonObject().apply {
            addProperty("platform", "CODEBUDDY"); addProperty("osType", osType)
            add("skillKeys", JsonArray().also { a -> keys.forEach(a::add) })
        })
        return array(value?.obj(), "items")
    }
    fun wikiMetadata(id: Long): JsonObject = transport.authorizedJson("GET", "/wiki/documents/$id/metadata")?.obj()
        ?: error("文档名称响应无效")
    fun downloadWiki(ids: List<Long>): ByteArray = if (ids.size == 1)
        transport.authorizedBytes("GET", "/wiki/documents/${ids.single()}/download")
    else transport.authorizedBytes("POST", "/wiki/documents:download", JsonObject().apply {
        add("documentIds", JsonArray().also { a -> ids.forEach(a::add) })
    })
    private fun context(osType: String) = JsonObject().apply { addProperty("platform", "CODEBUDDY"); addProperty("osType", osType) }
    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._:-]{1,256}"))) { "标识格式无效" }
        return URLEncoder.encode(value, Charsets.UTF_8)
    }
    private fun normalizeMessage(row: JsonObject): JsonObject? {
        val rawRole = row.string("role") ?: row.string("senderType")
        val role = when (rawRole?.uppercase()) { "USER" -> "user"; "ASSISTANT" -> "assistant"; else -> return null }
        val content = row.string("content") ?: row.string("text") ?: return null
        val id = row.string("messageKey") ?: row.string("id") ?: row.get("sequence")?.toString()
            ?: row.get("sequenceNo")?.toString() ?: return null
        return JsonObject().apply {
            addProperty("id", id); addProperty("role", role); addProperty("content", content)
            row.string("runKey")?.let { addProperty("runKey", it) }
            row.string("createdAt")?.let { addProperty("createdAt", it) }
        }
    }
    private fun array(row: JsonObject?, vararg keys: String): JsonArray {
        for (key in keys) row?.get(key)?.takeIf { it.isJsonArray }?.let { return it.asJsonArray }
        return JsonArray()
    }
    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
    companion object {
        fun normalizeSessions(raw: JsonArray): JsonArray = JsonArray().also { out ->
            raw.forEach { row -> if (row.isJsonObject) out.add(normalizeSession(row.asJsonObject)) }
        }
        fun normalizeSession(row: JsonObject): JsonObject {
            val key = row.get("sessionKey")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: error("会话响应缺少 sessionKey")
            val title = row.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: "新对话"
            return JsonObject().apply {
                addProperty("sessionKey", key)
                addProperty("title", title)
                for (field in listOf("createdAt", "updatedAt"))
                    row.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.let { if (it != null) addProperty(field, it) }
            }
        }
    }
}
