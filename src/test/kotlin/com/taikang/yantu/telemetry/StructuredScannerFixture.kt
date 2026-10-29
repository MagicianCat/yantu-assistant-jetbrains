package com.taikang.yantu.telemetry

import java.nio.file.Files
import java.time.Instant

/** Executable fixture so verification needs no additional test runtime dependency. */
object StructuredScannerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-scanner-fixture")
        try {
            val native = root.resolve("install-a/JetBrains/install-a")
            val session = native.resolve("history/workspace-a/conversation-a")
            val tree = native.resolve("file-tree/workspace-a/conversation-a")
            Files.createDirectories(session.resolve("messages"))
            Files.createDirectories(tree)
            Files.writeString(session.resolve("index.json"), """{"requests":[
              {"id":"request-1","startedAt":"2026-10-27T01:00:00Z","state":"complete","usage":{"inputTokens":10,"outputTokens":2,"totalTokens":12},"messages":["message-1"]},
              {"id":"request-2","startedAt":"2026-10-27T02:00:00Z","state":"complete","usage":{"inputTokens":20,"outputTokens":4,"totalTokens":24},"messages":["message-2"]}
            ]}""")
            Files.writeString(session.resolve("messages/message-1.json"), """{"extra":"{\"requestId\":\"request-1\"}","message":"{\"toolCalls\":[{\"toolCallId\":\"tool-1\",\"toolName\":\"use_skill\",\"args\":{\"command\":\"security-review\"}}]}"}""")
            Files.writeString(session.resolve("messages/message-2.json"), """{"extra":{"requestId":"request-2","modelId":"auto"},"message":{"toolCalls":[{"toolCallId":"tool-2","toolName":"edit_file"}]}}""")
            Files.writeString(tree.resolve("file-tree.json"), """[{"filePath":"ReflectUtils.java","versions":[
              {"versionId":"v1","status":"accepted","metadata":{"requestId":"request-2","conversationId":"conversation-a"},"diff":{"addedLines":6,"removedLines":1}},
              {"versionId":"v1","status":"accepted","metadata":{"requestId":"request-2","conversationId":"conversation-a"},"diff":{"addedLines":6,"removedLines":1}},
              {"versionId":"v0","status":"rejected","metadata":{"requestId":"request-1","conversationId":"conversation-a"},"diff":{"addedLines":99,"removedLines":0}}]},
              {"filePath":"JsonUtils.java","versions":[{"versionId":"v2","status":"accepted","metadata":{"requestId":"request-2","conversationId":"conversation-a"},"diff":{"addedLines":4,"removedLines":1}}]}]""")
            val scanner = StructuredScanner(root) { Instant.parse("2026-10-27T03:00:00Z") }
            repeat(2) {
                val result = scanner.scan()
                check(result.partitions == 1 && result.sessions == 1 && result.requests == 2 && result.incomplete == 0) { result }
                check(result.latest?.requestId == "request-2" && result.latest.addedLines == 10L && result.latest.removedLines == 2L && result.latest.changedFiles == 2) { result }
                check(result.latest.inputTokens == 20L && result.latest.toolCalls == 1) { result }
                val first = result.turns.single { it.requestId == "request-1" }
                val second = result.turns.single { it.requestId == "request-2" }
                check(first.addedLines == 0L && first.removedLines == 0L && first.skillCallDetails.size == 1) { first }
                check(second.addedLines == 10L && second.removedLines == 2L && second.filesModified == 2) { second }
            }
            Files.delete(session.resolve("messages/message-2.json"))
            val incomplete = scanner.scan()
            check(incomplete.requests == 1 && incomplete.incomplete == 1) { incomplete }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
