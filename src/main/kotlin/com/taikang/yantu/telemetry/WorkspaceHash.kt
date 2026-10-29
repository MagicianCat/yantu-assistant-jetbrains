package com.taikang.yantu.telemetry

import java.security.MessageDigest

/** TkCoding history directories use MD5 of the Windows workspace path with backslashes. */
object WorkspaceHash {
    fun matches(path: String, hash: String): Boolean {
        if (!hash.matches(Regex("[a-fA-F0-9]{32}"))) return false
        val normalized = path.trimEnd('/', '\\').replace('/', '\\')
        val candidates = listOf(normalized, normalized.replaceFirstChar { it.lowercase() }, normalized.lowercase()).distinct()
        return candidates.any { candidate ->
            MessageDigest.getInstance("MD5").digest(candidate.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.equals(hash, true)
        }
    }
}
