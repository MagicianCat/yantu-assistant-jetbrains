plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

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
