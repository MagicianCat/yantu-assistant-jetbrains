package com.taikang.yantu.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/** Atomic local state; no token, prompt, response text, or source snapshot is stored here. */
class DurableOutbox(private val root: Path) {
    data class Item(
        val id: String, val kind: String, var payload: JsonObject, var revision: Int,
        var uploadedRevision: Int, var fingerprint: String, var changedAtMs: Long,
        var retryAtMs: Long = 0, var attempts: Int = 0, var lastError: String? = null,
    )
    private val directory = root.resolve("outbox")
    private val items = linkedMapOf<String, Item>()
    val installationId: String

    init {
        Files.createDirectories(directory)
        val idFile = root.resolve("state").resolve("installation-id.txt")
        Files.createDirectories(idFile.parent)
        installationId = runCatching { Files.readString(idFile).trim().takeIf { UUID.fromString(it) != null } }.getOrNull()
            ?: UUID.randomUUID().toString().also { atomicWrite(idFile, it.toByteArray(Charsets.UTF_8)) }
        Files.newDirectoryStream(directory, "*.json").use { stream ->
            for (file in stream.take(20_000)) {
                runCatching { readItem(file) }.getOrNull()?.let { items[it.id] = it }
            }
        }
    }

    @Synchronized fun upsertGeneration(id: String, source: JsonObject, quality: String): Item? {
        val key = "generation:$id"
        val previous = items[key]
        val oldQuality = previous?.payload?.get("captureQuality")?.asString ?: ""
        if (rank(quality) < rank(oldQuality)) return null
        val payload = source.deepCopy()
        if (previous != null && quality == "STRUCTURED" && previous.payload.get("captureQuality")?.asString == "STRUCTURED" &&
            payload.get("codeSource") == null && previous.payload.get("codeSource") != null) {
            for (field in listOf("code", "fileTypes", "codeSource"))
                previous.payload.get(field)?.let { payload.add(field, it.deepCopy()) }
        }
        val fingerprint = sha256(payload.toString())
        if (previous?.fingerprint == fingerprint) return null
        val revision = (previous?.revision ?: 0) + 1
        payload.addProperty("revision", revision)
        val item = Item(key, "generation", payload, revision, previous?.uploadedRevision ?: 0,
            fingerprint, System.currentTimeMillis())
        write(item)
        items[key] = item
        return item
    }

    @Synchronized fun enqueueSkill(eventId: String, payload: JsonObject): Item? {
        val key = "skill:$eventId"
        if (items.containsKey(key)) return null
        val item = Item(key, "skill", payload.deepCopy(), 1, 0, sha256(payload.toString()), System.currentTimeMillis())
        write(item)
        items[key] = item
        return item
    }

    @Synchronized fun pending(nowMs: Long = System.currentTimeMillis()): List<Item> = items.values
        .filter { it.uploadedRevision < it.revision && it.retryAtMs <= nowMs && nowMs - it.changedAtMs >= settleMs(it) }
        .sortedWith(compareBy<Item> { if (it.kind == "skill") 0 else 1 }.thenBy { it.changedAtMs })
        .take(100).map { it.copy(payload = it.payload.deepCopy()) }

    @Synchronized fun ack(id: String, revision: Int) {
        val item = items[id] ?: return
        if (item.revision != revision) return
        item.uploadedRevision = revision
        item.attempts = 0; item.retryAtMs = 0; item.lastError = null
        write(item)
    }

    @Synchronized fun retry(id: String, message: String, status: Int? = null) {
        val item = items[id] ?: return
        item.attempts++
        val steps = longArrayOf(5, 15, 60, 300)
        val seconds = if (status != null && status in 400..499 && status != 401 && status != 429) 3600L
            else steps[min(item.attempts - 1, steps.lastIndex)]
        item.retryAtMs = System.currentTimeMillis() + (seconds + Random.nextLong(0, 3)) * 1000
        item.lastError = message.take(180)
        write(item)
    }

    @Synchronized fun stats(): Pair<Int, String?> {
        val waiting = items.values.count { it.uploadedRevision < it.revision }
        val error = items.values.toList().asReversed().firstOrNull { it.lastError != null }?.lastError
        return waiting to error
    }

    private fun settleMs(item: Item): Long = when {
        item.kind == "skill" -> 0
        item.payload.get("captureQuality")?.asString == "STRUCTURED" -> 10_000
        else -> 30_000
    }
    private fun rank(quality: String): Int = when (quality) { "STRUCTURED" -> 2; "LOG_FINAL" -> 1; else -> 0 }
    private fun path(item: Item): Path = directory.resolve(sha256(item.id).take(40) + ".json")
    private fun write(item: Item) {
        val row = JsonObject().apply {
            addProperty("id", item.id); addProperty("kind", item.kind); add("payload", item.payload)
            addProperty("revision", item.revision); addProperty("uploadedRevision", item.uploadedRevision)
            addProperty("fingerprint", item.fingerprint); addProperty("changedAtMs", item.changedAtMs)
            addProperty("retryAtMs", item.retryAtMs); addProperty("attempts", item.attempts)
            item.lastError?.let { addProperty("lastError", it) }
        }
        atomicWrite(path(item), row.toString().toByteArray(Charsets.UTF_8))
    }
    private fun readItem(path: Path): Item? {
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..2_000_000) return null
        val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
        val id = obj.get("id")?.asString ?: return null
        val kind = obj.get("kind")?.asString ?: return null
        if (kind !in setOf("generation", "skill") || path.fileName.toString() != sha256(id).take(40) + ".json") return null
        return Item(id, kind, obj.getAsJsonObject("payload") ?: return null,
            obj.get("revision")?.asInt ?: return null, obj.get("uploadedRevision")?.asInt ?: 0,
            obj.get("fingerprint")?.asString ?: "", obj.get("changedAtMs")?.asLong ?: 0,
            obj.get("retryAtMs")?.asLong ?: 0, obj.get("attempts")?.asInt ?: 0, obj.get("lastError")?.asString)
    }
    private fun atomicWrite(path: Path, bytes: ByteArray) {
        val temp = path.resolveSibling(path.fileName.toString() + ".${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
