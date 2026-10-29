package com.taikang.yantu.telemetry

import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/** Bounded diagnostic scan of recent JetBrains log tails; all results are keyed by requestId. */
class JetBrainsLogScanner(private val root: Path, private val now: () -> Instant = Instant::now) {
    data class Turn(val requestId: String, val state: String, val inputTokens: Long?, val outputTokens: Long?,
                    val totalTokens: Long?, val toolCalls: Int, val conversationId: String? = null,
                    val startedAt: String? = null, val endedAt: String? = null,
                    val workspaceHash: String? = null, val exactUsage: Boolean = false,
                    val workspacePath: String? = null,
                    val skillCallDetails: List<StructuredScanner.SkillCall> = emptyList())
    data class Result(val files: Int, val turns: Map<String, Turn>)
    private data class MutableTurn(var state: String = "RUNNING", var input: Long? = null, var output: Long? = null,
                                   var total: Long? = null, val tools: MutableSet<String> = mutableSetOf(),
                                   var conversationId: String? = null, var startedAt: String? = null,
                                   var endedAt: String? = null, var workspaceHash: String? = null, var exactUsage: Boolean = false,
                                   val skills: MutableMap<String, StructuredScanner.SkillCall> = linkedMapOf())
    private data class Cached(val size: Long, val modified: Long, val turns: Map<String, Turn>)
    private val cache = mutableMapOf<Path, Cached>()
    private val start = Regex("\\[AgentReporter\\] onAgentStart:.*?conversationId=([^\\s]+).*?requestId=([^\\s]+)")
    private val end = Regex("\\[AgentReporter\\]\\s+onAgentEnd:.*conversationId=([^\\s]+)\\s+requestId=([^\\s]+)\\s+error=(true|false)")
    private val step = Regex("notifyStepEnd,\\s*step:\\s*\\d+,\\s*requestId:\\s*([^,\\s]+).*?usage:\\s*(\\{.*?\\})(?:,\\s*isMaxTokenLimit|$)")
    private val finalUsage = Regex("Agent execution successful with usage:\\s*(\\{.*\\})")
    private val tool = Regex("tool-call-streaming-start\\s+开始解析:\\s*(\\S+)\\s+-\\s+(\\S+)")
    private val skillArgument = Regex("参数解析完成:\\s*(\\S+)\\s+-\\s*参数:\\s*command\\s+-\\s*值:\\s*([^\\s]+)")

