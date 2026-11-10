package com.taikang.yantu.skills

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AsdmInstallerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val project = Files.createTempDirectory("yantu-asdm-fixture-")
        try {
            fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { stream ->
                    for ((name, content) in entries) {
                        stream.putNextEntry(ZipEntry("package/.asdm/toolsets/$name"))
                        stream.write(content.toByteArray()); stream.closeEntry()
                    }
                }
            }.toByteArray()
            val installer = AsdmInstaller()
            check(installer.install(zip("code-dev/actions/prd-code.md" to "first", "code-dev/actions/stale.md" to "old"), project, 42) == 2)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "first")
            check(Files.readString(project.resolve(".codebuddy/commands/prd-code.md")) ==
                "@.asdm/toolsets/code-dev/actions/prd-code.md")
            check(Files.readString(project.resolve(".gitignore")).contains("/.asdm/"))
            check(Files.readString(project.resolve(".gitignore")).contains("/.codebuddy/"))
            Files.writeString(project.resolve(".codebuddy/commands/custom.md"), "custom")
            check(installer.install(zip("code-dev/actions/prd-code.md" to "second"), project, 43) == 1)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "second")
            check(!Files.exists(project.resolve(".codebuddy/commands/stale.md")))
            check(!Files.exists(project.resolve(".codebuddy/commands/custom.md")))
            check(!Files.exists(project.resolve(".asdm/toolsets/code-dev/actions/stale.md")))
            check(Files.readString(project.resolve(".codebuddy/commands/prd-code.md")) ==
                "@.asdm/toolsets/code-dev/actions/prd-code.md")
            check(runCatching { installer.install(zip("code-dev/actions/prd-code.md" to "third"), project, 0) }.isFailure)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "second")
        } finally {
            Files.walk(project).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
