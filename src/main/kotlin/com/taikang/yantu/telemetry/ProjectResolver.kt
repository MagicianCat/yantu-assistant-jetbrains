package com.taikang.yantu.telemetry

import java.nio.file.Files
import java.nio.file.Path

/** Resolves project identity without starting git or exposing a remote URL. */
class ProjectResolver(private val installationId: String) {
    data class Identity(val key: String, val name: String, val source: String)

    fun resolve(workspace: String?): Identity? {
        if (workspace.isNullOrBlank()) return null
        val path = WorkspacePath.parse(workspace) ?: return null
        if (!Files.isDirectory(path)) return null
        val remote = originRemote(path)
        if (remote != null) {
            val normalized = remote.trim().replace(Regex("^[^@]+@([^:]+):"), "$1/")
                .replace(Regex("^[A-Za-z]+://"), "").replace(Regex("^[^/@]+@"), "")
                .removeSuffix(".git").trimEnd('/').lowercase()
            val name = normalized.substringAfterLast('/').take(256)
            if (normalized.isNotBlank() && name.isNotBlank())
                return Identity(DurableOutbox.sha256(normalized), name, "GIT_REMOTE")
        }
        return Identity(DurableOutbox.sha256("$installationId:${path.toString().trimEnd('/', '\\')}"),
            path.fileName?.toString()?.take(256) ?: "unknown", "LOCAL")
    }

    private fun originRemote(path: Path): String? {
        var dir: Path? = path
        while (dir != null) {
            val config = dir.resolve(".git").resolve("config")
            if (Files.isRegularFile(config) && Files.size(config) in 1..1_000_000) {
                var origin = false
                for (line in Files.readAllLines(config)) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[")) origin = trimmed.equals("[remote \"origin\"]", true)
                    else if (origin && trimmed.startsWith("url", true) && trimmed.contains('='))
                        return trimmed.substringAfter('=').trim().takeIf { it.isNotBlank() }
                }
            }
            dir = dir.parent
        }
        return null
    }
}
