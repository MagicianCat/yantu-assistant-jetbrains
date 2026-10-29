package com.taikang.yantu.telemetry

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class SkillMarkerResolver(private val home: Path = Path.of(System.getProperty("user.home"))) {
    data class Marker(val skillKey: String, val skillVersionId: Long?, val installationId: String?)
    fun resolve(key: String, workspace: String?): Marker? {
        if (!key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) return null
        val roots = mutableListOf<Path>()
        if (!workspace.isNullOrBlank()) {
            runCatching {
                val raw = workspace.trim()
                (if (raw.startsWith("file:", true)) Path.of(java.net.URI.create(raw)) else Path.of(raw))
                    .toAbsolutePath().normalize()
            }.getOrNull()
                ?.takeIf { it.parent != null && Files.isDirectory(it) }?.let(roots::add)
        }
        roots.add(home)
        for (root in roots) {
            val marker = root.resolve(".codebuddy").resolve("skills").resolve(key).resolve(".yantu-platform-skill.json")
            val row = runCatching {
                if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker) || Files.size(marker) !in 1..65_536) return@runCatching null
                JsonParser.parseString(Files.readString(marker)).asJsonObject
            }.getOrNull() ?: continue
            if (row.get("source")?.asString != "yantu-platform" || row.get("skillKey")?.asString != key) continue
            val versionId = row.get("skillVersionId")?.let { runCatching { it.asLong }.getOrNull() }
            val installationId = row.get("installationId")?.let { runCatching { it.asString.take(64) }.getOrNull() }
            return Marker(key, versionId, installationId)
        }
        return null
    }
}

/** Maps one request to the exact backend DTO; no message text or file path enters the payload. */
class GenerationMapper(private val installationId: String, private val markers: SkillMarkerResolver = SkillMarkerResolver()) {
    private val projects = ProjectResolver(installationId)
    data class Mapped(val generationId: String, val quality: String, val payload: JsonObject,
                      val skills: List<JsonObject>)

    fun structured(turn: StructuredScanner.Turn, log: JetBrainsLogScanner.Turn?): Mapped? {
        val status = status(turn.state) ?: return null
        val startedAt = instant(turn.startedAt) ?: return null
        val id = generationId(turn.workspaceHash, turn.conversationId, turn.requestId)
        val logEndedAt = instant(log?.endedAt)
        val endedAt = logEndedAt ?: instant(turn.endedAt)
        val durationSource = if (logEndedAt != null) "LOG_END" else "STRUCTURED_MESSAGE_END"
        val workspacePath = turn.workspacePath ?: log?.workspacePath
        val project = projects.resolve(workspacePath)
        val invocations = linkedMapOf<String, Pair<StructuredScanner.SkillCall, SkillMarkerResolver.Marker>>()
        for (call in (turn.skillCallDetails + (log?.skillCallDetails ?: emptyList())).distinctBy { it.toolCallId }) {
            val marker = markers.resolve(call.skillKey, workspacePath) ?: continue
            invocations[call.toolCallId] = call to marker
        }
        val skillKeys = JsonArray().also { array -> invocations.values.map { it.second.skillKey }.distinct().forEach(array::add) }
        val skillInvocations = JsonArray()
        val skillEvents = mutableListOf<JsonObject>()
        for ((call, marker) in invocations.values) {
            val invokedAt = instant(call.invokedAt) ?: startedAt
            skillInvocations.add(JsonObject().apply {
                addProperty("skillKey", marker.skillKey)
                marker.skillVersionId?.let { addProperty("skillVersionId", it) }
                addProperty("invokedAt", invokedAt.toString())
            })
            val eventId = stableEventId(turn.workspaceHash, turn.conversationId, turn.requestId, call.toolCallId)
            skillEvents.add(JsonObject().apply {
                addProperty("eventId", eventId); addProperty("skillKey", marker.skillKey)
                marker.skillVersionId?.let { addProperty("skillVersionId", it) }
                marker.installationId?.let { addProperty("installationId", it) }
                addProperty("localDirectory", workspacePath?.take(2048) ?: "UNKNOWN")
                addProperty("clientSessionId", turn.conversationId); addProperty("generationId", id)
                addProperty("client", "JetBrains"); addProperty("clientVersion", "IDEA-POC")
                turn.model?.let { addProperty("model", it.take(256)) }
                addProperty("invokedAt", invokedAt.toString())
            })
        }
        val usage = JsonObject().apply {
            turn.inputTokens?.let { addProperty("inputTokens", it) }
            turn.outputTokens?.let { addProperty("outputTokens", it) }
            turn.totalTokens?.let { addProperty("totalTokens", it) }
            turn.cacheReadTokens?.let { addProperty("cacheReadTokens", it) }
            turn.cacheWriteTokens?.let { addProperty("cacheWriteTokens", it) }
            turn.cacheMissTokens?.let { addProperty("cacheMissTokens", it) }
            turn.thinkingTokens?.let { addProperty("thinkingTokens", it) }
            turn.lastTokens?.let { addProperty("lastTokens", it) }
            turn.modelCallCount?.let { addProperty("modelCallCount", it.coerceAtMost(Int.MAX_VALUE.toLong())) }
            addProperty("source", "STRUCTURED_REQUEST_USAGE")
            addProperty("quality", if (turn.inputTokens != null && turn.outputTokens != null) "EXACT" else "PARTIAL")
        }
        val code = JsonObject().apply {
            addProperty("linesAdded", turn.addedLines); addProperty("linesDeleted", turn.removedLines)
            addProperty("filesCreated", turn.filesCreated); addProperty("filesModified", turn.filesModified)
        }
        val fileTypes = JsonArray()
        for (file in turn.fileTypes) fileTypes.add(JsonObject().apply {
            addProperty("type", file.type); addProperty("extension", file.extension)
            addProperty("linesAdded", file.linesAdded); addProperty("linesDeleted", file.linesDeleted)
            addProperty("filesCreated", file.filesCreated); addProperty("filesModified", file.filesModified)
        })
        val mismatch = log?.totalTokens != null && turn.totalTokens != null && log.totalTokens != turn.totalTokens
        val payload = JsonObject().apply {
            addProperty("generationId", id); addProperty("sessionId", turn.conversationId)
            addProperty("clientInstallationId", installationId)
            project?.let { addProperty("projectKey", it.key); addProperty("projectName", it.name); addProperty("projectSource", it.source) }
            addProperty("startedAt", startedAt.toString()); endedAt?.let { addProperty("endedAt", it.toString()) }
            endedAt?.let { addProperty("durationMs", (it.toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0))
                addProperty("durationSource", durationSource) }
            add("skillKeys", skillKeys); add("skillInvocations", skillInvocations)
            add("usage", usage); add("code", code); add("fileTypes", fileTypes)
            addProperty("toolCallCount", maxOf(turn.toolCalls, log?.toolCalls ?: 0))
            addProperty("toolFailureCount", 0); addProperty("status", status)
            addProperty("captureQuality", "STRUCTURED"); turn.model?.let { addProperty("modelId", it.take(128)) }
            addProperty("observedLog", log != null); addProperty("observedStructured", true)
            addProperty("tokenMismatch", mismatch)
            if (turn.treePresent) addProperty("codeSource", "FILE_TREE_REQUEST_DIFF")
        }
        return Mapped(id, "STRUCTURED", payload, skillEvents)
    }

