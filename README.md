# 研途助手 IDEA 插件 · P0-C/D 遥测闭环验证版

目标环境：Windows IntelliJ IDEA 2026.1.5（build 261）。本版在已验证的设备码登录和结构化指标诊断基础上，加入 IDEA JVM 内的后台采集、日志与结构化轮次合并、本地持久化队列及平台上传。运行时不需要 Node.js 或独立采集进程。

## 构建与安装

开发机使用 JDK 17，执行 `./gradlew buildPlugin`；Windows 可运行 `gradlew.bat buildPlugin`。ZIP 位于 `build/distributions/`。在 IDEA 中从 **Settings → Plugins → ⚙ → Install Plugin from Disk** 安装并重启。请确认工具窗口显示的新插件版本，再开始测试。

## Windows 实机验证

1. 打开“研途助手”，确认“采集状态”为“后台采集运行中”。即使关闭工具窗口，后台服务仍按约 5 秒周期扫描。多个 IDEA 实例中只允许一个持有采集锁；其它实例显示只读状态。
2. 在平台 API 地址输入 `http://10.154.76.195/api/v1`，执行设备码登录并点击“验证 API”。登录前产生的轮次会先保留在本地，登录后自动上传。
3. 在 TkCoding IDEA 插件中进行连续两轮操作：第一轮只读并调用平台安装的 Skill，第二轮接受代码修改。刷新状态页核对最近 requestId、Token、Tool/Skill 与代码 `+/-`。设计样本第二轮应为 `+10/-2`、2 个修改文件，第一轮代码变更为 0。
4. 查看“待上传”和“最近上传”。点击“立即同步”可触发一次扫描与上传；刚结束的结构化轮次会等待约 10 秒稳定，只有日志的轮次会等待约 30 秒。后台应能按同一 generationId 保留最终 revision；SkillUsage 的同一 toolCallId 只产生一个 eventId。
5. 断网后完成一轮，确认“待上传”增加；退出并重新打开 IDEA，恢复网络和登录后点击“立即同步”，确认待上传归零。重复刷新和重启不应新增重复记录。
6. 在平台后台核对 Generation 与 SkillUsage：同一轮的 request、Token、代码行数及平台 Skill 次数一致。请特别检查非平台安装的 Skill 不产生 SkillUsage。

若上传失败，状态页会显示接口路径、HTTP 状态或简短异常。不要发送访问令牌、原始对话、源码或完整本地日志。

## 数据范围与口径

- 只读 `%LOCALAPPDATA%/CodeBuddyExtension/Data/*/JetBrains/*` 的最近 7 天结构化 history、messages 中的 Tool 元数据及 file-tree 的 accepted 版本；日志读取 `Logs/JetBrains` 最近两天的有界尾部。每个 JSON 最大 8 MiB，最多 500 会话；日志每文件最多 2 MiB、最多 30 文件。
- 按 workspaceHash、conversationId 和 requestId 生成稳定 Generation ID；结构化 usage 优先，日志只作补充和冲突标记，不相加。file-tree 只按精确 requestId 计代码增删，按文件类型聚合后上报，不上传文件路径或源码。
- 仅将带 `.yantu-platform-skill.json` 标记的 Skill 计入平台 SkillUsage；SkillUsage 使用稳定 UUID 和 `Idempotency-Key`。Generation 使用单调 revision，后端按 `generationId` upsert。
- 本地持久化目录为 `%LOCALAPPDATA%/YantuAssistantJetBrains/`。除平台协议要求的项目目录元数据外，队列不保存对话正文、源码快照或令牌；refreshToken 仍只在 IDEA PasswordSafe 中。
- 采集队列跨重启恢复。HTTP 401 尝试刷新令牌一次，403 保留队列，网络或 5xx 有界退避。只有 2xx 响应会确认上传。

## 当前验证边界

本机没有 Windows 的 TkCoding JetBrains 数据目录，因此本地仅执行脱敏样本、持久化/请求测试和插件构建。Windows 实机的日志格式、完整双通道指标、平台查询与断网恢复仍须按上述步骤验收。当前日志实现使用最近文件的有界重扫并依靠稳定事件键去重；长时间未运行期间超出 2 MiB 尾部的日志事件主要依赖结构化 history 回补。
