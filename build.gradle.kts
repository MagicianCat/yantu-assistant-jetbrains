import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

tasks.register("verifyWebviewAssets") {
    doLast {
        val expected = mapOf(
            "app.js" to "e6c08f734e46180e82d74c81eb8f9cf8957c5a312202fd5a3da79e168947f810",
            "app.css" to "c264ff5785d777ecfa90debdfed178b15129eed86e2781cb1c62fbf7293bc52d",
        )
        for ((name, sha) in expected) {
            val file = layout.projectDirectory.file("src/main/resources/webview/$name").asFile
            check(file.isFile) { "Missing webview asset: $name" }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == sha) { "Webview asset checksum mismatch: $name" }
        }
    }
}
tasks.processResources { dependsOn("verifyWebviewAssets") }

kotlin {
    jvmToolchain(17)
}

dependencies {
    testRuntimeOnly(kotlin("stdlib"))
    testRuntimeOnly("com.google.code.gson:gson:2.11.0")
    intellijPlatform {
        val localPath = providers.gradleProperty("localIdePath").orNull
        val targetVersion = providers.gradleProperty("targetIdeVersion").orElse("2024.1").get()
        when {
            localPath != null -> local(localPath)
            targetVersion.startsWith("2024.") || targetVersion.startsWith("2025.1") || targetVersion.startsWith("2025.2") -> intellijIdeaCommunity(targetVersion)
            else -> intellijIdea(targetVersion)
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("targetSinceBuild").orElse("241").get()
            untilBuild = providers.gradleProperty("targetUntilBuild").orElse("262.*").get()
        }
    }
    pluginVerification {
        providers.gradleProperty("pluginVerifierCliPath").orNull?.let { cliPath.set(file(it)) }
        ides {
            current()
            providers.gradleProperty("verificationIdePaths").orNull?.split('|')?.filter { it.isNotBlank() }
                ?.forEach { local(file(it)) }
        }
    }
}

tasks.register<JavaExec>("verifyScannerFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.StructuredScannerFixture"
}

tasks.register<JavaExec>("verifyCommandUsage") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.CommandUsageFixture"
}

tasks.register<JavaExec>("verifyLogFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.JetBrainsLogScannerFixture"
}

tasks.register<JavaExec>("verifyVerificationLink") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.VerificationLinkFixture"
}

tasks.register<JavaExec>("verifyPrivateRoute") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.PlatformHttpClientFixture"
}

tasks.register<JavaExec>("verifyTelemetryPipeline") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.TelemetryPipelineFixture"
}

tasks.register<JavaExec>("verifyTelemetryRequest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.TelemetryRequestFixture"
}

tasks.register<JavaExec>("verifyUploadWorker") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.UploadWorkerFixture"
}

tasks.register<JavaExec>("verifySkillInstaller") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.skills.SkillInstallerFixture"
}

tasks.register<JavaExec>("verifySseDecoder") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.chat.SseDecoderFixture"
}

tasks.register<JavaExec>("verifySkillUpdates") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.skills.SkillUpdateFixture"
}

tasks.register<JavaExec>("verifyPlatformUrl") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.PlatformUrlFixture"
}

tasks.register<JavaExec>("verifyWiki") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.wiki.WikiFixture"
}

tasks.register<JavaExec>("verifySessions") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.SessionFixture"
}

tasks.register<JavaExec>("verifyWindowsConfig") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.platform.WindowsConfigFixture"
}

tasks.register<JavaExec>("verifyAsdmInstaller") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.skills.AsdmInstallerFixture"
}