    fun logOnly(turn: JetBrainsLogScanner.Turn): Mapped? {
        if (turn.state !in setOf("COMPLETED", "FAILED", "CANCELLED")) return null
        val conversationId = turn.conversationId ?: return null
        val startedAt = instant(turn.startedAt) ?: return null
        val endedAt = instant(turn.endedAt)
        val id = generationId(turn.workspaceHash.orEmpty(), conversationId, turn.requestId)
        val project = projects.resolve(turn.workspacePath)
        val payload = JsonObject().apply {
            addProperty("generationId", id); addProperty("sessionId", conversationId)
            addProperty("clientInstallationId", installationId)
            project?.let { addProperty("projectKey", it.key); addProperty("projectName", it.name); addProperty("projectSource", it.source) }
            addProperty("startedAt", startedAt.toString()); endedAt?.let { addProperty("endedAt", it.toString()) }
            endedAt?.let { addProperty("durationMs", (it.toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0))
                addProperty("durationSource", "LOG_END") }
            add("skillKeys", JsonArray()); add("skillInvocations", JsonArray())
            add("code", JsonObject().apply { addProperty("linesAdded", 0); addProperty("linesDeleted", 0)
                addProperty("filesCreated", 0); addProperty("filesModified", 0) })
            add("fileTypes", JsonArray())
            if (turn.inputTokens != null || turn.outputTokens != null || turn.totalTokens != null) add("usage", JsonObject().apply {
                turn.inputTokens?.let { addProperty("inputTokens", it) }; turn.outputTokens?.let { addProperty("outputTokens", it) }
                turn.totalTokens?.let { addProperty("totalTokens", it) }
                addProperty("source", if (turn.exactUsage) "LOG_FINAL_USAGE" else "LOG_STEP_PARTIAL")
                addProperty("quality", if (turn.exactUsage) "EXACT" else "PARTIAL")
            })
            addProperty("toolCallCount", turn.toolCalls); addProperty("toolFailureCount", 0)
            addProperty("status", turn.state); addProperty("captureQuality", "LOG_FINAL")
            addProperty("observedLog", true); addProperty("observedStructured", false)
            addProperty("tokenMismatch", false)
        }
        return Mapped(id, "LOG_FINAL", payload, emptyList())
    }

    fun generationId(workspaceHash: String, conversationId: String, requestId: String): String =
        "jb-" + DurableOutbox.sha256("JetBrains|$workspaceHash|$conversationId|$requestId").take(40)

    private fun stableEventId(workspaceHash: String, conversationId: String, requestId: String, callId: String): String {
        val hex = DurableOutbox.sha256("JetBrains|$workspaceHash|$conversationId|$requestId|$callId")
        val bytes = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
    private fun status(raw: String): String? = when (raw.lowercase()) {
        "complete", "completed" -> "COMPLETED"
        "failed" -> "FAILED"
        "cancelled", "canceled" -> "CANCELLED"
        else -> null
    }
    private fun instant(raw: String?): Instant? = raw?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching {
            val value = it.toLong(); Instant.ofEpochMilli(if (it.length == 10) value * 1000 else value)
        }.getOrNull()
    }
}
