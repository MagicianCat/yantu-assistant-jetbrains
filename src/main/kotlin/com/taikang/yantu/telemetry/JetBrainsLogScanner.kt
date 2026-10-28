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
import java.time.ZoneId

/** Bounded diagnostic scan of recent JetBrains log tails; all results are keyed by requestId. */
class JetBrainsLogScanner(private val root: Path, private val now: () -> Instant = Instant::now) {
    data class Turn(val requestId: String, val state: String, val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?, val toolCalls: Int)
    data class Result(val files: Int, val turns: Map<String, Turn>)
    private data class MutableTurn(var state: String = "RUNNING", var input: Long? = null, var output: Long? = null,
                                   var total: Long? = null, val tools: MutableSet<String> = mutableSetOf())
    private val start = Regex("\\[AgentReporter\\] onAgentStart:.*?conversationId=([^\\s]+).*?requestId=([^\\s]+)")
    private val end = Regex("\\[AgentReporter\\]\\s+onAgentEnd:.*conversationId=([^\\s]+)\\s+requestId=([^\\s]+)\\s+error=(true|false)")
    private val step = Regex("notifyStepEnd,\\s*step:\\s*\\d+,\\s*requestId:\\s*([^,\\s]+).*?usage:\\s*(\\{.*?\\})(?:,\\s*isMaxTokenLimit|$)")
    private val tool = Regex("tool-call-streaming-start\\s+开始解析:\\s*(\\S+)\\s+-\\s+(\\S+)")

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
        val turns = linkedMapOf<String, MutableTurn>()
        for (file in files) {
            var active: String? = null
            for (line in tailLines(file)) {
                val begun = start.find(line)
                if (begun != null) { active = begun.groupValues[2]; turns.getOrPut(active) { MutableTurn() }; continue }
                val ended = end.find(line)
                if (ended != null) {
                    val id = ended.groupValues[2]
                    turns.getOrPut(id) { MutableTurn() }.state = if (ended.groupValues[3] == "false") "COMPLETED" else "FAILED"
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
                val called = tool.find(line)
                if (called != null && active != null) turns[active]?.tools?.add(called.groupValues[1])
            }
        }
        return Result(files.size, turns.mapValues { (id, value) -> Turn(id, value.state, value.input, value.output, value.total, value.tools.size) })
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
