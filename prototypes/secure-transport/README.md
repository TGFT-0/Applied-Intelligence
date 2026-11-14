# Secure transport prototype / 安全传输原型

[中文](#中文) · [English](#english)

## 中文

这是独立测试项目，不是可以使用的远程 host。模组继续仅支持本机。

原型仅监听 `127.0.0.1` 的临时端口。没有启动入口，不生成 JAR，不接真实 Agent、模型凭据或工作目录。根项目不引用此项目。

已验证的行为：

- TLS 1.3、专用 CA、标准证书链与地址验证、控制端双向证书认证。
- 临时登记端口、本地批准、每设备权限、证书续期及撤销。
- 持久模拟工单、按设备划分的幂等 ID、重启后标记中断。
- 接单后断网或关闭客户端，模拟工单继续执行。重连与重试不重新启动同一工单。
- 握手、请求头和请求体的绝对截止时间，长度上限及严格 JSON。
- 拒绝浏览器标记、旧 bearer、错误 Host、明文、旧 TLS 与模糊 HTTP 报文。

在仓库根目录使用 Java 21 运行：

```sh
./gradlew -p prototypes/secure-transport test
python3 prototypes/secure-transport/audit_dependencies.py
```

2026-11-14：28 项测试通过。测试使用真实回环 TLS 和模拟工单，不消耗模型 Tokens。

**未完成发布条件：** 持久私钥和 OS 密钥存储、生产环境的文件/进程/模型凭据隔离、完整限流、慢 SSE 压测、真实任务事件、跨电脑与平台验收、AE2 界面接入、独立安全评审。

测试中的私钥只保存在内存。重启测试复用测试 CA，并不证明真实主机的身份恢复功能。`executions` 记录持久启动意图，不是模型调用次数。模拟任务无需读取任何项目文件。心跳流只是测试流，不提供任务结果。

原型禁用 HTTP 连接复用，SSE 每次仅等待一帧写入，并设置 10 秒写截止。现有测试未覆盖所有慢消费者和连接洪泛情况。连接上限不能保证公平性，也不能抵抗网络带宽耗尽。

执行端进程停止后，未完成任务在重新打开状态库时标记中断，不自动重跑。网络断开不会触发此行为。真实外部操作不承诺跨崩溃只执行一次。

完整设计和待验收项见 [REMOTE-CONTROL-V2.md](../../docs/design/REMOTE-CONTROL-V2.md)。依赖与许可证记录见 [DEPENDENCIES.md](DEPENDENCIES.md)。

## English

This separate project tests transport behavior. It does not provide a usable remote host. The mod supports local execution only.

The prototype binds temporary ports on `127.0.0.1`. It has no launcher and produces no JAR. It cannot access Agent backends, model credentials, or project directories. The root project does not include this project.

The tests cover these behaviors:

- TLS 1.3, a dedicated CA, standard certificate and address checks, and mutual authentication for control requests.
- A temporary enrollment port, local approval, device permissions, certificate renewal, and revocation.
- Persistent mock jobs, device-specific idempotency IDs, and interrupted status after a host restart.
- Mock jobs continue after a client disconnects or closes. Reconnection and retries do not restart the same job.
- Absolute deadlines for handshakes, headers, and bodies, plus size limits and strict JSON.
- Rejection of browser headers, legacy bearer credentials, incorrect Host headers, plaintext, old TLS, and ambiguous HTTP framing.

Use Java 21. Run these commands from the repository root:

```sh
./gradlew -p prototypes/secure-transport test
python3 prototypes/secure-transport/audit_dependencies.py
```

On 2026-11-14, all 28 tests passed. Tests use real loopback TLS and mock jobs. They do not consume model Tokens.

Release requirements remain incomplete. These include persistent keys, OS key storage, worker isolation, model credential isolation, complete rate limits, and slow-consumer stress tests. Real task events, two-computer tests, platform acceptance, AE2 integration, and independent security review also remain incomplete.

Test keys exist only in memory. Restart tests reuse the test CA. These tests do not prove durable host identity recovery. The `executions` field counts persistent launch intents, not model calls. Mock jobs read no project files. The heartbeat stream contains no task results.

The prototype disables HTTP connection reuse. Each SSE stream permits one pending frame and applies a ten-second write deadline. Tests do not cover every slow-consumer or connection-flood scenario. Connection limits do not guarantee fairness or prevent bandwidth exhaustion.

After the execution host stops, unfinished jobs become interrupted when the state store opens again. They do not restart automatically. Network disconnection does not trigger this behavior. The design does not promise exactly-once external effects across crashes.

Read the [design and acceptance requirements](../../docs/design/REMOTE-CONTROL-V2.md). See [DEPENDENCIES.md](DEPENDENCIES.md) for dependency and license records.
