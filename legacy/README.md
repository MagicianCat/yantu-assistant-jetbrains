# IDEA 2021–2023 旧版插件构建

此目录复用上级 `src/main` 与 `src/test`，使用 Gradle 7.6.4、Gradle IntelliJ Plugin 1.17.4 和 IDEA 2021.1 SDK 构建 Java 11 字节码。插件声明支持 IDEA build `211` 至 `233.*`（2021.1–2023.3）。2022.3–2023.3 的 IDE 运行在 Java 17 上，可以加载 Java 11 字节码，但仍需逐版检查 IntelliJ API 的二进制兼容性。没有 IntelliJ GUI Designer 表单，因此关闭旧版 SDK 上不可用的代码插桩。

从此目录执行 `./gradlew buildPlugin verifyLegacyCommandUsage verifyLegacyScanner verifyLegacyUpload verifyLegacyRequest verifyLegacyAsdmInstaller verifyLegacyPlatformUrl`。安装包位于 `build/distributions/`。当前 0.6.7 包在 2021.1、2021.2、2021.3、2022.1、2022.2、2022.3、2023.1、2023.2 和 2023.3 上通过 IntelliJ Plugin Verifier 1.410 的二进制兼容检查；2021.2、2021.3 和 2022.1 报告两处 JCEF `JBCefJSQuery.create(JBCefBrowser)` 废弃 API 使用，但没有二进制不兼容问题。登录、JCEF 和 TkCoding 数据源仍需在目标 Windows 版本上实机验证。
