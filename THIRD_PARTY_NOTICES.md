# Third-party notices

Applied Intelligence uses [LGPL-3.0-only](LICENSE). Third-party material retains its own license.

## Runtime and build components

| Component | Use | License |
|---|---|---|
| [AE2 19.2.19](https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/da9bd7f09a94272a67f9825e4c0f4f6b61983b69) | External dependency for menus, widgets, models, and textures | LGPL-3.0-or-later, Copyright AlgorithmX2 and AE2 contributors |
| [Gson 2.10.1](https://github.com/google/gson/tree/gson-parent-2.10.1) | External game/runtime dependency | Apache-2.0, Copyright Google Inc. See [LICENSE-GSON.txt](LICENSE-GSON.txt) |
| [Gradle wrapper](https://github.com/gradle/gradle/blob/master/LICENSE) | Build launcher | Apache-2.0. Copyright headers and the wrapper JAR's `META-INF/LICENSE` remain intact |

The mod references AE2 and vanilla Minecraft textures at runtime. It does not bundle those textures or AE2 classes. Gson is no longer embedded because the standalone host was removed in 0.11.1-alpha.

## Design references

- **T3 Code** — Copyright (c) 2026 T3 Tools Inc. The earlier remote design (removed in 0.11.1-alpha) referenced its [architecture documentation](https://github.com/pingdotgg/t3code/blob/7a64505a21e755498e6b85794124f3754265db38/docs/internals/remote.md). The multi-Agent adapters also reference its [Pi RPC adapter](https://github.com/pingdotgg/t3code/blob/6790f85f53a060a3c322089c3fac5dfcecdfc8b1/packages/provider-pi/src/server/adapter.ts) and [OpenCode 2 adapter](https://github.com/pingdotgg/t3code/blob/6790f85f53a060a3c322089c3fac5dfcecdfc8b1/packages/provider-opencode/src/server/v2/adapter.ts). Full MIT license: [LICENSE-T3CODE.txt](LICENSE-T3CODE.txt).
- **T3 Code for WoW** — Copyright (c) 2026 Ryan Hughes. The question workflow references its [waiting state and response handling](https://github.com/ryanrhughes/t3code-wow/tree/9fa45df58549d6beb390f67adde48d45baa186b5). Full MIT license: [LICENSE-T3CODE-WOW.txt](LICENSE-T3CODE-WOW.txt).

- **DeepSeek Harness** — Copyright (c) 2026 DeepSeek. The ACP adapter references its [official protocol and sandbox implementation](https://github.com/deepseek-ai/deepseek-harness/tree/39163dd30f703a79079e2d2446545cff3c453738). Full MIT license: [LICENSE-DEEPSEEK-HARNESS.txt](LICENSE-DEEPSEEK-HARNESS.txt).
- **Pi** — Copyright (c) 2025 Mario Zechner. The RPC adapter references its [official RPC interface](https://github.com/earendil-works/pi/tree/275dde410065080d291de45fbe2110272d10de23/packages/coding-agent). Full MIT license: [LICENSE-PI.txt](LICENSE-PI.txt).
- **OpenCode** — Copyright (c) 2025 opencode. The HTTP/SSE adapter references its [official v2 API and event schemas](https://github.com/anomalyco/opencode/tree/de63679df018d91e7bfd81d443971bcabd36372a). Full MIT license: [LICENSE-OPENCODE.txt](LICENSE-OPENCODE.txt).

The mod uses its own Java adapter and AE2 interface. It does not bundle T3 executables, WoW addons, or their assets. All five MIT notices remain included for the referenced material and any applicable adaptation.

The mod JAR includes the project license, Gson license, the T3, DeepSeek Harness, Pi, and OpenCode licenses, and these notices under `META-INF/`. See the [license audit](docs/LICENSE-AUDIT.md) for the reference scope and package checks.
