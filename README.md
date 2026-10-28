# 研途助手 IDEA 插件 0.3.5-p0cd-direct

**当前实机验证目标：Windows 内网平台设备码登录。**

Windows 直连探针已确认同一授权申请接口在 `10.154.76.195` 返回 HTTP 200（0.203 秒）；旧版插件却收到 504。本版对私网 API 显式绕过系统代理直连，避免 IDEA JVM 的代理路径干扰。

Windows IDEA 2026.1（IC-261）插件包：[0.3.5-p0cd-direct.zip](build/distributions/yantu-assistant-jetbrains-0.3.5-p0cd-direct.zip)。

macOS 本机 IDEA 2025.2.6.3（IC-252）验证包：[0.3.5-p0cd-direct-ic252.zip](build/distributions/yantu-assistant-jetbrains-0.3.5-p0cd-direct-ic252.zip)。该包用本机 IC-252 SDK 编译，支持 build 252.*；本机安装时请选此包。安装后确认工具窗口中的插件版本与所选 ZIP 一致，再执行验证。

修复说明：未保存平台地址时点击登录，旧版会构造缺少协议的请求 URI；新版会直接读取并保存输入框地址。设备授权页按 `opencode-gui` 的方式将后端返回的绝对 URL 转成字符串交给系统浏览器。若浏览器未能自动打开，页面仍保留设备码和授权链接，可手动复制打开。本机 `127.0.0.1:8090` 已验证创建授权返回 200、待批准轮询返回 202。

## 不安装插件的授权接口诊断

如果 Windows 上 `GET /api/v1/auth/me` 直连返回 401，但插件的授权申请仍为 504，先在 Windows PowerShell 运行：

```powershell
.\scripts\diagnose-device-auth.ps1 -ApiBaseUrl "http://10.154.76.195/api/v1"
```

脚本只发起一次有效 PKCE 授权申请，不批准该设备码；仅输出 HTTP 状态、耗时、远端 IP 和 `Server/Via/X-Request-Id` 响应头，不输出设备码、PKCE verifier 或响应正文。若直连 POST 返回 200，优先检查 IDEA 的代理/请求路径；若同样返回 504，检查服务器前端 Nginx 和后端日志。无需重新安装插件。

## Windows 实机验证步骤

1. 在 IDEA 2026.1.5 中打开 **Settings → Plugins → ⚙ → Install Plugin from Disk**，选择与本机 IDEA build 匹配的 ZIP，安装并重启 IDEA。
2. 打开左侧“研途助手”，确认有“平台登录”区域，以及“日志文件 / 日志请求 / 日志状态 / Token 冲突”字段。
3. 在“平台 API 地址”输入 `https://平台域名/api/v1`，可直接点击“设备码登录”，插件会先校验并保存输入框地址；“保存地址”按钮仍可单独使用。若平台仅提供获授权的内网 IPv4 HTTP 地址，也可输入 `http://内网地址:端口/api/v1`。
4. 若 Windows 曾出现授权申请 HTTP 504，先在浏览器检查 `http://10.154.76.195/actuator/health`（Actuator 不带 `/api/v1`），再点击“测试连接”，记录 GET /auth/me 的状态码、耗时、请求路由和 Server/Via。401 表示已到达需登录的 API。再点击“设备码登录”，若仍为 504，记录页面显示的 Server/Via。
5. 点击“设备码登录”。插件应打开授权网页，页面应显示设备码和“等待浏览器授权”。用平台账号在浏览器批准授权，等待状态变为“已登录”。
6. 点击“验证 API”，确认显示“受保护 API 验证成功”。重启 IDEA，再点一次“验证 API”，确认刷新令牌可恢复登录。
7. 点击“退出登录”后再次点“验证 API”，确认不再通过。若发生错误，记录操作步骤、页面状态和 HTTP 状态码；不要发送令牌或完整日志。

## 本版能力与边界

- 保留 P0-B 的结构化轮次、Token、Tool/Skill 和 accepted 代码增删行诊断。上一版的双轮指标验证已完成，无需按旧 README 重做。
- 新增最近两天 `Logs/JetBrains` 的有界尾部读取，按 `requestId` 展示日志请求与结构化最近轮次的状态和 Token 差异。每文件最多 2 MiB，最多 30 个文件。
- 新增平台地址配置、设备码与 PKCE 授权、受保护 API 验证、退出登录；刷新令牌存 IDEA PasswordSafe。
- 此包**不会上传遥测**。持久化日志游标、完整双通道合并、出站队列和 Generation/SkillUsage 上传仍待后续 P0-C/D 开发。

## 构建

开发机使用 JDK 17：`./gradlew buildPlugin`。Windows 可运行 `gradlew.bat buildPlugin`。运行时由 IDEA JVM 执行，不需要 Node.js 或独立采集进程。若开发机已缓存 IDE SDK，可用 `-PlocalIdePath=/absolute/path/to/idea` 离线构建。
