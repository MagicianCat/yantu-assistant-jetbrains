package com.taikang.yantu.telemetry

import java.nio.file.Files
import java.time.Instant

object CommandUsageFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-command-fixture")
        try {
            val workspace = root.resolve("project")
            val shortcut = workspace.resolve(".codebuddy/commands/database-design.md")
            val action = workspace.resolve(".asdm/toolsets/system-design/actions/database-design.md")
            Files.createDirectories(shortcut.parent)
            Files.createDirectories(action.parent)
            Files.createDirectories(workspace.resolve(".asdm/workspace/requirements/REQ-123-example"))
            Files.writeString(shortcut, "@.asdm/toolsets/system-design/actions/database-design.md\n")
            Files.writeString(action, "action")
            val native = root.resolve("data/install/JetBrains/native")
            val session = native.resolve("history/workspace/conversation")
            Files.createDirectories(session.resolve("messages"))
            val timestamp = Instant.now().minusSeconds(60).toString()
            Files.writeString(session.resolve("index.json"), """{"workspacePath":"${workspace.toString().replace("\\", "\\\\")}","requests":[
              {"id":"menu","startedAt":"$timestamp","state":"complete","messages":["menu"]},
              {"id":"typed","startedAt":"$timestamp","state":"complete","messages":["typed","read"]},
              {"id":"skill","startedAt":"$timestamp","state":"complete","messages":["skill"]}
            ]}""")
            Files.writeString(session.resolve("messages/menu.json"), """{"role":"user","createdAt":"$timestamp","extra":{"requestId":"menu","command":"database-design","sourceContentBlocks":[{"type":"resource_link","_meta":{"mentionType":"command","commandName":"database-design","prompt":"@.asdm/toolsets/system-design/actions/database-design.md"}}],"inputPhrase":[{"type":"command","content":"database-design"},{"type":"normal","content":" REQ-123"}]}}""")
            Files.writeString(session.resolve("messages/typed.json"), """{"role":"user","createdAt":"$timestamp","extra":{"requestId":"typed","command":"","inputPhrase":[{"type":"normal","content":"/database-design REQ-123"}]}}""")
            Files.writeString(session.resolve("messages/read.json"), """{"role":"assistant","extra":{"requestId":"typed"},"message":{"toolCalls":[{"toolName":"read_file","toolCallId":"tool-1","args":{"filePath":".asdm/toolsets/system-design/actions/database-design.md"}}]}}""")
            Files.writeString(session.resolve("messages/skill.json"), """{"role":"user","extra":{"requestId":"skill","inputPhrase":[{"type":"normal","content":"/database-design REQ-123"}]},"message":{"toolCalls":[{"toolName":"use_skill","toolCallId":"tool-2","args":{"command":"database-design"}}]}}""")
            val turns = StructuredScanner(root.resolve("data")).scan().turns.associateBy { it.requestId }
            val mapper = CommandUsageMapper("test-installation")
            val outbox = DurableOutbox(root.resolve("state"))
            for (id in listOf("menu", "typed")) {
                val turn = turns.getValue(id)
                val events = mapper.map(turn, turn.workspacePath, "jb-$id")
                check(events.size == 1 && events[0].get("reqId")?.asString == "REQ-123-example") { events }
                check(events[0].get("triggerType").asString == id.uppercase())
                outbox.enqueueCommand(events[0].get("eventId").asString, events[0])
                check(mapper.map(turn, turn.workspacePath, "jb-$id")[0].get("eventId") == events[0].get("eventId"))
            }
            check(mapper.map(turns.getValue("skill"), workspace.toString(), "jb-skill").isEmpty())
            val restored = DurableOutbox(root.resolve("state"))
            check(restored.pending().size == 2)
            val sent = mutableListOf<String>()
            check(UploadWorker(restored, { true }, send = { path, _, _ -> sent.add(path); 200 }).flushOnce().uploaded == 2)
            check(sent.all { it == "/telemetry/command-usage-events" })
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
