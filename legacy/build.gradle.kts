plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.taikang.yantu"
version = "0.6.7-legacy-2021-2023"

repositories { mavenCentral() }

intellij {
    version.set(providers.gradleProperty("targetIdeVersion").orElse("2021.1"))
    type.set("IC")
    downloadSources.set(false)
    instrumentCode.set(false)
}

tasks.patchPluginXml {
    sinceBuild.set("211")
    untilBuild.set("233.*")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.ExperimentalStdlibApi"
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

sourceSets {
    main {
        kotlin.srcDir("../src/main/kotlin")
        resources.srcDir("../src/main/resources")
    }
    test {
        kotlin.srcDir("../src/test/kotlin")
    }
}

mapOf(
    "verifyLegacyCommandUsage" to "com.taikang.yantu.telemetry.CommandUsageFixture",
    "verifyLegacyScanner" to "com.taikang.yantu.telemetry.StructuredScannerFixture",
    "verifyLegacyUpload" to "com.taikang.yantu.telemetry.UploadWorkerFixture",
    "verifyLegacyRequest" to "com.taikang.yantu.platform.TelemetryRequestFixture",
    "verifyLegacyAsdmInstaller" to "com.taikang.yantu.skills.AsdmInstallerFixture",
    "verifyLegacyPlatformUrl" to "com.taikang.yantu.platform.PlatformUrlFixture",
).forEach { (taskName, fixture) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(fixture)
    }
}
