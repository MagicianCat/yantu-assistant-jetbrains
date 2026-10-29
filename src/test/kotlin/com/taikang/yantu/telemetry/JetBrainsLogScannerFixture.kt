package com.taikang.yantu.telemetry

import java.nio.file.Files
import java.time.Instant

object JetBrainsLogScannerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-log-fixture")
        try {
            val day = root.resolve("2026-10-27")
            Files.createDirectories(day)
            Files.writeString(day.resolve("workspace__hash.log"), """
                2026 [AgentReporter] onAgentStart: userInput=private text agent=a mode=x conversationId=conversation-a requestId=request-1
                2026 notifyStepEnd, step: 1, requestId: request-1, usage: {"inputTokens":10,"outputTokens":2,"totalTokens":12}
                2026 tool-call-streaming-start 开始解析: tool-1 - use_skill
                2026 参数解析完成: tool-1 - 参数: command - 值: security-review
                2026 [AgentReporter] onAgentEnd: conversationId=conversation-a requestId=request-1 error=false
                2026 [AgentReporter] onAgentStart: userInput=private text agent=a mode=x conversationId=conversation-a requestId=request-2
                2026 notifyStepEnd, step: 1, requestId: request-2, usage: {"inputTokens":20,"outputTokens":4,"totalTokens":24}
                2026 [AgentReporter] onAgentEnd: conversationId=conversation-a requestId=request-2 error=false
            """.trimIndent() + "\n")
            val scanner = JetBrainsLogScanner(root) { Instant.parse("2026-10-27T05:00:00Z") }
            val result = scanner.scan()
            check(result.files == 1 && result.turns.size == 2) { result }
            check(result.turns["request-1"]?.toolCalls == 1 && result.turns["request-1"]?.totalTokens == 12L) { result }
            check(result.turns["request-1"]?.skillCallDetails?.single()?.skillKey == "security-review") { result }
            check(result.turns["request-2"]?.toolCalls == 0 && result.turns["request-2"]?.totalTokens == 24L) { result }
            check(scanner.scan().turns.size == 2)
            val log = day.resolve("workspace__hash.log")
            Files.writeString(log, "2026 [AgentReporter] onAgentStart: userInput=x agent=a mode=x conversationId=conversation-a requestId=request-3", java.nio.file.StandardOpenOption.APPEND)
            check(scanner.scan().turns.size == 2) // incomplete line is retried
            Files.writeString(log, "\n2026 [AgentReporter] onAgentEnd: conversationId=conversation-a requestId=request-3 error=false\n", java.nio.file.StandardOpenOption.APPEND)
            check(scanner.scan().turns["request-3"]?.state == "COMPLETED")
            Files.writeString(log, "2026 [AgentReporter] onAgentStart: userInput=x agent=a mode=x conversationId=conversation-a requestId=request-4\n")
            check(scanner.scan().turns.keys == setOf("request-4")) // truncate/rotation-like replacement
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
