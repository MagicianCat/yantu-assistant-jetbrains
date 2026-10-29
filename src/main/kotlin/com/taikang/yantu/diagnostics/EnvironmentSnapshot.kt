package com.taikang.yantu.diagnostics

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import com.taikang.yantu.telemetry.StructuredScanner
import com.taikang.yantu.telemetry.JetBrainsLogScanner
import com.taikang.yantu.telemetry.TelemetryApplicationService
import com.taikang.yantu.platform.YantuPlatformService

data class EnvironmentSnapshot(
    val ideaVersion: String,
    val pluginVersion: String,
    val javaVersion: String,
    val jcefAvailable: Boolean,
    val logsStatus: String,
    val dataStatus: String,
    val dataPartitions: Int,
    val checkedAt: String,
    val scannedSessions: Int,
    val scannedRequests: Int,
    val incompleteFiles: Int,
    val latestRequestId: String,
    val latestState: String,
    val latestUsage: String,
    val latestTools: Int,
    val latestSkills: Int,
    val latestCode: String,
    val platformUrl: String,
    val loginState: String,
    val loginMessage: String,
    val userCode: String,
    val verificationUri: String,
    val logFilesScanned: Int,
    val logRequests: Int,
    val latestLogState: String,
    val tokenMismatch: Boolean,
    val telemetryMode: String,
    val pendingUploads: Int,
    val telemetryError: String,
    val lastScanAt: String,
    val lastUploadAt: String,
)

/** Reads only directory metadata. No account names or absolute paths leave this class. */
object EnvironmentSnapshotReader {
    private const val PLUGIN_ID = "com.taikang.yantu.assistant.jetbrains"

    fun read(jcefAvailable: Boolean): EnvironmentSnapshot {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        val root = localAppData.resolve("CodeBuddyExtension")
        val dataRoot = root.resolve("Data")
        val telemetry = TelemetryApplicationService.get().snapshot()
        val scan = telemetry.structured ?: StructuredScanner(dataRoot).scan()
        val logScan = telemetry.logs ?: JetBrainsLogScanner(root.resolve("Logs").resolve("JetBrains")).scan()
        val platformService = YantuPlatformService.get()
        platformService.restoreOnceInBackground()
        val platform = platformService.snapshot()
        return EnvironmentSnapshot(
            ideaVersion = ApplicationInfo.getInstance().fullVersion,
            pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "未知",
            javaVersion = System.getProperty("java.runtime.version", "未知"),
            jcefAvailable = jcefAvailable,
            logsStatus = status(root.resolve("Logs").resolve("JetBrains")),
            dataStatus = status(dataRoot),
            dataPartitions = countJetBrainsPartitions(dataRoot),
            checkedAt = Instant.now().toString(),
            scannedSessions = scan.sessions,
            scannedRequests = scan.requests,
            incompleteFiles = scan.incomplete,
            latestRequestId = scan.latest?.requestId ?: "未发现",
            latestState = scan.latest?.state ?: "—",
            latestUsage = scan.latest?.let { "输入 ${it.inputTokens ?: "—"} / 输出 ${it.outputTokens ?: "—"} / 总计 ${it.totalTokens ?: "—"}" } ?: "—",
            latestTools = scan.latest?.toolCalls ?: 0,
            latestSkills = scan.latest?.skillCalls ?: 0,
            latestCode = scan.latest?.let { "+${it.addedLines} / -${it.removedLines}，${it.changedFiles} 文件" } ?: "—",
            platformUrl = platform.apiBaseUrl,
            loginState = platform.state,
            loginMessage = platform.message,
            userCode = platform.userCode ?: "",
            verificationUri = platform.verificationUri ?: "",
            logFilesScanned = logScan.files,
            logRequests = logScan.turns.size,
            latestLogState = scan.latest?.requestId?.let { logScan.turns[it]?.state } ?: "—",
            tokenMismatch = scan.latest?.let { latest ->
                logScan.turns[latest.requestId]?.totalTokens?.let { it != latest.totalTokens }
            } ?: false,
            telemetryMode = telemetry.mode,
            pendingUploads = telemetry.pending,
            telemetryError = telemetry.lastError,
            lastScanAt = telemetry.lastScanAt,
            lastUploadAt = telemetry.lastUploadAt,
        )
    }

    private fun status(path: Path): String = when {
        !Files.exists(path) -> "未发现"
        !Files.isDirectory(path) || !Files.isReadable(path) -> "不可读取"
        else -> "可读取"
    }

    private fun countJetBrainsPartitions(dataRoot: Path): Int {
        if (!Files.isDirectory(dataRoot) || !Files.isReadable(dataRoot)) return 0
        return try {
            Files.newDirectoryStream(dataRoot).use { installations ->
                installations.count { installation ->
                    Files.isDirectory(installation.resolve("JetBrains"))
                }
            }
        } catch (_: Exception) {
            0
        }
    }
}
