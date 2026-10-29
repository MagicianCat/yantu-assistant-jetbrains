package com.taikang.yantu.telemetry

import com.google.gson.JsonObject

/** Uploads only durable records; the backend's stable ID/revision handles a lost acknowledgement. */
class UploadWorker(
    private val outbox: DurableOutbox,
    private val loggedIn: () -> Boolean,
    private val send: (String, JsonObject, String?) -> Int,
) {
    data class Result(val uploaded: Int, val error: String? = null)
    fun flushOnce(nowMs: Long = System.currentTimeMillis()): Result {
        if (!loggedIn()) return Result(0)
        var uploaded = 0
        var lastError: String? = null
        for (item in outbox.pending(nowMs)) {
            val path = if (item.kind == "skill") "/telemetry/skill-usage-events" else "/telemetry/generations"
            val idempotencyKey = if (item.kind == "skill") item.id.removePrefix("skill:") else null
            try {
                val status = send(path, item.payload, idempotencyKey)
                if (status in 200..299) {
                    outbox.ack(item.id, item.revision)
                    uploaded++
                } else {
                    lastError = "$path HTTP $status"
                    outbox.retry(item.id, lastError, status)
                    if (status == 401 || status == 403) break
                }
            } catch (error: Exception) {
                lastError = "$path ${error.javaClass.simpleName}: ${(error.message ?: "").take(100)}"
                outbox.retry(item.id, lastError)
                break
            }
        }
        return Result(uploaded, lastError)
    }
}
