package com.taikang.yantu.skills

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

class SkillScanner(private val home: Path = Path.of(System.getProperty("user.home"))) {
    data class LocalSkill(val key: String, val version: String?, val directory: Path, val scope: String,
                          val platform: Boolean, val versionId: Long?)
    fun scan(projectRoot: Path?): List<LocalSkill> {
        val roots = listOfNotNull(projectRoot?.let { it to "project" }, home to "global").distinctBy { it.first.toAbsolutePath().normalize() }
        val found = mutableListOf<LocalSkill>()
        for ((base, scope) in roots) {
            val skills = base.resolve(".codebuddy").resolve("skills")
            if (!Files.isDirectory(skills)) continue
            Files.newDirectoryStream(skills).use { entries ->
                for (directory in entries.take(1000)) {
                    if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory) || !Files.isRegularFile(directory.resolve("SKILL.md"))) continue
                    val key = directory.fileName.toString()
                    if (!validKey(key)) continue
                    val marker = readMarker(directory.resolve(".yantu-platform-skill.json"))
                    val validMarker = marker?.get("source")?.asString == "yantu-platform" && marker.get("skillKey")?.asString == key
                    val yaml = runCatching { Files.readString(directory.resolve("skill.yaml")).take(65_536) }.getOrNull()
                    val version = yaml?.lineSequence()?.firstNotNullOfOrNull { line ->
                        Regex("^\\s*version\\s*:\\s*['\"]?([^\\n#'\"]+)").find(line)?.groupValues?.get(1)?.trim()
                    } ?: marker?.get("version")?.let { runCatching { it.asString }.getOrNull() }
                    found.add(LocalSkill(key, version, directory, scope, validMarker,
                        if (validMarker) marker?.get("skillVersionId")?.let { runCatching { it.asLong }.getOrNull() } else null))
                }
            }
        }
        return found
    }
    fun withStatus(recommendations: JsonArray, local: List<LocalSkill>): JsonArray = JsonArray().also { output ->
        for (value in recommendations) {
            val row = value.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: continue
            val key = row.get("skillKey")?.asString ?: row.get("key")?.asString ?: continue
            row.addProperty("skillKey", key)
            for (field in listOf("runKey", "name", "description", "version", "reason", "detailPath", "localVersion"))
                if (row.get(field)?.isJsonNull == true) row.remove(field)
            val selected = local.firstOrNull { it.key == key }
            val remoteVersion = row.get("version")?.let { runCatching { it.asString }.getOrNull() }
            val status = when {
                selected == null -> "missing"
                remoteVersion != null && selected.version != null && compareVersions(selected.version, remoteVersion) < 0 -> "update"
                else -> selected.scope
            }
            row.addProperty("status", status)
            selected?.version?.let { row.addProperty("localVersion", it) }
            output.add(row)
        }
    }
    private fun readMarker(path: Path): JsonObject? = runCatching {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) !in 1..65_536) return@runCatching null
        JsonParser.parseString(Files.readString(path)).asJsonObject
    }.getOrNull()
    companion object {
        fun validKey(key: String) = key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
        fun compareVersions(left: String, right: String): Int {
            val a = left.removePrefix("v").split(Regex("[.-]")); val b = right.removePrefix("v").split(Regex("[.-]"))
            for (i in 0 until maxOf(a.size, b.size)) {
                val av = a.getOrElse(i) { "" }; val bv = b.getOrElse(i) { "" }
                val an = av.toLongOrNull(); val bn = bv.toLongOrNull()
                if (an != null && bn != null && an != bn) return an.compareTo(bn)
                val compared = av.compareTo(bv)
                if (compared != 0) return compared
            }
            return 0
        }
    }
}
