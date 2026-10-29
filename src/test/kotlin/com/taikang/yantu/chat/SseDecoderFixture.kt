package com.taikang.yantu.chat

object SseDecoderFixture {
    @JvmStatic fun main(args: Array<String>) {
        val events = mutableListOf<SseClient.Event>()
        val decoder = SseDecoder(events::add)
        listOf(": heartbeat", "id: 42", "event: message.delta", "data: {\"delta\":", "data: \"你好\"}", "",
            "event: run.completed", "data: {\"runKey\":\"r-1\"}").forEach(decoder::line)
        decoder.flush()
        check(events.size == 2)
        check(events[0].id == "42" && events[0].event == "message.delta" && events[0].data == "{\"delta\":\n\"你好\"}")
        check(events[1].event == "run.completed" && events[1].id == null)
    }
}
