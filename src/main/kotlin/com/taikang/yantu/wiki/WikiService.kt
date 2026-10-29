package com.taikang.yantu.wiki

import com.taikang.yantu.platform.PlatformApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

class WikiService(private val api: PlatformApi? = null) {
    private val platformApi: PlatformApi get() = api ?: PlatformApi()
    fun title(id: Long): String {
        require(id > 0)
        val row = platformApi.wikiMetadata(id)
        require(row.get("id")?.asLong == id)
        return row.get("title")?.asString?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: error("文档名称响应无效")
    }
    fun download(rawIds: List<Long>): Path? {
        val ids = normalizeIds(rawIds)
        val name = if (ids.size == 1) "wiki-${ids.single()}.md" else "wiki-documents.zip"
        val selected = AtomicReference<Path?>()
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser(Path.of(System.getProperty("user.home")).toFile())
            chooser.selectedFile = Path.of(System.getProperty("user.home"), name).toFile()
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION)
                selected.set(chooser.selectedFile.toPath())
        }
        val target = selected.get() ?: return null
        val bytes = platformApi.downloadWiki(ids)
        writeDownload(target, bytes, ids.size > 1)
        return target
    }
    companion object {
        fun writeDownload(target: Path, bytes: ByteArray, multiple: Boolean) {
        require(bytes.isNotEmpty()) { "Wiki 下载返回空文件" }
        if (multiple) require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) { "Wiki 批量下载响应不是 ZIP 文件" }
        val temp = target.resolveSibling(target.fileName.toString() + ".${UUID.randomUUID()}.tmp")
        try {
            Files.write(temp, bytes)
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        require(Files.size(target) == bytes.size.toLong()) { "Wiki 文件写入不完整" }
        }
        fun normalizeIds(raw: List<Long>): List<Long> = raw.distinct().also { ids ->
            require(ids.size in 1..20 && ids.all { it > 0 }) { "文档 ID 数量或格式无效" }
        }
    }
}
