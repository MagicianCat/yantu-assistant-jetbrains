package com.taikang.yantu.telemetry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Read-only, bounded scan of TkCoding's JetBrains metadata. Message text is never retained. */
class StructuredScanner(private val dataRoot: Path, private val now: () -> Instant = Instant::now) {
    data class SkillCall(val skillKey: String, val toolCallId: String, val invokedAt: String?)
    data class FileTypeMetric(val type: String, val extension: String, val linesAdded: Long,
                              val linesDeleted: Long, val filesCreated: Int, val filesModified: Int)
    data class Turn(
        val requestId: String, val conversationId: String, val state: String,
        val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?,
        val toolCalls: Int, val skillCalls: Int, val addedLines: Long, val removedLines: Long,
        val changedFiles: Int, val model: String?, val startedAt: String?,
        val nativeInstallationId: String = "", val workspaceHash: String = "",
        val workspacePath: String? = null, val skillCallDetails: List<SkillCall> = emptyList(),
        val fileTypes: List<FileTypeMetric> = emptyList(), val filesCreated: Int = 0,
        val filesModified: Int = 0, val treePresent: Boolean = false,
        val cacheReadTokens: Long? = null, val cacheWriteTokens: Long? = null,
        val cacheMissTokens: Long? = null, val thinkingTokens: Long? = null,
        val lastTokens: Long? = null, val modelCallCount: Long? = null,
        val endedAt: String? = null,
    )
    data class Result(val partitions: Int, val sessions: Int, val requests: Int, val incomplete: Int,
                      val latest: Turn?, val turns: List<Turn> = emptyList())
    private val safeName = Regex("[A-Za-z0-9_-]{1,256}")
    private val maxBytes = 8L * 1024 * 1024
    private val cutoff get() = now().minusSeconds(TimeUnit.DAYS.toSeconds(7))

