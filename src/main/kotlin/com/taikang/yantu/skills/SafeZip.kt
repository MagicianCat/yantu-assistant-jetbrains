package com.taikang.yantu.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object SafeZip {
    data class Entry(val name: String, val bytes: ByteArray, val directory: Boolean)
    private const val MAX_ENTRIES = 2000
    private const val MAX_FILE = 50 * 1024 * 1024
    private const val MAX_TOTAL = 250 * 1024 * 1024

    fun read(bytes: ByteArray): List<Entry> {
        require(bytes.size <= MAX_TOTAL + 65557) { "ZIP 超过大小限制" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557))
            .firstOrNull { it + 22 <= bytes.size && buffer.intAt(it) == 0x06054b50 } ?: error("ZIP 结束记录缺失")
        val count = buffer.shortAt(eocd + 10)
        val centralSize = buffer.intAt(eocd + 12).toLong() and 0xffffffffL
        val centralOffset = buffer.intAt(eocd + 16).toLong() and 0xffffffffL
        require(count < 0xffff && centralSize < 0xffffffffL && centralOffset < 0xffffffffL) { "不支持 ZIP64" }
        require(count <= MAX_ENTRIES && centralOffset + centralSize <= eocd) { "ZIP 中央目录越界" }
        var offset = centralOffset.toInt(); var total = 0L
        val entries = mutableListOf<Entry>(); val names = mutableSetOf<String>()
        repeat(count) {
            require(offset + 46 <= eocd && buffer.intAt(offset) == 0x02014b50) { "ZIP 中央目录损坏" }
            val flags = buffer.shortAt(offset + 8); val method = buffer.shortAt(offset + 10)
            val crc = buffer.intAt(offset + 16).toLong() and 0xffffffffL
            val compressed = buffer.intAt(offset + 20).toLong() and 0xffffffffL
            val uncompressed = buffer.intAt(offset + 24).toLong() and 0xffffffffL
            val nameLength = buffer.shortAt(offset + 28); val extraLength = buffer.shortAt(offset + 30)
            val commentLength = buffer.shortAt(offset + 32)
            val external = buffer.intAt(offset + 38)
            val localOffset = buffer.intAt(offset + 42).toLong() and 0xffffffffL
            val next = offset.toLong() + 46 + nameLength + extraLength + commentLength
            require(next <= eocd && compressed < 0xffffffffL && uncompressed < 0xffffffffL && localOffset < 0xffffffffL) { "ZIP64 或目录越界" }
            require(flags and 1 == 0 && method in setOf(0, 8)) { "加密或压缩方式不支持" }
            var extraAt = offset + 46 + nameLength
            val extraEnd = extraAt + extraLength
            while (extraAt + 4 <= extraEnd) {
                val kind = buffer.shortAt(extraAt); val length = buffer.shortAt(extraAt + 2)
                require(extraAt + 4 + length <= extraEnd && kind != 0x0001) { "ZIP64 或扩展字段损坏" }
                extraAt += 4 + length
            }
            require(extraAt == extraEnd) { "ZIP 扩展字段损坏" }
            require((external ushr 16) and 0xf000 != 0xa000) { "ZIP 不允许符号链接" }
            require(uncompressed <= MAX_FILE && total + uncompressed <= MAX_TOTAL) { "ZIP 解压大小超过限制" }
            val name = decode(bytes, offset + 46, nameLength).replace('\\', '/')
            validateName(name)
            require(names.add(name.lowercase())) { "ZIP 包含重复路径" }
            require(localOffset + 30 <= centralOffset && buffer.intAt(localOffset.toInt()) == 0x04034b50) { "ZIP 本地头损坏" }
            require(buffer.shortAt(localOffset.toInt() + 6) == flags) { "ZIP 本地头标志不一致" }
            require(buffer.shortAt(localOffset.toInt() + 8) == method) { "ZIP 压缩方式不一致" }
            if (flags and 0x08 == 0) {
                require((buffer.intAt(localOffset.toInt() + 14).toLong() and 0xffffffffL) == crc &&
                    (buffer.intAt(localOffset.toInt() + 18).toLong() and 0xffffffffL) == compressed &&
                    (buffer.intAt(localOffset.toInt() + 22).toLong() and 0xffffffffL) == uncompressed) { "ZIP 本地头大小不一致" }
            }
            val localNameLength = buffer.shortAt(localOffset.toInt() + 26)
            val localExtraLength = buffer.shortAt(localOffset.toInt() + 28)
            val localName = decode(bytes, localOffset.toInt() + 30, localNameLength).replace('\\', '/')
            require(localName == name) { "ZIP 本地头路径不一致" }
            val dataStart = localOffset + 30 + localNameLength + localExtraLength
            require(dataStart + compressed <= centralOffset) { "ZIP 数据越界" }
            val directory = name.endsWith('/')
            val content = if (directory) ByteArray(0) else if (method == 0) bytes.copyOfRange(dataStart.toInt(), (dataStart + compressed).toInt())
                else inflate(bytes, dataStart.toInt(), compressed.toInt(), uncompressed.toInt())
            require(content.size.toLong() == uncompressed) { "ZIP 文件大小不一致" }
            val actualCrc = CRC32().apply { update(content) }.value
            require(actualCrc == crc) { "ZIP CRC 校验失败" }
            entries.add(Entry(name, content, directory)); total += content.size
            offset = next.toInt()
        }
        require(offset.toLong() == centralOffset + centralSize) { "ZIP 中央目录长度不一致" }
        return entries
    }
    private fun inflate(bytes: ByteArray, start: Int, length: Int, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        InflaterInputStream(ByteArrayInputStream(bytes, start, length), Inflater(true)).use { stream ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                require(out.size().toLong() + n <= limit) { "ZIP 解压大小不符" }
                out.write(chunk, 0, n)
            }
        }
        return out.toByteArray()
    }
    private fun decode(bytes: ByteArray, start: Int, count: Int): String {
        require(start >= 0 && start.toLong() + count <= bytes.size) { "ZIP 路径越界" }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, count)).toString()
    }
    private fun validateName(name: String) {
        require(name.isNotBlank() && !name.contains('\u0000') && !name.startsWith('/') &&
            !Regex("^[A-Za-z]:/").containsMatchIn(name) && name.split('/').none { it == ".." || it.isBlank() && !name.endsWith('/') }) { "ZIP 路径不安全" }
    }
    private fun ByteBuffer.intAt(index: Int): Int = getInt(index)
    private fun ByteBuffer.shortAt(index: Int): Int = getShort(index).toInt() and 0xffff
}
