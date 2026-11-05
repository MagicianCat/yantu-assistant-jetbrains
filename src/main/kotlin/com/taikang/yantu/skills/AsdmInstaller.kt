package com.taikang.yantu.skills

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID

/** Installs one platform ASDM archive in the current project. */
class AsdmInstaller {
    fun install(archive: ByteArray, projectRoot: Path, assetId: Long): Int {
        require(assetId > 0 && Files.isDirectory(projectRoot) && !Files.isSymbolicLink(projectRoot)) { "项目目录无效" }
        val entries = SafeZip.read(archive)
        val roots = entries.mapNotNull { entry ->
            val parts = entry.name.trimEnd('/').split('/')
            val index = parts.indexOf(".asdm")
            if (index < 0) null else parts.take(index).joinToString("/")
        }.toSet()
        require(roots.size == 1) { "工具包必须包含唯一的 .asdm 文件夹" }
        val root = roots.single()
        val prefix = (if (root.isEmpty()) "" else "$root/") + ".asdm/"
        val files = entries.filter { !it.directory && it.name.startsWith(prefix) }
        require(files.isNotEmpty()) { "工具包中的 .asdm 文件夹为空" }
        require(files.none { it.name.removePrefix(prefix) == ".yantu-platform-asdm.json" }) { "工具包包含保留文件" }
        val actions = files.mapNotNull { entry ->
            val parts = entry.name.removePrefix(prefix).split('/')
            if (parts.size == 4 && parts[0] == "toolsets" && parts[2] == "actions" &&
                parts[1].matches(Regex("[A-Za-z0-9_-]+")) && parts[3].matches(Regex("[A-Za-z0-9_-]+\\.md")))
                parts[1] to parts[3] else null
        }.sortedWith(compareBy({ it.second }, { it.first }))
        val commands = linkedMapOf<String, String>()
        for ((toolset, filename) in actions) {
            var name = filename
            if (commands.containsKey(name)) name = "$toolset-$filename"
            var index = 2
            while (commands.containsKey(name)) name = "$toolset-${index++}-$filename"
            commands[name] = "@.asdm/toolsets/$toolset/actions/$filename"
        }
        val target = projectRoot.resolve(".asdm")
        val commandRoot = projectRoot.resolve(".codebuddy/commands")
        require(!Files.isSymbolicLink(target) && !Files.isSymbolicLink(projectRoot.resolve(".codebuddy")) &&
            !Files.isSymbolicLink(commandRoot)) { "安装目录不允许符号链接" }
        for ((name, content) in commands) {
            val path = commandRoot.resolve(name)
            require(!Files.isSymbolicLink(path) && (!Files.exists(path) || Files.readString(path) == content)) {
                "已有不同内容的 Command：$name"
            }
        }
        val stage = projectRoot.resolve(".yantu-asdm-stage-${UUID.randomUUID()}")
        val backup = projectRoot.resolve(".yantu-asdm-backup-${UUID.randomUUID()}")
        var backedUp = false
        var installed = false
        val createdCommands = mutableListOf<Path>()
        try {
            Files.createDirectory(stage)
            for (entry in files) {
                val relative = entry.name.removePrefix(prefix)
                val destination = stage.resolve(relative).normalize()
                require(destination.startsWith(stage) && destination != stage) { "ZIP 路径不安全" }
                Files.createDirectories(destination.parent)
                Files.write(destination, entry.bytes, StandardOpenOption.CREATE_NEW)
            }
            Files.writeString(stage.resolve(".yantu-platform-asdm.json"), JsonObject().apply {
                addProperty("assetId", assetId); addProperty("installedAt", Instant.now().toString())
            }.toString() + "\n", StandardOpenOption.CREATE_NEW)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(target) && !Files.isSymbolicLink(target)) { "现有 .asdm 不是普通目录" }
                Files.move(target, backup); backedUp = true
            }
            Files.move(stage, target); installed = true
            if (commands.isNotEmpty()) Files.createDirectories(commandRoot)
            for ((name, content) in commands) {
                val path = commandRoot.resolve(name)
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.writeString(path, content, StandardOpenOption.CREATE_NEW)
                    createdCommands.add(path)
                } else require(!Files.isSymbolicLink(path) && Files.readString(path) == content) { "已有不同内容的 Command：$name" }
            }
            ensureIgnored(projectRoot, ".asdm")
            ensureIgnored(projectRoot, ".codebuddy")
        } catch (failure: Exception) {
            createdCommands.forEach(Files::deleteIfExists)
            if (installed) deleteTree(target)
            if (backedUp) Files.move(backup, target)
            deleteTree(stage)
            throw failure
        }
        if (backedUp) runCatching { deleteTree(backup) }
        return commands.size
    }

    private fun ensureIgnored(root: Path, directory: String) {
        val path = root.resolve(".gitignore")
        require(!Files.isSymbolicLink(path)) { ".gitignore 不允许符号链接" }
        val current = if (Files.exists(path)) Files.readString(path) else ""
        if (current.lineSequence().any { it.trim() in setOf(directory, "$directory/", "/$directory", "/$directory/") }) return
        Files.writeString(path, (if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else "") + "/$directory/\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
    }
}
