# Dependency record / 依赖记录

Date / 日期：2026-11-14

This prototype uses the repository's [LGPL-3.0-only license](../../LICENSE). It references dependency APIs and does not copy T3 Code implementation code. Existing project notices remain unchanged.

此原型采用仓库的 LGPL-3.0-only 许可证。实现调用依赖 API，未复制 T3 Code 实现源码。项目现有版权声明保持有效。

## Runtime / 运行依赖

| Maven artifacts | Locked version | Upstream license |
|---|---|---|
| `org.eclipse.jetty:jetty-server`, `jetty-http`, `jetty-io`, `jetty-util` | 12.1.15 | [Apache-2.0 or EPL-2.0](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-project/12.1.15/jetty-project-12.1.15.pom) |
| `org.bouncycastle:bcpkix-jdk18on`, `bcprov-jdk18on`, `bcutil-jdk18on` | 1.87 | [Bouncy Castle MIT license](https://www.bouncycastle.org/about/license/) |
| `com.fasterxml.jackson.core:jackson-core`, `jackson-databind` | 2.23.0 | [Apache-2.0](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.23.0/jackson-databind-2.23.0.pom) |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.23 | [Apache-2.0](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.23/jackson-annotations-2.23.pom) |
| `org.slf4j:slf4j-api` | 2.0.17 | [MIT](https://www.slf4j.org/license.html) |

The Jackson BOM 2.23.0 also appears in the lock file. A BOM supplies dependency metadata, not runtime classes.

锁文件还包含 Jackson BOM 2.23.0。BOM 提供依赖元数据，不提供运行类。

## Tests / 测试依赖

JUnit Jupiter 5.11.4 and JUnit Platform 1.11.4 use EPL-2.0. The test logger, `slf4j-simple` 2.0.17, uses MIT. API Guardian 1.1.2 and OpenTest4J 1.3.0 use Apache-2.0. Exact transitive versions appear in `gradle.lockfile`.

测试依赖包括 JUnit、SLF4J 测试日志、API Guardian 和 OpenTest4J。上文列出许可证，锁文件列出完整版本。

## Checks and distribution / 检查与分发

- Gradle locks all configurations in strict mode. SHA-256 records cover downloaded artifacts and metadata.
- The initial checksum records trust the initial Maven Central download. They do not establish independent publisher provenance or verify signatures.
- OSV returned no known entries for the twelve runtime coordinates on the date above. This result includes the BOM. It does not prove that the dependencies contain no vulnerabilities.
- Run `audit_dependencies.py` to repeat the runtime query. Test dependencies require their own release-time review.
- No dependency listed here enters the current mod JAR. The prototype has no distributable JAR. CI builds only its tests.
- Any future binary distribution must include the applicable license texts, upstream notices, and dependency records. That distribution still requires the separate security review.

Gradle 使用严格版本锁和 SHA-256 校验。初始校验记录来自 Maven Central 下载，未独立验证发布者身份或签名。

截至上述日期，OSV 未返回这十二项运行坐标的已知漏洞条目，其中包含 BOM。这不是安全认证。审查发行依赖时仍须查询最新公告，并单独检查测试工具。

这些依赖不进入当前模组 JAR。未来如分发包含它们的二进制文件，必须附上适用的许可全文、版权声明和依赖记录，并另行完成安全验收。
