# Webview 来源

源自 `opencode-gui` 提交 `dcf7b2b` 的 `src/webview` 和 `src/shared/messages.ts`。
IDEA 适配仅修改 `utils/vscode.ts`、设置面板及可选配置消息。开发机执行 `npm run build`，再把 `dist/main.js`、`dist/main.css` 复制到插件资源目录并更新 `build.gradle.kts` 中的 SHA-256。Gradle 构建验证预构建资源完整性；插件运行时不需要 Node.js。
