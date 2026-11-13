# 第三方许可检查

日期：2026-11-13。范围：0.11.1-alpha 的源码和模组 JAR。

## 参考来源

| 项目 | 检查版本 | 许可证 | 参考范围 |
|---|---|---|---|
| [T3 Code](https://github.com/pingdotgg/t3code/tree/7a64505a21e755498e6b85794124f3754265db38) | `7a64505` | MIT，Copyright (c) 2026 T3 Tools Inc. | `docs/internals/remote.md` 中的身份检查与配对架构 |
| [T3 Code for WoW](https://github.com/ryanrhughes/t3code-wow/tree/9fa45df58549d6beb390f67adde48d45baa186b5) | 0.4.0，`9fa45df` | MIT，Copyright (c) 2026 Ryan Hughes | `bridge/src/t3.js`、`addon/T3Code/UI/MainFrame.lua` 和 `docs/ARCHITECTURE.md` 中的待回复状态与答案提交方式 |

0.11.1-alpha 已移除远程实现和内嵌主机，保留本机 Agent 协议适配。0.11.0 另参考 T3 Code `6790f85` 的 `packages/provider-pi/src/server/adapter.ts`、`rpc.ts`、`packages/provider-opencode/src/server/v2/adapter.ts`、`OpenCode2Client.ts` 和 `OpenCode2Server.ts`，用于连接管理、事件归一化及完成状态处理。

新增上游参考：

| 项目 | 检查版本 | 许可证 | 参考范围 |
|---|---|---|---|
| DeepSeek Harness | `39163dd`；CLI 0.2.2-rc.3 | MIT，Copyright (c) 2026 DeepSeek | ACP、只读 sandbox 与配置 overlay |
| Pi | `275dde4`；CLI 1.5.2 | MIT，Copyright (c) 2025 Mario Zechner | JSONL RPC、结束事件和用量语义 |
| OpenCode | `de63679`；CLI 2.2.7 | MIT，Copyright (c) 2025 opencode | 本机服务、HTTP/SSE、权限、问答与用量结构 |

全部连接均使用 CLI 自身的公开接口。界面使用 AE2 组件。本次检查未发现直接打包的 T3/WoW 源文件、程序或素材。

## 许可与分发

MIT 允许使用、修改和分发，但要求副本或实质性部分保留版权及许可声明。本项目保留五份上游原文，包含版权、许可条件和免责条款：

- [LICENSE-T3CODE.txt](../LICENSE-T3CODE.txt)
- [LICENSE-T3CODE-WOW.txt](../LICENSE-T3CODE-WOW.txt)
- [LICENSE-DEEPSEEK-HARNESS.txt](../LICENSE-DEEPSEEK-HARNESS.txt)
- [LICENSE-PI.txt](../LICENSE-PI.txt)
- [LICENSE-OPENCODE.txt](../LICENSE-OPENCODE.txt)

五份文件随源码、模组 JAR分发。`tools/verify_release.py` 检查原文未改动，并核对模组 JAR 中的文件。项目自身的 LGPL-3.0-only 声明不替代上游 MIT 条款。

AE2、Gson 与 Gradle wrapper 的用途及许可见[第三方声明](../THIRD_PARTY_NOTICES.md)。以后引入新代码、素材或依赖时，需要补充对应的版权和许可记录。
