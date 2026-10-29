package com.taikang.yantu.telemetry

import com.google.gson.JsonObject
import java.nio.file.Files
import java.time.Instant

object TelemetryPipelineFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-telemetry-fixture")
        try {
            val workspace = root.resolve("project")
            val markerDir = workspace.resolve(".codebuddy/skills/security-review")
            Files.createDirectories(markerDir)
            Files.writeString(markerDir.resolve(".yantu-platform-skill.json"),
                """{"source":"yantu-platform","skillKey":"security-review","skillVersionId":42,"installationId":"marker-install"}""")
            val mapper = GenerationMapper("plugin-install", SkillMarkerResolver(root))
            val first = StructuredScanner.Turn("request-1", "conversation-a", "complete", 10, 2, 12,
                1, 1, 0, 0, 0, "auto", "2026-10-27T01:00:00Z", "native-a", "workspace-a", workspace.toString(),
                listOf(StructuredScanner.SkillCall("security-review", "tool-1", "2026-10-27T01:00:05Z")),
                emptyList(), 0, 0, true)
            val second = first.copy(requestId = "request-2", inputTokens = 20, outputTokens = 4, totalTokens = 24,
                addedLines = 10, removedLines = 2, changedFiles = 2, skillCallDetails = emptyList(),
                fileTypes = listOf(StructuredScanner.FileTypeMetric("JAVA", "java", 10, 2, 0, 2)),
                filesModified = 2, startedAt = "2026-10-27T02:00:00Z")
            val mapped1 = mapper.structured(first, null)!!
            val mapped2 = mapper.structured(second, null)!!
            check(mapped1.payload.get("sessionId").asString == "conversation-a")
            check(mapped1.payload.get("clientSessionId") == null)
            check(mapped1.payload.get("projectName").asString == "project")
            check(mapped1.payload.get("projectSource").asString == "LOCAL")
            check(mapped1.payload.getAsJsonArray("skillKeys")[0].asString == "security-review")
            check(mapped1.payload.getAsJsonArray("skillInvocations").size() == 1)
            val localProjectKey = mapped1.payload.get("projectKey").asString
            check(localProjectKey == ProjectResolver("plugin-install").resolve(workspace.toString())!!.key)
            val gitConfig = workspace.resolve(".git/config")
            Files.createDirectories(gitConfig.parent)
            Files.writeString(gitConfig, "[remote \"origin\"]\n  url = git@example.com:team/sample.git\n")
            val gitProject = ProjectResolver("plugin-install").resolve(workspace.toString())!!
            check(gitProject.name == "sample" && gitProject.source == "GIT_REMOTE" && gitProject.key != localProjectKey)
            check(mapped1.generationId != mapped2.generationId)
            check(mapped1.skills.size == 1 && mapped2.skills.isEmpty())
            check(mapped1.payload.getAsJsonObject("code").get("linesAdded").asInt == 0)
            check(mapped2.payload.getAsJsonObject("code").get("linesAdded").asInt == 10)
            check(mapped2.payload.getAsJsonObject("code").get("filesModified").asInt == 2)
            check(mapped2.payload.getAsJsonArray("fileTypes")[0].asJsonObject.get("type").asString == "JAVA")
            val event = mapped1.skills.single()
            check(event.get("client").asString == "JetBrains")
            check(event.get("eventId").asString == mapper.structured(first, null)!!.skills.single().get("eventId").asString)
            val outbox = DurableOutbox(root.resolve("local-state"))
            check(outbox.upsertGeneration(mapped1.generationId, mapped1.payload, mapped1.quality)?.revision == 1)
            check(outbox.upsertGeneration(mapped1.generationId, mapped1.payload, mapped1.quality) == null)
            check(outbox.enqueueSkill(event.get("eventId").asString, event) != null)
            check(outbox.enqueueSkill(event.get("eventId").asString, event) == null)
            check(outbox.stats().first == 2)
            val later = System.currentTimeMillis() + 40_000
            check(outbox.pending(later).size == 2)
            outbox.ack("generation:${mapped1.generationId}", 1)
            outbox.ack("skill:${event.get("eventId").asString}", 1)
            check(outbox.stats().first == 0)
            val reopened = DurableOutbox(root.resolve("local-state"))
            check(reopened.installationId == outbox.installationId && reopened.stats().first == 0)
            val updated = mapped1.payload.deepCopy().apply { getAsJsonObject("code").addProperty("linesAdded", 11) }
            check(reopened.upsertGeneration(mapped1.generationId, updated, "STRUCTURED")?.revision == 2)
            check(reopened.stats().first == 1)
            check(reopened.upsertGeneration(mapped1.generationId, mapped1.payload, "LOG_FINAL") == null)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
