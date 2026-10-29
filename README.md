# 研途助手 IDEA 插件 · P1–P3 本地验证版

当前版本 `0.5.6-p1p3-managed`。复用 TkCoding 的 SolidJS 聊天页面，在 IDEA 插件 JVM 内接入平台会话、SSE、Skill 推荐/安装/更新及 Wiki 下载。此前已通过 Windows 验证的遥测采集与上传仍独立后台运行。

## 安装与本地测试

Mac IDEA 2025.2 使用 `build/distributions/*-ic252.zip`；Windows IDEA 2026.1 使用 `build/distributions/*-ic261.zip`。在 IDEA **Settings → Plugins → Install Plugin from Disk** 安装并重启。插件运行时不需要 Node.js。

1. Windows 版内置平台 API `http://10.154.76.195/api/v1` 和网页地址 `http://10.154.76.195`，没有设置入口，直接点击登录并完成浏览器设备码授权。Mac 本地测试版可点击右上角 ⚙，将 API 地址设为 `http://127.0.0.1:8090/api/v1`。
2. 新建会话并发送一条消息，检查文字流、停止按钮、历史会话切换/删除和重新打开窗口后的恢复。
3. 对带 Skill 推荐的回答，检查项目/全局安装状态，选择安装范围并确认覆盖；核对 `.codebuddy/skills/<key>/SKILL.md`、`skill.yaml` 和 `.yantu-platform-skill.json`。安装时应连同依赖安装。
4. 检查欢迎页 Skill 更新卡片，选择更新并核对项目与全局两个目录；手动在任一目录放置同名非平台 Skill 时应出现覆盖警告。
5. 对带 `/wiki?documentId=<id>` 链接的回答，检查标题查询、单篇 Markdown 与多篇 ZIP 下载；取消保存对话框不应下载。

## 已实现的安全边界

- JCEF 页面不保存令牌，不直接请求平台 API。设备码和刷新令牌仍由 IDEA PasswordSafe 管理。
- Bundle 和每个 Artifact 校验 SHA-256；ZIP 拒绝穿越路径、符号链接、重复路径、ZIP64、加密及异常大小；安装在同目录暂存，失败恢复备份。
- Skill 详情按配置的网页地址做同源校验，并在外部浏览器打开；Wiki 文件经临时文件写入再替换目标。
- Webview 来源与构建步骤见 `webview/UPSTREAM.md`。Gradle 校验嵌入资源 SHA-256；Node.js 只用于开发构建。

## 验证边界

已完成 Kotlin 编译、脱敏 Skill 安装与 ZIP 安全样本、既有遥测样本、前端 Chromium 渲染测试。本地后端 `127.0.0.1:8090` 可达；需要人工完成设备码授权，才能进行会话、Bundle 和 Wiki 的真实接口验收。Mac 与 Windows 的 UI/平台接口语义相同；项目路径和 TkCoding 数据源仍需 Windows 回归。

若出现问题，在 IDEA **Help → Show Log** 中截取 `webview command failed`、`chat command failed` 或 `telemetry` 的相关行；不要发送访问令牌、原始对话或完整日志。