    fun scan(): Result {
        var partitions = 0; var sessions = 0; var requests = 0; var incomplete = 0
        var latest: Turn? = null
        val allTurns = mutableListOf<Turn>()
        var latestTime = ""
        for (installation in directories(dataRoot)) {
            if (!safeName.matches(installation.fileName.toString())) continue
            val client = installation.resolve("JetBrains")
            for (native in directories(client)) {
                if (!safeName.matches(native.fileName.toString())) continue
                partitions++
                val history = native.resolve("history")
                for (workspace in directories(history)) {
                    if (!safeName.matches(workspace.fileName.toString())) continue
                    for (session in directories(workspace)) {
                        if (sessions >= 500) return Result(partitions, sessions, requests, incomplete, latest, allTurns)
                        if (!safeName.matches(session.fileName.toString())) continue
                        val indexPath = session.resolve("index.json")
                        if (runCatching { Files.getLastModifiedTime(indexPath).toInstant().isBefore(cutoff) }.getOrDefault(true)) continue
                        sessions++
                        val index = readJson(indexPath)?.obj()
                        if (index == null) { incomplete++; continue }
                        val requestsArray = index.get("requests")?.array()
                        if (requestsArray == null) { incomplete++; continue }
                        val treePath = native.resolve("file-tree").resolve(workspace.fileName).resolve(session.fileName).resolve("file-tree.json")
                        val tree = if (Files.exists(treePath)) readJson(treePath) else null
                        if (Files.exists(treePath) && tree == null) incomplete++
                        for (requestElement in requestsArray) {
                            val request = requestElement.obj() ?: continue
                            val id = request.string("id")?.takeIf { safeName.matches(it) } ?: continue
                            val started = request.string("startedAt") ?: request.get("startedAt")?.takeIf { it.isJsonPrimitive }?.asString
                            val state = request.string("state") ?: "unknown"
                            val requestInstant = parseInstant(started)
                            if (requestInstant == null || requestInstant.isBefore(cutoff)) continue
                            val messages = request.get("messages")?.array()
                            val toolIds = mutableSetOf<String>()
                            val skillIds = mutableSetOf<String>()
                            val callDetails = linkedMapOf<String, SkillCall>()
                            var model: String? = null
                            var lastMessageAt: String? = null
                            var complete = true
                            for (entry in messages ?: JsonArray()) {
                                val embedded = entry.obj()
                                val messageId = if (entry.isJsonPrimitive) runCatching { entry.asString }.getOrNull() else embedded?.string("id")
                                val message = if (embedded?.has("message") == true) embedded else
                                    messageId?.takeIf { safeName.matches(it) }?.let { readJson(session.resolve("messages").resolve("$it.json"))?.obj() }
                                if (message == null) { complete = false; break }
                                val extra = message.get("extra").obj()
                                if (extra?.string("requestId")?.let { it != id } == true) continue
                                model = extra?.string("modelId") ?: extra?.string("modelName") ?: model
                                val createdAt = message.string("createdAt")
                                if (parseInstant(createdAt) != null && (lastMessageAt == null ||
                                    (parseInstant(createdAt)?.isAfter(parseInstant(lastMessageAt)) == true))) lastMessageAt = createdAt
                                collectTools(message.get("message") ?: message, toolIds, skillIds, callDetails,
                                    message.string("createdAt") ?: started, 0)
                            }
                            if (!complete) { incomplete++; continue }
                            val usage = if (state.equals("complete", true) || state.equals("completed", true)) request.get("usage").obj() else null
                            val diffs = if (tree != null) fileMetrics(tree, id, session.fileName.toString()) else Diff(0, 0, 0)
                            val turn = Turn(id, session.fileName.toString(), state, usage?.number("inputTokens"), usage?.number("outputTokens"),
                                usage?.number("totalTokens"), toolIds.size, skillIds.size, diffs.added, diffs.removed, diffs.files, model, started,
                                native.fileName.toString(), workspace.fileName.toString(),
                                index.string("workspacePath") ?: index.string("workspace") ?: request.string("workspacePath"),
                                callDetails.values.toList(), diffs.fileTypes, diffs.created, diffs.modified, tree != null,
                                usage?.number("cacheTokens") ?: usage?.number("cacheReadTokens"),
                                usage?.number("cachedWriteTokens") ?: usage?.number("cacheWriteTokens"),
                                usage?.number("cachedMissTokens") ?: usage?.number("cacheMissTokens"),
                                usage?.number("thinkingTokens"), usage?.number("lastTokens"), usage?.number("modelCallCount"),
                                if (state.equals("complete", true) || state.equals("completed", true)) lastMessageAt else null)
                            requests++
                            allTurns.add(turn)
                            if (started.orEmpty() >= latestTime) { latestTime = started.orEmpty(); latest = turn }
                        }
                    }
                }
            }
        }
        return Result(partitions, sessions, requests, incomplete, latest, allTurns)
    }

    private data class Diff(val added: Long, val removed: Long, val files: Int,
                            val created: Int = 0, val modified: Int = 0,
                            val fileTypes: List<FileTypeMetric> = emptyList())
    private data class FileAccumulator(var added: Long = 0, var removed: Long = 0, var created: Boolean = false)
    private val categories = mapOf("java" to "JAVA", "kt" to "KOTLIN", "kts" to "KOTLIN",
        "js" to "JAVASCRIPT", "jsx" to "JAVASCRIPT", "ts" to "TYPESCRIPT", "tsx" to "TYPESCRIPT",
        "vue" to "VUE", "html" to "HTML", "css" to "CSS", "sql" to "SQL", "xml" to "XML",
        "yaml" to "YAML", "yml" to "YAML", "json" to "JSON", "py" to "PYTHON", "go" to "GO",
        "sh" to "SHELL", "md" to "MARKDOWN")
    private fun fileMetrics(tree: JsonElement, requestId: String, conversationId: String): Diff {
        val files = tree.array() ?: tree.obj()?.get("files")?.array() ?: return Diff(0, 0, 0)
        val byFile = linkedMapOf<String, FileAccumulator>()
        val versions = mutableSetOf<String>()
        for (fileElement in files) {
            val file = fileElement.obj() ?: continue
            val path = file.string("filePath") ?: continue
            for (versionElement in file.get("versions")?.array() ?: JsonArray()) {
                val version = versionElement.obj() ?: continue
                val meta = version.get("metadata").obj() ?: continue
                if (meta.string("requestId") != requestId) continue
                if (meta.string("conversationId")?.let { it != conversationId } == true) continue
                val status = version.string("status") ?: meta.string("status") ?: continue
                if (!status.equals("accepted", true)) continue
                val versionId = version.string("versionId") ?: meta.string("toolCallId") ?: continue
                if (!versions.add("$path|$versionId")) continue
                val diff = version.get("diff").obj() ?: continue
                val metric = byFile.getOrPut(path) { FileAccumulator() }
                metric.added += (diff.number("addedLines") ?: 0).coerceAtLeast(0)
                metric.removed += (diff.number("removedLines") ?: 0).coerceAtLeast(0)
                metric.created = metric.created || version.get("isNewFile")?.let { runCatching { it.asBoolean }.getOrNull() } == true ||
                    (version.string("changeType") ?: "").matches(Regex("(?i)create|add|new"))
            }
        }
        val types = linkedMapOf<String, FileTypeMetric>()
        for ((path, metric) in byFile) {
            val extension = path.substringAfterLast('.', "").lowercase().take(20)
            val category = categories[extension] ?: "OTHER"
            val key = "$category|$extension"
            val prior = types[key]
            types[key] = FileTypeMetric(category, extension, (prior?.linesAdded ?: 0) + metric.added,
                (prior?.linesDeleted ?: 0) + metric.removed, (prior?.filesCreated ?: 0) + if (metric.created) 1 else 0,
                (prior?.filesModified ?: 0) + if (metric.created) 0 else 1)
        }
        return Diff(byFile.values.sumOf { it.added }, byFile.values.sumOf { it.removed }, byFile.size,
            byFile.values.count { it.created }, byFile.values.count { !it.created }, types.values.toList())
    }

