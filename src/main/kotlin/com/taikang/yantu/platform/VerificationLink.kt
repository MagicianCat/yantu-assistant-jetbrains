package com.taikang.yantu.platform

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Mirrors the existing TkCoding URL flow: discard server query and add user_code. */
object VerificationLink {
    fun create(raw: String, userCode: String): URI {
        val source = URI.create(raw)
        require(source.isAbsolute && source.host != null && source.userInfo == null && source.fragment == null) { "授权链接缺少协议或主机名" }
        require(source.scheme == "https" || (source.scheme == "http" && isLocalOrPrivate(source.host))) { "授权链接需使用 HTTPS 或内网 HTTP" }
        val result = URI(source.scheme, source.authority, source.path,
            "user_code=${URLEncoder.encode(userCode, StandardCharsets.UTF_8)}", null)
        require(result.isAbsolute && result.scheme in setOf("http", "https")) { "授权链接协议无效" }
        return result
    }

    private fun isLocalOrPrivate(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }
}
