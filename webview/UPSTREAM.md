# Webview 来源

源自 `opencode-gui` 的 `src/webview` 和 `src/shared/messages.ts`；推荐与下载折叠参考提交 `f4e9b5a`，ASDM 工具包安装参考提交 `824b54d`。
IDEA 适配使用 JCEF 桥接和 JVM 安装器，保留 Mac 设置面板与 Windows 内置地址。开发机执行 `npm run build`，再把 `dist/main.js`、`dist/main.css` 复制到插件资源目录并更新 `build.gradle.kts` 中的 SHA-256。Gradle 构建验证预构建资源完整性；插件运行时不需要 Node.js。
