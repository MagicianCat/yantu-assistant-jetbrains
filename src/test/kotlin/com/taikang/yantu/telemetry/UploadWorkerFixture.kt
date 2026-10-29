package com.taikang.yantu.telemetry

import com.google.gson.JsonObject
import java.nio.file.Files

object UploadWorkerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-upload-fixture")
        try {
            val outbox = DurableOutbox(root)
            val payload = JsonObject().apply { addProperty("generationId", "g-1"); addProperty("captureQuality", "STRUCTURED") }
            outbox.upsertGeneration("g-1", payload, "STRUCTURED")
            val readyAt = System.currentTimeMillis() + 40_000
            val offline = UploadWorker(outbox, { false }) { _, _, _ -> error("must not send") }
            check(offline.flushOnce(readyAt).uploaded == 0 && outbox.stats().first == 1)
            var attempts = 0
            val worker = UploadWorker(outbox, { true }) { path, body, key ->
                check(path == "/telemetry/generations" && body.get("revision").asInt == 1 && key == null)
                attempts++
                if (attempts == 1) 503 else 200
            }
            check(worker.flushOnce(readyAt).error?.contains("503") == true)
            check(outbox.stats().first == 1)
            check(worker.flushOnce(readyAt + 400_000).uploaded == 1)
            check(outbox.stats().first == 0)
            check(worker.flushOnce(readyAt + 800_000).uploaded == 0 && attempts == 2)
            val recovered = DurableOutbox(root)
            check(recovered.stats().first == 0)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
