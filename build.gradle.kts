import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

tasks.register("verifyWebviewAssets") {
    doLast {
        val expected = mapOf(
            "app.js" to "37c346002ff0fc4b41153ef0db7c37616873eaca76c0dc3e6fb117ef5b6496ab",
            "app.css" to "65da3266ee2c3fa10b1cf6da7e2ec3fdebb2db8a22269330f016f78aa06c6b15",
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
        providers.gradleProperty("localIdePath").orNull?.let { local(it) } ?: intellijIdea("2026.1.5")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("targetSinceBuild").orElse("261").get()
            untilBuild = providers.gradleProperty("targetUntilBuild").orElse("261.*").get()
        }
    }
}

tasks.register<JavaExec>("verifyScannerFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.taikang.yantu.telemetry.StructuredScannerFixture"
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
