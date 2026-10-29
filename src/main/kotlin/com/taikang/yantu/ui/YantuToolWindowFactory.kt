package com.taikang.yantu.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.taikang.yantu.diagnostics.EnvironmentSnapshot
import com.taikang.yantu.diagnostics.EnvironmentSnapshotReader
import com.taikang.yantu.platform.YantuPlatformService
import com.taikang.yantu.telemetry.TelemetryApplicationService
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class YantuToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        TelemetryApplicationService.get().startOnce()
        val view = if (runCatching { JBCefApp.isSupported() }.getOrDefault(false)) {
            runCatching { JcefStatusView() }.getOrElse { error ->
                LOG.warn("研途助手 JCEF 初始化失败，启用 Swing 状态页", error)
                SwingStatusView()
            }
        } else {
            SwingStatusView()
        }
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        content.setDisposer(view)
        toolWindow.contentManager.addContent(content)
        view.refresh()
    }

    private interface StatusView : Disposable {
        val component: JComponent
        fun refresh()
    }

    private class JcefStatusView : StatusView {
        private val browser = JBCefBrowser()
        private val query = JBCefJSQuery.create(browser)
        private val disposed = AtomicBoolean(false)
        private val gson = Gson()
        override val component: JComponent = browser.component

        init {
            query.addHandler { raw ->
                if (raw.length <= 2048) {
                    val command = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                    val type = command?.get("type")?.asString
                    if (type == "ready" || type == "status") refresh()
                    if (type in setOf("configure", "login", "logout", "verify-api", "test-connection", "sync-now")) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val service = YantuPlatformService.get()
                            runCatching {
                                when (type) {
                                    "configure" -> service.configure(command?.get("apiBaseUrl")?.asString ?: "")
                                    "login" -> service.beginLogin(command?.get("apiBaseUrl")?.asString ?: "")
                                    "logout" -> service.logout()
                                    "verify-api" -> service.verifyApi()
                                    "test-connection" -> service.testConnection(command?.get("apiBaseUrl")?.asString ?: "")
                                    "sync-now" -> TelemetryApplicationService.get().syncNow()
                                }
                            }.onFailure(service::reportFailure)
                            refresh()
                        }
                    }
                }
                null
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val template = javaClass.getResourceAsStream("/webview/poc.html")
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: return@executeOnPooledThread
                val html = template.replace("/*__YANTU_JS_QUERY__*/", query.inject("JSON.stringify(message)"))
                SwingUtilities.invokeLater {
                    if (!disposed.get()) browser.loadHTML(html)
                }
            }
        }

        override fun refresh() = refreshInBackground(true) { snapshot ->
            val payload = gson.toJson(snapshot)
            // Gson encodes the complete DTO before it enters JavaScript; no log or path is interpolated.
            browser.cefBrowser.executeJavaScript("window.yantuHostEvent($payload);", "", 0)
        }

        private fun refreshInBackground(jcefActive: Boolean, deliver: (EnvironmentSnapshot) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val snapshot = runCatching { EnvironmentSnapshotReader.read(jcefActive) }
                    .getOrElse { error ->
                        LOG.warn("研途助手环境诊断失败", error)
                        return@executeOnPooledThread
                    }
                SwingUtilities.invokeLater { if (!disposed.get()) deliver(snapshot) }
            }
        }

        override fun dispose() {
            disposed.set(true)
            Disposer.dispose(query)
            Disposer.dispose(browser)
        }
    }

    private class SwingStatusView : StatusView {
        private val disposed = AtomicBoolean(false)
        private val text = JTextArea("正在读取本机环境…").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        override val component: JComponent = JPanel(BorderLayout()).apply {
            add(JScrollPane(text), BorderLayout.CENTER)
            add(JButton("刷新状态").apply { addActionListener { refresh() } }, BorderLayout.SOUTH)
        }

        override fun refresh() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val snapshot = runCatching { EnvironmentSnapshotReader.read(false) }
                    .getOrElse { error ->
                        LOG.warn("研途助手环境诊断失败", error)
                        return@executeOnPooledThread
                    }
                SwingUtilities.invokeLater {
                    if (!disposed.get()) text.text = """
                        研途助手 · IDEA POC

                        IDEA：${snapshot.ideaVersion}
                        插件：${snapshot.pluginVersion}
                        Java：${snapshot.javaVersion}
                        JCEF：不可用，当前为 Swing 状态页
                        TkCoding 日志目录：${snapshot.logsStatus}
                        TkCoding 数据目录：${snapshot.dataStatus}
                        JetBrains 数据分区：${snapshot.dataPartitions}
                        扫描会话：${snapshot.scannedSessions}
                        扫描请求：${snapshot.scannedRequests}
                        待重试文件：${snapshot.incompleteFiles}
                        日志文件：${snapshot.logFilesScanned}
                        日志请求：${snapshot.logRequests}
                        日志状态：${snapshot.latestLogState}
                        Token 冲突：${snapshot.tokenMismatch}
                        最近请求：${snapshot.latestRequestId}
                        请求状态：${snapshot.latestState}
                        Token：${snapshot.latestUsage}
                        Tool / Skill：${snapshot.latestTools} / ${snapshot.latestSkills}
                        代码增删：${snapshot.latestCode}
                        平台地址：${snapshot.platformUrl}
                        登录状态：${snapshot.loginState}
                        登录信息：${snapshot.loginMessage}
                        采集状态：${snapshot.telemetryMode}
                        待上传：${snapshot.pendingUploads}
                        最近采集：${snapshot.lastScanAt}
                        最近上传：${snapshot.lastUploadAt}
                        采集/上传错误：${snapshot.telemetryError}
                        设备码：${snapshot.userCode}
                        授权链接：${snapshot.verificationUri}
                        检查时间：${snapshot.checkedAt}
                    """.trimIndent()
                }
            }
        }

        override fun dispose() {
            disposed.set(true)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(YantuToolWindowFactory::class.java)
    }
}
