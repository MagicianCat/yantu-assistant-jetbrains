package com.taikang.yantu.telemetry

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path

object WorkspacePath {
    fun parse(rawValue: String?): Path? {
        if (rawValue.isNullOrBlank()) return null
        val raw = rawValue.trim()
        return runCatching {
            val path = if (raw.startsWith("file:", true)) {
                runCatching { Path.of(URI.create(raw)) }.getOrElse {
                    var fallback = raw.replace(Regex("^file://", RegexOption.IGNORE_CASE), "")
                    fallback = URLDecoder.decode(fallback.replace("+", "%2B"), Charsets.UTF_8)
                    if (System.getProperty("os.name").startsWith("Windows", true)) fallback = fallback.replace(Regex("^/([A-Za-z]:)"), "$1")
                    Path.of(fallback)
                }
            } else Path.of(raw)
            path.toAbsolutePath().normalize()
        }.getOrNull()
    }
}
