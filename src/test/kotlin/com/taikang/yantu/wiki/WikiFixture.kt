package com.taikang.yantu.wiki

import java.nio.file.Files

object WikiFixture {
    @JvmStatic fun main(args: Array<String>) {
        check(WikiService.normalizeIds(listOf(1, 1, 2)) == listOf(1L, 2L))
        check(WikiService.normalizeIds((1L..20L).toList()).size == 20)
        check(runCatching { WikiService.normalizeIds((1L..21L).toList()) }.isFailure)
        check(runCatching { WikiService.normalizeIds(listOf(0)) }.isFailure)
        val dir = Files.createTempDirectory("yantu-wiki-fixture")
        try {
            val target = dir.resolve("wiki-documents.zip")
            WikiService.writeDownload(target, byteArrayOf(80, 75, 3, 4, 1, 2), true)
            check(Files.readAllBytes(target).contentEquals(byteArrayOf(80, 75, 3, 4, 1, 2)))
            check(runCatching { WikiService.writeDownload(target, "error".toByteArray(), true) }.isFailure)
            check(Files.readAllBytes(target).size == 6)
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }
}
