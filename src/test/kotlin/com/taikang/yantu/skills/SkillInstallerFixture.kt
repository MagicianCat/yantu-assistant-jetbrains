package com.taikang.yantu.skills

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SkillInstallerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("yantu-skill-test")
        try {
            val skills = root.resolve(".codebuddy/skills")
            Files.createDirectories(skills.resolve("review"))
            Files.writeString(skills.resolve("review/SKILL.md"), "old")
            val artifact = zip("review/SKILL.md" to "new".toByteArray(), "review/skill.yaml" to "version: 1.2.3".toByteArray())
            val bundle = zip("review/artifact.zip" to artifact)
            val descriptor = SkillInstaller.Descriptor(hash(bundle), listOf(SkillInstaller.Item("review", "1.2.3", 42,
                "artifact.zip", hash(artifact))))
            check(runCatching { SkillInstaller().install(bundle, descriptor.copy(sha256 = "0".repeat(64)), skills) }.isFailure)
            check(Files.readString(skills.resolve("review/SKILL.md")) == "old")
            check(SkillInstaller().install(bundle, descriptor, skills) == listOf("review"))
            check(Files.readString(skills.resolve("review/SKILL.md")) == "new")
            val local = SkillScanner(root).scan(null)
            check(local.single().platform && local.single().version == "1.2.3" && local.single().versionId == 42L)
            check(runCatching { SafeZip.read(zip("../escape" to byteArrayOf(1))) }.isFailure)
            check(runCatching { SafeZip.read(zip("a" to byteArrayOf(1)).also { it[32] = (it[32].toInt() xor 1).toByte() }) }.isFailure)
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
