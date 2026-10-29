package com.taikang.yantu.skills

import com.google.gson.JsonArray
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.taikang.yantu.platform.PlatformApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class SkillService(private val api: PlatformApi = PlatformApi(), private val installer: SkillInstaller = SkillInstaller(),
                   private val scanner: SkillScanner = SkillScanner()) {
    data class Result(val installed: List<String>, val failures: List<String>)
    fun install(project: Project, runKey: String, keys: List<String>, osType: String): Result {
        require(keys.isNotEmpty() && keys.size <= 40 && keys.all(SkillScanner::validKey)) { "Skill 数量或 key 无效" }
        val root = chooseRoot(project) ?: return Result(emptyList(), emptyList())
        val directory = root.resolve(".codebuddy").resolve("skills")
        val existing = keys.filter { Files.exists(directory.resolve(it)) }
        if (existing.isNotEmpty() && !confirm(project, "以下 Skill 已存在，将被覆盖：${existing.joinToString("、")}。继续？", "覆盖确认")) return Result(emptyList(), emptyList())
        if (!confirm(project, "安装 ${keys.joinToString("、")} 到 ${directory}？依赖项也会一并安装。", "安装确认")) return Result(emptyList(), emptyList())
        val metadata = api.createBundle(runKey, osType, keys)
        val status = metadata.get("resultStatus")?.asString ?: metadata.get("status")?.asString
        require(status in setOf("AVAILABLE", "COMPLETE", "PARTIAL")) { "Bundle 状态：${status ?: "未知"}" }
        val failures = metadata.get("failures")?.takeIf { it.isJsonArray }?.asJsonArray?.map { failure ->
            val row = failure.takeIf { it.isJsonObject }?.asJsonObject
            listOfNotNull(row?.get("skillKey")?.asString, row?.get("message")?.asString ?: row?.get("reason")?.asString)
                .joinToString(": ").take(160)
        } ?: emptyList()
        val descriptor = installer.parseDescriptor(metadata)
        val dependencyCollisions = descriptor.items.map { it.key }.filter { it !in keys && Files.exists(directory.resolve(it)) }
        if (dependencyCollisions.isNotEmpty() && !confirm(project,
                "依赖 Skill 已存在，也将被覆盖：${dependencyCollisions.joinToString("、")}。继续？", "依赖覆盖确认"))
            return Result(emptyList(), failures)
        val id = metadata.get("id")?.asString ?: metadata.get("bundleId")?.asString ?: error("Bundle 下载 ID 缺失")
        val bytes = api.downloadBundle(id)
        return Result(installer.install(bytes, descriptor, directory), failures)
    }
    fun scan(project: Project): List<SkillScanner.LocalSkill> = scanner.scan(project.basePath?.let(Path::of))
    private fun chooseRoot(project: Project): Path? {
        val projectRoot = project.basePath?.let(Path::of)?.takeIf { Files.isDirectory(it) }
        if (projectRoot == null) return Path.of(System.getProperty("user.home"))
        val result = AtomicInteger(-1)
        SwingUtilities.invokeAndWait {
            result.set(Messages.showDialog(project, "选择 Skill 安装位置", "安装范围",
                arrayOf("项目", "全局", "取消"), 0, Messages.getQuestionIcon()))
        }
        return when (result.get()) { 0 -> projectRoot; 1 -> Path.of(System.getProperty("user.home")); else -> null }
    }
    private fun confirm(project: Project, text: String, title: String): Boolean {
        val result = AtomicInteger(-1)
        SwingUtilities.invokeAndWait { result.set(Messages.showYesNoDialog(project, text, title, Messages.getQuestionIcon())) }
        return result.get() == Messages.YES
    }
}
