# Delivery preferences

- By default, deliver a direct link to the compiled mod JAR only. The user prefers this over ZIP bundles, source packages, or repeated setup instructions.
- Name releases `applied-intelligence-<mod-version>-mc<minecraft-version>-neoforge.jar`. Keep the build configuration responsible for this name.
- Include additional artifacts or setup explanations when the user explicitly requests them.

- Keep `zh_cn` and `en_us` UI translations complete. Preserve the names Agent, Skill, Codex and AE2.
- Current scope is private singleplayer. Defer multiplayer Agent features until the user requests them.

- Remote access is temporarily withdrawn. Keep releases local-only; do not restore pairing, remote services, or the standalone host without a new user request. Do not republish withdrawn 0.10.0-alpha or 0.11.0-alpha binaries.
- Update README.md and README.en.md together when supported capabilities change. Preserve the user-written introduction, language buttons, and legal notices.
