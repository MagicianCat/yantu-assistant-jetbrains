package com.taikang.yantu.skills

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class SkillInstaller {
    data class Item(val key: String, val version: String?, val versionId: Long?, val artifactName: String, val sha256: String)
    data class Descriptor(val sha256: String, val items: List<Item>)
    private data class Operation(val target: Path, val stage: Path, var backup: Path? = null, var committed: Boolean = false)

    fun parseDescriptor(row: JsonObject): Descriptor {
        val hash = row.get("sha256")?.asString ?: error("Bundle 缺少 SHA-256")
        require(hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Bundle SHA-256 格式无效" }
        val items = row.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Bundle 缺少条目")
        return Descriptor(hash, items.map { value ->
            val item = value.asJsonObject
            val key = item.get("skillKey")?.asString ?: error("Skill key 缺失")
            require(SkillScanner.validKey(key)) { "Skill key 无效" }
            val name = item.get("artifactFileName")?.asString ?: error("Artifact 路径缺失")
            val sha = item.get("artifactSha256")?.asString ?: error("Artifact SHA-256 缺失")
            require(sha.matches(Regex("[a-fA-F0-9]{64}"))) { "Artifact SHA-256 格式无效" }
            Item(key, item.get("version")?.let { runCatching { it.asString }.getOrNull() },
                item.get("versionId")?.let { runCatching { it.asLong }.getOrNull() }, name, sha)
        })
    }

    fun install(bundle: ByteArray, descriptor: Descriptor, skillsDirectory: Path): List<String> {
        require(sha256(bundle).equals(descriptor.sha256, true)) { "Bundle SHA-256 校验失败" }
        require(descriptor.items.isNotEmpty() && descriptor.items.size <= 50) { "Bundle 条目数量无效" }
        require(descriptor.items.map { it.key }.distinct().size == descriptor.items.size) { "Bundle Skill 重复" }
        val outer = SafeZip.read(bundle).associateBy { it.name }
        require(Files.isDirectory(skillsDirectory) || !Files.exists(skillsDirectory)) { "Skill 目标不是目录" }
        require(!Files.isSymbolicLink(skillsDirectory) && !Files.isSymbolicLink(skillsDirectory.parent)) {
            "Skill 目录路径不允许符号链接"
        }
        Files.createDirectories(skillsDirectory)
        require(!Files.isSymbolicLink(skillsDirectory)) { "Skill 目录不允许符号链接" }
        val operations = mutableListOf<Operation>()
        try {
            for (item in descriptor.items) {
                val artifactPath = if (item.artifactName.startsWith("${item.key}/")) item.artifactName else "${item.key}/${item.artifactName}"
                val artifact = outer[artifactPath]?.takeIf { !it.directory } ?: error("Bundle 缺少 ${item.key} Artifact")
                require(sha256(artifact.bytes).equals(item.sha256, true)) { "${item.key} SHA-256 校验失败" }
                val rawFiles = SafeZip.read(artifact.bytes)
                val first = rawFiles.firstOrNull()?.name?.substringBefore('/')
                val strip = if (first != null && rawFiles.all { it.name == "$first/" || it.name.startsWith("$first/") }) "$first/" else ""
                val files = rawFiles.mapNotNull { entry ->
                    val name = entry.name.removePrefix(strip)
                    if (name.isBlank()) null else entry.copy(name = name)
                }
                require(files.any { it.name == "SKILL.md" && !it.directory }) { "${item.key} 缺少 SKILL.md" }
                val stage = skillsDirectory.resolve(".yantu-stage-${UUID.randomUUID()}")
                Files.createDirectory(stage)
                operations.add(Operation(skillsDirectory.resolve(item.key), stage))
                for (file in files) {
                    if (file.name == ".yantu-platform-skill.json") continue
                    val target = stage.resolve(file.name).normalize()
                    require(target.startsWith(stage) && target != stage) { "解压目标越界" }
                    if (file.directory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.write(target, file.bytes)
                    }
                }
                Files.writeString(stage.resolve(".yantu-platform-skill.json"), JsonObject().apply {
                    addProperty("schemaVersion", 1); addProperty("source", "yantu-platform"); addProperty("skillKey", item.key)
                    item.versionId?.let { addProperty("skillVersionId", it) }
                    item.version?.let { addProperty("version", it) }
                    addProperty("artifactSha256", item.sha256); addProperty("installationId", UUID.randomUUID().toString())
                    addProperty("installedAt", Instant.now().toString())
                }.toString() + "\n")
            }
            for (operation in operations) {
                if (Files.exists(operation.target)) {
                    require(!Files.isSymbolicLink(operation.target)) { "目标 Skill 不允许符号链接" }
                    operation.backup = skillsDirectory.resolve(".yantu-backup-${UUID.randomUUID()}")
                    Files.move(operation.target, operation.backup)
                }
                Files.move(operation.stage, operation.target)
                operation.committed = true
            }
        } catch (failure: Exception) {
            for (operation in operations.asReversed()) {
                if (operation.committed) deleteTree(operation.target)
                operation.backup?.takeIf(Files::exists)?.let { Files.move(it, operation.target) }
                deleteTree(operation.stage)
            }
            throw failure
        }
        operations.forEach { it.backup?.let(::deleteTree) }
        return descriptor.items.map { it.key }
    }
    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