    private fun collectTools(value: JsonElement?, tools: MutableSet<String>, skills: MutableSet<String>,
                             calls: MutableMap<String, SkillCall>, invokedAt: String?, depth: Int) {
        if (value == null || depth > 4) return
        val decoded = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val raw = value.asString.trim()
            if (raw.startsWith("{") || raw.startsWith("[")) runCatching { JsonParser.parseString(raw) }.getOrNull() else null
        } else value
        if (decoded == null) return
        if (decoded.isJsonArray) { decoded.asJsonArray.forEach { collectTools(it, tools, skills, calls, invokedAt, depth + 1) }; return }
        val obj = decoded.obj() ?: return
        val id = obj.string("toolCallId") ?: obj.string("callId") ?: obj.string("id")
        val name = obj.string("toolName") ?: obj.string("name")
        if (id != null && name != null) {
            tools.add(id)
            val args = obj.get("args").obj() ?: obj.get("arguments").obj()
            if (name == "use_skill") {
                val skillKey = args?.string("command")
                if (skillKey?.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) == true) {
                    skills.add(id)
                    calls[id] = SkillCall(skillKey, id, invokedAt)
                }
            }
        }
        for (key in listOf("toolCalls", "tools", "content", "parts", "message", "data")) collectTools(obj.get(key), tools, skills, calls, invokedAt, depth + 1)
    }

    private fun parseInstant(raw: String?): Instant? = raw?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching {
            val value = it.toLong(); Instant.ofEpochMilli(if (it.length == 10) value * 1000 else value)
        }.getOrNull()
    }
    private fun readJson(path: Path): JsonElement? = runCatching {
        val before = Files.size(path)
        if (before !in 1..maxBytes || !Files.isRegularFile(path)) return null
        val bytes = Files.readAllBytes(path)
        if (bytes.size.toLong() != before || Files.size(path) != before) return null
        JsonParser.parseString(bytes.toString(Charsets.UTF_8))
    }.getOrNull()
    private fun directories(path: Path): List<Path> = runCatching {
        if (!Files.isDirectory(path)) return emptyList()
        Files.newDirectoryStream(path).use { stream -> stream.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }.take(500).toList() }
    }.getOrDefault(emptyList())
    private fun JsonElement?.obj(): JsonObject? = when {
        this == null -> null
        isJsonObject -> asJsonObject
        isJsonPrimitive && asJsonPrimitive.isString && asString.trimStart().startsWith("{") -> runCatching { JsonParser.parseString(asString).asJsonObject }.getOrNull()
        else -> null
    }
    private fun JsonElement?.array(): JsonArray? = if (this?.isJsonArray == true) asJsonArray else null
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.number(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
}
