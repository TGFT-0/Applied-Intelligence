# Changelog

## 0.10.0-alpha

- Receive bounded PNG/JPEG images from Codex app-server image items, MCP image content and final Markdown references inside the execution workspace.
- Add an AE2 image collection tile to the existing crafting steps and a shared gallery with thumbnails, navigation, fit/100%, zoom and pan.
- Keep final text in a vanilla written book, with clickable image attachment pages. Archive every advertised image in the world before delivering the product, so books survive remote disconnects and game restarts.
- Re-encode images without source metadata, restrict file access, validate dimensions and hashes, bound authenticated transfers, and release gallery textures on close.
- Track the release version in generated mod metadata. Curate public source and fixture-only screenshots; exclude runtime logs, configurations, pairing data and development saves.

## 0.9.0-alpha

- Add the approved inline, multiline Steering composer below four scrollable crafting rows. Use AE2 field textures and its native Send button; preserve the CPU list and footer. Restore six rows when the execution ends.
- Send `turn/steer` to the original job, host and expected active turn. Show Added and clear the draft only after a matching Codex acknowledgment. Keep rejected input editable and uncertain delivery identifiable for safe retries.
- Persist bounded idempotency receipts without raw instruction text. Concurrent retries forward once; lost transport replies can be reconciled, stale turns are refused, and execution-host restart never replays uncertain instructions.
- Keep drafts separate by job. Preserve the existing question/answer flow and refuse ambiguous identical-recipe CPU views. Read-only sandbox and approval policy remain unchanged.
- Add ten regression/integration tests (62 total) and extend the packaged standalone JAR test to run Steering without Minecraft on its classpath.

Official CLI accepted the protocol handshake and refused an idle-turn steer without starting a model turn. Full model-driven Steering remains unverified; development screenshots and executing integration tests use a CLI subprocess fixture.

## 0.8.1-alpha

- Replace the answer choices, Previous, Next and Send buttons with AE2's own `AE2Button`. Use its resource sprites, borders, text colors, hover and disabled rendering instead of Minecraft's stone buttons.
- Preserve answer selection markers, drafts, paging, submission and existing layout.

## 0.8.0-alpha

- Move one-shot execution to the official Codex stdio app-server protocol. Enable structured questions in Default mode while keeping the read-only sandbox and never-approve policy.
- Show a book-shaped Needs input entry in the native crafting table and mark the CPU while it waits. Open an AE2 dialog from that entry, with paged questions, suggested options, free text and draft preservation in the same screen.
- Reply to the original JSON-RPC request on its original turn. Bind answers to the owning job and host; reject stale or conflicting answers and acknowledge duplicate retries without answering the next question.
- Pause the Agent execution timeout while waiting for a person. Invalidate questions on cancellation, server resolution and execution-host shutdown. Restore active timeout after answering.
- Preserve native progress, context, cumulative Tokens and written-book output through the new protocol. Keep question text and answer receipts out of cards and items; do not persist raw player replies separately.
- Add eight tests for question validation, timeout behavior, cancellation, restart, sequential questions and real HTTP routing (52 total). Keep the packaged standalone-host check.

Requires a recent Codex CLI with `default_mode_request_user_input`. Official CLI 0.167.0-alpha.4 completed initialize and thread/start without starting a model turn. Full model-driven question generation remains unverified at the user's request. Ordinary chat questions and permission approvals are outside this release.

## 0.7.1-alpha

- Add the approved two-line Tokens readout to the native crafting status footer, with one native tooltip for input, cached input, output and total. Include Chinese and English; no auxiliary footnotes or new controls.
- Keep CTX as context capacity. Show unknown usage as an em dash and keep the last execution visible while its CPU is idle in the same screen, including completion-only CLI reports.
- Read cumulative CLI usage snapshots without double counting repeated events or cached input. Validate counts and preserve the last valid snapshot; persist and transport usage through local and remote job summaries and details.
- Add five parser regression tests and extend process, persistence, HTTP and packaged-host coverage (44 tests total).

Usage is reported per Agent execution; concurrent identical recipes retain the existing recipe-based view. Real model calls remain deferred.

## 0.7.0-alpha

- Ship one JAR that works as the NeoForge mod or as a standalone Java 21 Agent host. Isolate the bundled Gson dependency in the embedded host JAR.
- Add an optional game-started host and a compact bilingual standalone window. Pair over a private network with a five-minute, one-time code; a new pairing replaces the previous controller.
- Keep provider credentials and executable/workspace configuration on the execution host. Reject remote configuration actions and verify saved machine identity before sending authenticated requests.
- Bind an idle assembler to the selected machine with sneak-use. Persist the target and dispatch intent so reconnects and settings changes cannot silently move or duplicate work.
- Route native crafting progress, context capacity and report books through remote execution. Retry lost archive acknowledgements across reconnects and controller restarts.
- Add real process-tree cancellation through the native crafting cancel action when a single CPU owns the recipe. Refuse ambiguous multi-CPU cancellation.
- Verify private routes bypass system proxies and bound response reads. Test the packaged JAR in a separate JVM without Minecraft on its classpath.

Private-singleplayer Alpha. No built-in public relay, writable tools or interactive app-server controls. Integration tests and screenshots use a CLI fixture; real second-computer/model testing remains outstanding.

## 0.6.0-alpha

- Show public CLI plan steps directly in the native crafting status table, with queued/running/completed/failed states. Fall back to observed action categories when no plan is supplied; never display reasoning or tool arguments/output.
- Show native CPU elapsed time continuously in the Agent heading and left CPU rows.
- Show Agent context capacity in native CPU selection and active CPU rows. Add an optional context-window setting passed to Codex CLI; leave unknown capacity explicit, without guessing from token usage or AE2 storage bytes.
- Persist bounded step metadata across restarts and use compact progress polling. Preserve ordinary CPU rendering and native selection, cancellation and scrolling.
- Add five regression tests for event updates, privacy, bounds, context configuration and restart persistence (24 total).

Screenshots use a CLI subprocess fixture. Real model calls remain deferred. Plan steps are execution metadata; concurrent identical recipes share the current running recipe view.

## 0.5.1-alpha

- Protect completed machine work from the 100-record history limit until its result reaches the world report archive. Persist that protection across restarts; acknowledge it after archival, machine reset or removal.
- Keep stacked products intact when the inventory is full, instead of silently dropping books. Single products replace their own slot, including the offhand.
- Put the result first in report books. Keep execution identity in item data rather than printing an internal UUID in the text.
- Add a real 100+ job history rollover and restart regression test.
- Bundle license and third-party notices in the JAR; verify translations, version and package contents locally and in GitHub Actions.

This is a private-singleplayer Alpha. Real Codex model calls and Windows/macOS game sessions remain unverified. Development screenshots use a CLI subprocess fixture.

## 0.5.0

- Use native AE2 ordering and CPU/item status views; remove the separate task page and Agent status tabs.
- Align encoding tabs to native controls and narrow card editors to the standard inventory width.
- Automatically turn completed products into written report books when taken into the player inventory.
