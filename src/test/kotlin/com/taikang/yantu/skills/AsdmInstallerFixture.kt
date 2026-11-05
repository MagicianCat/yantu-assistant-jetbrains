package com.taikang.yantu.skills

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AsdmInstallerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val project = Files.createTempDirectory("yantu-asdm-fixture-")
        try {
            val action = "package/.asdm/toolsets/code-dev/actions/prd-code.md"
            fun zip(content: String): ByteArray = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { stream ->
                    stream.putNextEntry(ZipEntry(action)); stream.write(content.toByteArray()); stream.closeEntry()
                }
            }.toByteArray()
            val installer = AsdmInstaller()
            check(installer.install(zip("first"), project, 42) == 1)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "first")
            check(Files.readString(project.resolve(".codebuddy/commands/prd-code.md")) ==
                "@.asdm/toolsets/code-dev/actions/prd-code.md")
            check(Files.readString(project.resolve(".gitignore")).contains("/.asdm/"))
            check(Files.readString(project.resolve(".gitignore")).contains("/.codebuddy/"))
            check(installer.install(zip("second"), project, 43) == 1)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "second")
            Files.writeString(project.resolve(".codebuddy/commands/prd-code.md"), "custom")
            check(runCatching { installer.install(zip("third"), project, 44) }.isFailure)
            check(Files.readString(project.resolve(".asdm/toolsets/code-dev/actions/prd-code.md")) == "second")
        } finally {
            Files.walk(project).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
