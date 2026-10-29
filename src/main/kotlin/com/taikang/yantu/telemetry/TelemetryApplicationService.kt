package com.taikang.yantu.telemetry

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.taikang.yantu.platform.YantuPlatformService
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class TelemetryApplicationService : Disposable {
    data class Snapshot(val mode: String, val pending: Int, val lastError: String,
                        val lastScanAt: String, val lastUploadAt: String, val collected: Int,
                        val structured: StructuredScanner.Result?, val logs: JetBrainsLogScanner.Result?)
    private val started = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "yantu-jetbrains-telemetry").apply { isDaemon = true }
    }
    private val root = (System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), "AppData", "Local"))
    private val source = root.resolve("CodeBuddyExtension")
    private val stateRoot = root.resolve("YantuAssistantJetBrains")
    private val structuredScanner = StructuredScanner(source.resolve("Data"))
    private val logScanner = JetBrainsLogScanner(source.resolve("Logs").resolve("JetBrains"))
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var outbox: DurableOutbox? = null
    private val diagnosticsSeen = mutableSetOf<String>()
    @Volatile private var mode = "未启动"
    @Volatile private var lastError = ""
    @Volatile private var lastScanAt = ""
    @Volatile private var lastUploadAt = ""
    @Volatile private var collected = 0
    @Volatile private var pending = 0
    @Volatile private var lastStructured: StructuredScanner.Result? = null
    @Volatile private var lastLogs: JetBrainsLogScanner.Result? = null

    fun startOnce() {
        if (!started.compareAndSet(false, true)) return
        scheduler.execute {
            try {
                Files.createDirectories(stateRoot.resolve("state"))
                channel = FileChannel.open(stateRoot.resolve("state").resolve("collector.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                lock = runCatching { channel?.tryLock() }.getOrNull()
                if (lock == null) { mode = "其它 IDEA 实例正在采集，本实例只读"; return@execute }
                outbox = DurableOutbox(stateRoot)
                pending = outbox!!.stats().first
                mode = "后台采集运行中"
                YantuPlatformService.get().restoreOnceInBackground()
                scheduler.scheduleWithFixedDelay({ safePoll() }, 0, 5, TimeUnit.SECONDS)
            } catch (error: Exception) {
                mode = "采集启动失败"
                lastError = safeError(error)
                LOG.warn("研途助手采集启动失败", error)
            }
        }
    }

    fun syncNow() { if (mode == "后台采集运行中") scheduler.execute { safePoll() } }
    fun snapshot(): Snapshot = Snapshot(mode, pending, lastError, lastScanAt, lastUploadAt, collected, lastStructured, lastLogs)

    private fun safePoll() {
        try { poll() }
        catch (error: Exception) {
            lastError = safeError(error)
            LOG.warn("研途助手采集周期失败", error)
        }
    }
    private fun poll() {
        val store = outbox ?: return
        val structured = structuredScanner.scan()
        val logs = logScanner.scan()
        lastStructured = structured
        lastLogs = logs
        val mapper = GenerationMapper(store.installationId, diagnostic = { detail ->
            if (diagnosticsSeen.add(detail)) LOG.info("telemetry resolution $detail")
        })
        val openWorkspaces = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }.distinct()
        val observed = mutableSetOf<String>()
        val structuredKeys = structured.turns.map { it.conversationId to it.requestId }.toSet()
        val duplicateIds = structured.turns.groupBy { mapper.generationId(it.workspaceHash, it.conversationId, it.requestId) }
            .filterValues { turns -> turns.map { it.nativeInstallationId }.distinct().size > 1 }.keys
        val collisionWarning = if (duplicateIds.isNotEmpty()) "发现跨原生安装分区的重复轮次 ID，已跳过 ${duplicateIds.size} 轮" else ""
        if (collisionWarning.isNotEmpty()) lastError = collisionWarning
        for (turn in structured.turns) {
            val log = logs.turns[turn.requestId]?.takeIf {
                it.conversationId == turn.conversationId
            }
            val matchedWorkspace = if (turn.workspacePath.isNullOrBlank() && log?.workspacePath.isNullOrBlank())
                openWorkspaces.singleOrNull { WorkspaceHash.matches(it, turn.workspaceHash) } else null
            val mapped = mapper.structured(if (matchedWorkspace != null) turn.copy(workspacePath = matchedWorkspace) else turn, log) ?: continue
            if (mapped.generationId in duplicateIds) continue
            observed.add(mapped.generationId)
            store.upsertGeneration(mapped.generationId, mapped.payload, mapped.quality)
            for (skill in mapped.skills) store.enqueueSkill(skill.get("eventId").asString, skill)
        }
        for (turn in logs.turns.values) {
            if ((turn.conversationId to turn.requestId) in structuredKeys) continue
            val mapped = mapper.logOnly(turn) ?: continue
            if (mapped.generationId !in observed && mapped.generationId !in duplicateIds)
                store.upsertGeneration(mapped.generationId, mapped.payload, mapped.quality)
        }
        collected = structured.turns.size
        lastScanAt = Instant.now().toString()
        pending = store.stats().first
        val platform = YantuPlatformService.get()
        val result = UploadWorker(store, { platform.snapshot().state == "已登录" },
            { detail -> LOG.warn(detail) },
            { path, body, key -> platform.postTelemetry(path, body, key) }).flushOnce()
        if (result.uploaded > 0) lastUploadAt = Instant.now().toString()
        if (result.error != null) lastError = result.error
        else if (result.uploaded > 0 && collisionWarning.isEmpty()) lastError = ""
        pending = store.stats().first
    }
    private fun safeError(error: Throwable): String = (error.javaClass.simpleName + ": " + (error.message ?: ""))
        .replace(Regex("(?i)(bearer|token|password)[=: ]+[^ ,;]+"), "$1=[redacted]").take(180)

    override fun dispose() {
        scheduler.shutdownNow()
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
    companion object {
        private val LOG = Logger.getInstance(TelemetryApplicationService::class.java)
        fun get(): TelemetryApplicationService = ApplicationManager.getApplication().getService(TelemetryApplicationService::class.java)
    }
}