    fun scan(): Result {
        val dates = (0L..1L).map { LocalDate.ofInstant(now().minusSeconds(it * 86400), ZoneId.systemDefault()).toString() }.toSet()
        val files = mutableListOf<Path>()
        for (date in dates) {
            val day = root.resolve(date)
            if (!Files.isDirectory(day)) continue
            runCatching { Files.newDirectoryStream(day, "*.log").use { stream ->
                stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }.take(30 - files.size).forEach(files::add)
            } }
        }
        val turns = linkedMapOf<String, Turn>()
        files.sortBy { it.toString() }
        for (file in files) {
            val size = runCatching { Files.size(file) }.getOrNull() ?: continue
            val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: continue
            val cached = cache[file]
            val parsed = if (cached != null && cached.size == size && cached.modified == modified) cached.turns
                else parseFile(file).also { cache[file] = Cached(size, modified, it) }
            turns.putAll(parsed)
        }
        cache.keys.retainAll(files.toSet())
        return Result(files.size, turns)
    }

    private fun parseFile(file: Path): Map<String, Turn> {
        val turns = linkedMapOf<String, MutableTurn>()
        val toolNames = mutableMapOf<String, String>()
        var active: String? = null
        var workspacePath: String? = null
        for (line in tailLines(file)) {
                if (line.startsWith("Workspace Path:")) { workspacePath = line.removePrefix("Workspace Path:").trim(); continue }
                val begun = start.find(line)
                if (begun != null) {
                    active = begun.groupValues[2]
                    val turn = turns.getOrPut(active) { MutableTurn() }
                    turn.conversationId = begun.groupValues[1]
                    turn.startedAt = lineTimestamp(line) ?: turn.startedAt
                    turn.workspaceHash = file.fileName.toString().removeSuffix(".log").substringAfterLast("__", "")
                    continue
                }
                val ended = end.find(line)
                if (ended != null) {
                    val id = ended.groupValues[2]
                    val turn = turns.getOrPut(id) { MutableTurn() }
                    turn.state = if (ended.groupValues[3] == "false") "COMPLETED" else "FAILED"
                    turn.conversationId = ended.groupValues[1]
                    turn.endedAt = lineTimestamp(line) ?: turn.endedAt
                    if (active == id) active = null
                    continue
                }
                val usage = step.find(line)
                if (usage != null) {
                    val id = usage.groupValues[1]
                    val row = runCatching { JsonParser.parseString(usage.groupValues[2]).asJsonObject }.getOrNull()
                    if (row != null) {
                        val turn = turns.getOrPut(id) { MutableTurn() }
                        turn.input = row.long("inputTokens") ?: turn.input
                        turn.output = row.long("outputTokens") ?: turn.output
                        turn.total = row.long("totalTokens") ?: turn.total
                    }
                    continue
                }
                val final = finalUsage.find(line)
                if (final != null && active != null) {
                    val row = runCatching { JsonParser.parseString(final.groupValues[1]).asJsonObject }.getOrNull()
                    if (row != null) {
                        val turn = turns.getOrPut(active) { MutableTurn() }
                        turn.input = row.long("inputTokens") ?: turn.input
                        turn.output = row.long("outputTokens") ?: turn.output
                        turn.total = row.long("totalTokens") ?: turn.total
                        turn.exactUsage = true
                    }
                    continue
                }
                val called = tool.find(line)
                if (called != null && active != null) {
                    turns[active]?.tools?.add(called.groupValues[1])
                    toolNames[called.groupValues[1]] = called.groupValues[2]
                    continue
                }
                val skill = skillArgument.find(line)
                if (skill != null && toolNames.remove(skill.groupValues[1]) == "use_skill") {
                    val key = skill.groupValues[2]
                    val running = turns.values.filter { it.conversationId != null && it.state == "RUNNING" }
                    if (running.size == 1 && key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
                        running.single().skills[skill.groupValues[1]] = StructuredScanner.SkillCall(key, skill.groupValues[1], lineTimestamp(line))
                }
            }
        return turns.mapValues { (id, value) -> Turn(id, value.state, value.input, value.output, value.total, value.tools.size,
            value.conversationId, value.startedAt, value.endedAt, value.workspaceHash, value.exactUsage, workspacePath,
            value.skills.values.toList()) }
    }

    private fun lineTimestamp(line: String): String? {
        val match = Regex("^\\[?(\\d{4}[-/]\\d{2}[-/]\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(?:[.,](\\d{1,3}))?").find(line) ?: return null
        val date = match.groupValues[1].replace('/', '-')
        val millis = match.groupValues.getOrElse(3) { "" }.padEnd(3, '0').ifEmpty { "000" }
        return runCatching { LocalDateTime.parse("${date}T${match.groupValues[2]}.$millis", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault()).toInstant().toString() }.getOrNull()
    }

    private fun tailLines(path: Path): List<String> = runCatching {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val length = minOf(size, 2L * 1024 * 1024).toInt()
            val startOffset = size - length
            val buffer = ByteBuffer.allocate(length)
            channel.position(startOffset)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            buffer.flip()
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE).decode(buffer).toString()
            val complete = text.substring(0, text.lastIndexOf('\n').takeIf { it >= 0 } ?: 0)
            val withoutPartialHead = if (startOffset > 0) complete.substringAfter('\n', "") else complete
            withoutPartialHead.lineSequence().take(50_000).toList()
        }
    }.getOrDefault(emptyList())
    private fun com.google.gson.JsonObject.long(key: String): Long? = get(key)?.let { runCatching { it.asLong }.getOrNull() }
}
