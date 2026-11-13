# Remote access withdrawal / 远程功能撤回

2026-11-13：0.10.0-alpha 和 0.11.0-alpha 的公开发行及 JAR 附件已撤回，相关 GitHub Actions 构建附件已删除。

已确认旧版默认通过 HTTP 明文传送配对码、访问凭据、任务和结果。私有 IP 检查和配对码不能加密链路，也不能阻止网络路径上的攻击者截获凭据。旧机器 ID 校验不等于加密身份认证。

0.11.1-alpha 移除远程服务、配对、远程连接客户端和独立 host。新版本只运行本机 Agent；旧共享配置不会启动远程监听，旧远程任务不会改派本机。已完整归档的书和图片保留。

如果你已下载旧版，请关闭独立 host 及仍运行旧模组的游戏进程，然后替换旧 JAR。更新一台电脑不能停止另一台电脑上的旧 host，也不能召回已经下载或转存的文件。不要继续分发旧版或复用旧配对凭据。

本机 CLI 仍可能联系其配置的模型服务；OpenCode 2 保留仅监听本机、使用随机认证的官方接口。本版移除的是跨机器控制能力，不是模型服务访问。

本次处理为撤回存在传输风险的功能，不代表完成了全部 Agent、CLI 或本机权限的安全审计。没有证据支持“已遭入侵”或“没有发生泄露”的结论。

---

On 2026-11-13, releases 0.10.0-alpha and 0.11.0-alpha were withdrawn. Their JAR attachments and associated GitHub Actions build artifacts were deleted.

The old default remote transport sent pairing codes, access credentials, tasks, and results over plaintext HTTP. Private IP checks and pairing codes do not encrypt traffic. An attacker on the network path could intercept credentials. Machine ID checks do not provide cryptographic server authentication.

Version 0.11.1-alpha removes the remote server, pairing, remote client, and standalone host. It supports local Agents only. Old sharing settings cannot start a remote listener. Old remote tasks cannot run locally. Books and fully archived images remain available.

Close old standalone hosts and game processes before replacing the old JAR. Updating one computer cannot stop an old host on another computer. Previously downloaded or copied files cannot be recalled. Do not distribute old releases or reuse their pairing credentials.

Local CLIs can still contact their configured model services. OpenCode 2 retains its authenticated loopback interface. Cross-computer control is removed.

This withdrawal does not constitute a complete audit of Agent CLIs or local permissions. The available evidence establishes neither a compromise nor the absence of data exposure.
