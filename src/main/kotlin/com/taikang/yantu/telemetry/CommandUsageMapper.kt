package com.taikang.yantu.telemetry

import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Emits only installed ASDM commands with structured invocation evidence. */
class CommandUsageMapper(private val installationId: String) {
    private val projects = ProjectResolver(installationId)
    private val name = Regex("[A-Za-z][A-Za-z0-9_-]{0,127}")
    private val mapping = Regex("@?\\.asdm/toolsets/([A-Za-z0-9_-]+)/actions/([A-Za-z0-9_-]+\\.md)")
    private val reqName = Regex("REQ-[\\p{L}\\p{N}_-]{1,251}")

    fun map(turn: StructuredScanner.Turn, workspace: String?, generationId: String): List<JsonObject> {
        val root = WorkspacePath.parse(workspace)?.takeIf { Files.isDirectory(it) && it.parent != null } ?: return emptyList()
        val project = projects.resolve(workspace)
        return turn.commandCalls.distinctBy { it.commandName }.mapNotNull { call ->
            if (!call.verified || !name.matches(call.commandName)) return@mapNotNull null
            val shortcut = root.resolve(".codebuddy/commands/${call.commandName}.md")
            val content = readSmallFile(shortcut) ?: return@mapNotNull null
            val match = mapping.matchEntire(content.trim()) ?: return@mapNotNull null
            val toolset = match.groupValues[1]
            if (match.groupValues[2] != "${call.commandName}.md") return@mapNotNull null
            val relative = ".asdm/toolsets/$toolset/actions/${call.commandName}.md"
            if (call.promptPath != null && call.promptPath.replace('\\', '/').removePrefix("@")
                    .equals(relative, true).not()) return@mapNotNull null
            val action = root.resolve(relative)
            if (!Files.isRegularFile(action) || Files.isSymbolicLink(action)) return@mapNotNull null
            val at = runCatching { Instant.parse(call.invokedAt ?: turn.startedAt) }.getOrNull() ?: return@mapNotNull null
            val req = resolveReq(root, toolset, call.commandName, call.reqArgument, turn.reqIdsFromFiles)
            val eventId = eventId(turn.nativeInstallationId, turn.conversationId, turn.requestId, call.commandName)
            JsonObject().apply {
                addProperty("eventId", eventId)
                addProperty("commandName", call.commandName)
                addProperty("toolsetName", toolset)
                addProperty("triggerType", call.triggerType)
                addProperty("evidenceType", call.evidenceType)
                addProperty("installationId", installationId)
                addProperty("clientSessionId", turn.conversationId)
                addProperty("generationId", generationId)
                addProperty("client", "JetBrains")
                addProperty("invokedAt", at.toString())
                project?.let { addProperty("projectKey", it.key) }
                req?.let { addProperty("reqId", it.first); addProperty("reqIdSource", it.second) }
            }
        }
    }

    private fun readSmallFile(file: Path): String? = runCatching {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) !in 1..1_000_000) null
        else Files.readString(file)
    }.getOrNull()

    private fun resolveReq(root: Path, toolset: String, command: String, argument: String?, files: List<String>): Pair<String, String>? {
        val scoped = toolset == "req-analyzer" || toolset == "system-design" && !command.startsWith("system-") ||
            toolset == "code-dev" && command in setOf("prd-code", "prd-code-web", "code-review") ||
            toolset in setOf("code-review", "testcase-design", "prototype-builder", "webui-autotest", "ui-automation-testing")
        if (!scoped) return null
        val argumentAllowed = toolset in setOf("req-analyzer", "system-design", "testcase-design", "prototype-builder", "webui-autotest") ||
            toolset == "code-dev" && command in setOf("prd-code", "prd-code-web")
        val arg = if (argumentAllowed && argument != null) matchReqDirectory(root, argument) else null
        val fileIds = files.filter { reqName.matches(it) }.map { matchReqDirectory(root, it) ?: it }.distinct()
        if (fileIds.size > 1 || arg != null && fileIds.size == 1 && arg != fileIds[0]) return null
        return when {
            arg != null -> arg to "ARGUMENT"
            fileIds.size == 1 -> fileIds[0] to "REQUEST_FILE_TREE"
            else -> null
        }
    }

    private fun matchReqDirectory(root: Path, value: String): String? {
        if (!reqName.matches(value)) return null
        val matches = mutableSetOf<String>()
        for (kind in listOf("requirements", "features")) {
            val dir = root.resolve(".asdm/workspace/$kind")
            if (!Files.isDirectory(dir)) continue
            runCatching { Files.newDirectoryStream(dir).use { stream ->
                stream.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) &&
                    (it.fileName.toString() == value || it.fileName.toString().startsWith("$value-")) }
                    .take(500).forEach { matches.add(it.fileName.toString()) }
            } }
        }
        return matches.singleOrNull()
    }

    private fun eventId(native: String, conversation: String, request: String, command: String): String {
        val hex = DurableOutbox.sha256("JetBrains|$native|$conversation|$request|$command")
        val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
