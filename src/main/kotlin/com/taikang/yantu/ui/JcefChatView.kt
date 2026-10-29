package com.taikang.yantu.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.taikang.yantu.chat.ChatService
import com.taikang.yantu.platform.YantuPlatformService
import com.taikang.yantu.platform.PlatformUrlResolver
import com.taikang.yantu.platform.WindowsPlatformConfig
import com.taikang.yantu.skills.SkillService
import com.taikang.yantu.wiki.WikiService
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class JcefChatView(private val project: Project) : Disposable {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser)
    private val closed = AtomicBoolean(false)
    private val chat = ChatService.get()
    private val skills = SkillService()
    private val wiki = WikiService()
    private val subscription: AutoCloseable
    private val visibilityListener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && browser.component.isShowing) scheduleRepaint()
    }
    val component: JComponent = browser.component

    init {
        chat.setProjectRoot(project.basePath)
        browser.component.addHierarchyListener(visibilityListener)
        query.addHandler { raw ->
            if (raw.length <= 65_536) {
                val message = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                if (message != null) runCatching { dispatch(message) }.onFailure { failure ->
                    LOG.warn("webview command failed", failure)
                    chat.notice("error", failure.message ?: "操作失败")
                }
            }
            null
        }
        subscription = chat.subscribe(::deliver)
        ApplicationManager.getApplication().executeOnPooledThread {
            val js = javaClass.getResourceAsStream("/webview/app.js")?.bufferedReader()?.use { it.readText() } ?: return@executeOnPooledThread
            val css = javaClass.getResourceAsStream("/webview/app.css")?.bufferedReader()?.use { it.readText() } ?: return@executeOnPooledThread
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes).let { Base64.getEncoder().encodeToString(it) }
            val bridge = query.inject("JSON.stringify(message)")
            val html = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'none'; img-src data:;"><style nonce="$nonce">$css</style></head><body><div id="root"></div><script nonce="$nonce">window.yantuManagedWindows=${WindowsPlatformConfig.enabled()};window.yantuHost={postMessage:function(message){$bridge}};window.yantuHostEvent=function(data){window.dispatchEvent(new MessageEvent('message',{data:data}));};</script><script type="module" nonce="$nonce">$js</script></body></html>"""
            SwingUtilities.invokeLater {
                if (!closed.get()) {
                    browser.loadHTML(html)
                    scheduleRepaint()
                }
            }
        }
    }

    private fun dispatch(message: JsonObject) {
        val type = message.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        fun id(key: String) = message.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,256}")) } ?: throw IllegalArgumentException("$key 格式无效")
        when (type) {
            "ready", "refresh" -> chat.execute { chat.bootstrap() }
            "login" -> chat.login()
            "logout" -> chat.execute { chat.logout() }
            "new-session" -> chat.execute { chat.newSession() }
            "select-session" -> chat.execute { chat.selectSession(id("sessionKey")) }
            "delete-session" -> chat.execute { chat.deleteSession(id("sessionKey")) }
            "send-message" -> {
                val content = message.get("content")?.asString ?: ""
                require(content.trim().length in 1..10_000)
                chat.execute { chat.sendMessage(content) }
            }
            "cancel-run" -> chat.execute { chat.cancelRun() }
            "configure" -> {
                require(!WindowsPlatformConfig.enabled()) { "Windows 版平台地址不可修改" }
                val base = message.get("apiBaseUrl")?.asString ?: throw IllegalArgumentException("地址不能为空")
                chat.execute {
                    YantuPlatformService.get().configure(base)
                    message.get("webBaseUrl")?.asString?.takeIf { it.isNotBlank() }?.let(YantuPlatformService.get()::configureWeb)
                    chat.bootstrap()
                }
            }
            "open-detail" -> {
                val detail = message.get("detailPath")?.asString ?: throw IllegalArgumentException("详情链接缺失")
                chat.execute { BrowserUtil.browse(PlatformUrlResolver.checkedDetail(YantuPlatformService.get().snapshot().webBaseUrl, detail).toASCIIString()) }
            }
            "install-skill", "install-all" -> {
                val runKey = id("runKey")
                val keys = if (type == "install-all") chat.recommendedKeys(runKey) else listOf(id("skillKey"))
                chat.execute {
                    val result = skills.install(project, runKey, keys, chat.currentOsType())
                    if (result.installed.isNotEmpty()) { chat.refreshSkillStatus(); chat.notice("info", "已安装：${result.installed.joinToString("、")}") }
                    if (result.failures.isNotEmpty()) chat.notice("error", "部分 Skill 打包失败：${result.failures.joinToString("；")}")
                }
            }
            "wiki-metadata-request" -> {
                val documentId = message.get("documentId")?.asLong?.takeIf { it > 0 } ?: throw IllegalArgumentException("文档 ID 无效")
                chat.execute {
                    val title = runCatching { wiki.title(documentId) }.getOrDefault("文档已不可访问")
                    deliver(JsonObject().apply { addProperty("type", "wiki-metadata"); addProperty("documentId", documentId); addProperty("title", title) })
                }
            }
            "download-wiki" -> {
                val ids = message.get("documentIds")?.asJsonArray?.map { it.asLong } ?: throw IllegalArgumentException("文档 ID 缺失")
                chat.execute {
                    val saved = wiki.download(ids)
                    if (saved != null) chat.notice("info", "已保存 ${ids.distinct().size} 篇 Wiki 文档：$saved")
                }
            }
            "retry-updates" -> chat.execute { chat.checkUpdates() }
            "dismiss-updates" -> chat.dismissUpdates()
            "install-updates" -> {
                val keys = message.get("skillKeys")?.asJsonArray?.map { it.asString } ?: throw IllegalArgumentException("更新项缺失")
                chat.execute { chat.installUpdates(project, keys) }
            }
            else -> chat.notice("error", "该功能正在接入：$type")
        }
    }
    private fun deliver(event: JsonObject) {
        val payload = event.toString()
        SwingUtilities.invokeLater {
            if (!closed.get()) browser.cefBrowser.executeJavaScript("window.yantuHostEvent($payload);", "", 0)
        }
    }
    private fun scheduleRepaint() {
        for (delay in intArrayOf(100, 500)) Timer(delay) {
            if (!closed.get() && browser.component.isShowing && browser.component.width > 0 && browser.component.height > 0) {
                browser.cefBrowser.wasResized(browser.component.width, browser.component.height)
                browser.component.revalidate()
                browser.component.repaint()
            }
        }.apply { isRepeats = false; start() }
    }
    override fun dispose() {
        closed.set(true)
        browser.component.removeHierarchyListener(visibilityListener)
        subscription.close()
        Disposer.dispose(query)
        Disposer.dispose(browser)
    }
    companion object { private val LOG = Logger.getInstance(JcefChatView::class.java) }
}
