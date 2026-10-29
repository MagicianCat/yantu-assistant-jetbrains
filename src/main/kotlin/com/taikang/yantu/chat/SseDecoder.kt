package com.taikang.yantu.chat

/** A line decoder; BufferedReader supplies UTF-8 decoded, CRLF-normalized lines. */
class SseDecoder(private val emit: (SseClient.Event) -> Unit) {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    fun line(line: String) {
        if (line.isEmpty()) { flush(); return }
        if (line.startsWith(':')) return
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "id" -> if (!value.contains('\u0000')) id = value
            "data" -> if (data.length + value.length < 1_000_000) data.append(value).append('\n')
        }
    }
    fun flush() {
        if (data.isNotEmpty()) emit(SseClient.Event(id, event, data.toString().removeSuffix("\n")))
        event = null; id = null; data.setLength(0)
    }
}
