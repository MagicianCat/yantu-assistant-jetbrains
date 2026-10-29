package com.taikang.yantu.skills

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.taikang.yantu.platform.PlatformApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class SkillUpdateService(private val api: PlatformApi? = null, private val scanner: SkillScanner = SkillScanner(),
                         private val installer: SkillInstaller = SkillInstaller()) {
    private val platformApi: PlatformApi get() = api ?: PlatformApi()
    data class Candidate(val key: String, val name: String?, val localVersion: String, val latestVersion: String,
                         val latestVersionId: Long, val targets: List<SkillScanner.LocalSkill>)
    data class InstallResult(val installed: List<String>, val skipped: List<String>, val failed: List<String>)
    fun check(projectRoot: Path?, osType: String): List<Candidate> {
        val local = scanner.scan(projectRoot).filter { it.platform && it.version != null }
        if (local.isEmpty()) return emptyList()
        return plan(local, platformApi.checkUpdates(osType, local.map { it.key }.distinct()))
    }
    fun plan(local: List<SkillScanner.LocalSkill>, remoteItems: JsonArray): List<Candidate> {
        val remote = remoteItems.associateBy { it.asJsonObject.get("skillKey")?.asString }
        return local.groupBy { it.key }.mapNotNull { (key, installed) ->
            val latest = remote[key]?.asJsonObject ?: return@mapNotNull null
            val latestVersion = latest.get("latestVersion")?.asString ?: return@mapNotNull null
            val id = latest.get("latestVersionId")?.asLong ?: return@mapNotNull null
            if (installed.all { it.versionId == id || SkillScanner.compareVersions(it.version.orEmpty(), latestVersion) >= 0 }) return@mapNotNull null
            Candidate(key, latest.get("displayName")?.asString,
                installed.minWithOrNull { a, b -> SkillScanner.compareVersions(a.version.orEmpty(), b.version.orEmpty()) }?.version.orEmpty(),
                latestVersion, id, installed)
        }
    }
    fun public(candidates: List<Candidate>): JsonArray = JsonArray().also { array -> candidates.forEach { c ->
        array.add(JsonObject().apply {
            addProperty("skillKey", c.key); c.name?.let { addProperty("name", it) }
            addProperty("localVersion", c.localVersion); addProperty("latestVersion", c.latestVersion)
            addProperty("latestVersionId", c.latestVersionId)
            add("scopes", JsonArray().also { scopes -> c.targets.map { it.scope }.distinct().forEach(scopes::add) })
        })
    } }
    fun install(project: Project, candidates: List<Candidate>, selectedKeys: List<String>, osType: String): InstallResult {
        val selected = candidates.filter { it.key in selectedKeys }
        if (selected.isEmpty()) return InstallResult(emptyList(), emptyList(), emptyList())
        val grouped = linkedMapOf<Path, MutableList<Candidate>>()
        for (candidate in selected) for (target in candidate.targets)
            grouped.getOrPut(target.directory.parent) { mutableListOf() }.add(candidate)
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val cache = mutableMapOf<List<Long>, Pair<ByteArray, SkillInstaller.Descriptor>>()
        for ((directory, items) in grouped) {
            for (chunk in items.distinctBy { it.key }.chunked(50)) {
                try {
                val ids = chunk.map { it.latestVersionId }.distinct().sorted()
                val bundle = cache.getOrPut(ids) {
                    val metadata = platformApi.createUpdateBundle(osType, ids)
                    val status = metadata.get("resultStatus")?.asString ?: metadata.get("status")?.asString
                    require(status in setOf("AVAILABLE", "COMPLETE", "PARTIAL")) { "更新 Bundle 状态：${status ?: "未知"}" }
                    val descriptor = installer.parseDescriptor(metadata)
                    val id = metadata.get("id")?.asString ?: metadata.get("bundleId")?.asString ?: error("Bundle 下载 ID 缺失")
                    platformApi.downloadBundle(id) to descriptor
                }
                val platformPaths = scanner.scan(project.basePath?.let(Path::of)).filter { it.platform }.map { it.directory }.toSet()
                val collisions = bundle.second.items.map { it.key }.filter { key ->
                    val target = directory.resolve(key)
                    Files.exists(target) && target !in platformPaths
                }
                if (collisions.isNotEmpty() && !confirm(project, "目标目录含非平台 Skill：${collisions.joinToString("、")}。覆盖？")) {
                    skipped += chunk.map { "${it.key}@${scope(directory, project)}" }
                    continue
                }
                installed += installer.install(bundle.first, bundle.second, directory).map { "$it@${scope(directory, project)}" }
                } catch (failure: Exception) {
                    failed += chunk.map { "${it.key}@${scope(directory, project)}: ${failure.message ?: "失败"}" }
                }
            }
        }
        return InstallResult(installed.distinct(), skipped.distinct(), failed.distinct())
    }
    private fun scope(directory: Path, project: Project): String =
        if (project.basePath?.let(Path::of)?.resolve(".codebuddy/skills") == directory) "项目" else "全局"
    private fun confirm(project: Project, message: String): Boolean {
        val selected = AtomicInteger(-1)
        SwingUtilities.invokeAndWait { selected.set(Messages.showYesNoDialog(project, message, "更新覆盖确认", Messages.getWarningIcon())) }
        return selected.get() == Messages.YES
    }
}
