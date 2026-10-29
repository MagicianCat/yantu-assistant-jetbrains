# Webview 与 Kotlin Bridge

界面复用 `webview/src/shared/messages.ts` 的消息类型。前端通过 `window.yantuHost.postMessage(message)` 发送 JSON；宿主通过 `window.yantuHostEvent(event)` 回推。`ready` 是握手和状态恢复入口，重复发送不会创建会话。

## 前端命令

`ready`、`refresh`、`login`、`logout`、`new-session`、`select-session`、`delete-session`、`send-message`、`cancel-run`、`configure`、`open-detail`、`install-skill`、`install-all`、`install-updates`、`dismiss-updates`、`retry-updates`、`wiki-metadata-request`、`download-wiki`。

`send-message.content` 限制为 1–10000 字符；会话、运行和 Skill 标识限制为 1–256 个字母、数字及 `._:-`；Wiki ID 必须为正数。消息体上限 64 KiB。所有网络、文件与浏览器操作由 Kotlin 宿主执行，Webview 的 CSP 禁止网络连接。

## 宿主事件

`state` 是全量快照，包含登录、会话、消息、运行、推荐、更新及连接状态。`notice` 包含 `level` 和 `message`，用于操作结果及错误。`wiki-metadata` 包含 `documentId` 和 `title`。状态结构以共享消息类型为准。

发送成功后宿主订阅 SSE，按顺序更新消息；运行结束、失败或取消时刷新会话详情。会话切换、退出登录和关闭插件页面时关闭当前 SSE。页面关闭后宿主丢弃 UI 投递。

## 资源与兼容性

JCEF 加载打包的静态 JS/CSS，使用随机 CSP nonce。`build.gradle.kts` 校验资源 SHA；修改 `webview/src` 后需先执行 `npm run build` 并更新 SHA。测试包分别针对 IDEA 2025.2（build 252）和 2026.1（build 261）。
