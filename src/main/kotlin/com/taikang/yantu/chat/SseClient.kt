package com.taikang.yantu.chat

import com.taikang.yantu.platform.PlatformApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class SseClient(private val api: PlatformApi, private val runKey: String,
                private val onEvent: (Event) -> Unit, private val onConnection: (String) -> Unit) : AutoCloseable {
    data class Event(val id: String?, val event: String?, val data: String)
    private val closed = AtomicBoolean(false)
    @Volatile private var stream: java.io.InputStream? = null
    private var lastId: String? = null
    private val seenIds = LinkedHashSet<String>()

    fun run() {
        var attempt = 0
        while (!closed.get() && attempt < 5) {
            onConnection(if (attempt == 0) "connecting" else "reconnecting")
            try {
                val input = api.openRunEvents(runKey, lastId)
                stream = input
                onConnection("connected")
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    val decoder = SseDecoder { decoded ->
                        if (decoded.id == null || seenIds.add(decoded.id)) onEvent(decoded)
                        if (decoded.id != null) {
                            lastId = decoded.id
                            if (seenIds.size > 1000) seenIds.clear()
                        }
                    }
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        decoder.line(line)
                    }
                    decoder.flush()
                }
                if (closed.get()) break
            } catch (_: Exception) { if (closed.get()) break }
            attempt++
            if (attempt < 5) Thread.sleep(minOf(30_000L, 1000L shl attempt))
        }
        onConnection("closed")
    }
    override fun close() { closed.set(true); runCatching { stream?.close() } }
}
