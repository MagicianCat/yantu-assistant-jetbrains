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
            check(installer.install(zip(
                "代码开发工具集/actions/code-review.md" to "代码开发",
                "代码开发工具集/actions/generate-coding-extensions.md" to "扩展",
                "代码开发工具集/rules/code-first-default-development-rules.mdc" to "规则",
                "代码评审工具集/actions/code-review.md" to "代码评审",
                "代码评审工具集/actions/set-review-standards.md" to "标准",
                "代码评审工具集/spec/report-template.md" to "模板"
            ), project, 44) == 4)
            check(Files.readString(project.resolve(".codebuddy/commands/code-review.md")) ==
                "@.asdm/toolsets/代码开发工具集/actions/code-review.md")
            check(Files.readString(project.resolve(".codebuddy/commands/代码评审工具集-code-review.md")) ==
                "@.asdm/toolsets/代码评审工具集/actions/code-review.md")
            check(!Files.exists(project.resolve(".codebuddy/commands/report-template.md")))
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
