# User guide

[Home](../README.en.md) · [中文](USAGE.md) | English

## Cards and task patterns

Open an editor from the Pattern Encoding Terminal's right mode bar. Place a blank pattern in the native pattern slot. Name the card before saving it.

- A **Skill card** stores reusable instructions, such as “State the conclusion before explaining the reasons.”
- A **task pattern** stores task text and a snapshot of selected Skills. Install it in a Pattern Provider beside an Agent Assembler.

A new card consumes one blank pattern. Editing a card consumes no pattern. Changes to a Skill card do not change saved task patterns. Select the updated Skill in the task editor before saving the pattern again.

Open Agent settings through the editor's gear button. Click the Agent name at the top to change it. Set the CLI path and workspace, then click **Save and check**. Tasks use read-only tools in that workspace. Configure the CLI's model and authentication before ordering a task.

## Choose an Agent

| Agent | Steering | In-game questions | Tokens |
|---|---|---|---|
| Codex CLI | Yes | Yes | When reported by the CLI |
| DeepSeek Harness | Unavailable | Unavailable | Displays `—` |
| Pi | Yes | Unavailable | When reported by the CLI |
| OpenCode 2 | Yes | Simple text and single-choice forms | When reported by the CLI |

DeepSeek Harness means the `dsh` CLI from `deepseek-ai/deepseek-harness`. OpenCode requires **2.x** from `@opencode/cli`. Official npm packages:

```sh
npm install -g @deepseek-ai/dsh
npm install -g @earendil-works/pi-coding-agent
npm install -g @opencode/cli
```

Default executable names are `dsh`, `pi`, and `opencode`. On Windows, select the launcher from the official npm installation.

The local computer selects one Agent at a time. Accepted local tasks retain their original Agent.

Pi's restricted mode disables extensions, MCP, and native Skills. Only file reading, search, and directory tools are enabled. In-game Skill cards still accompany each task. DeepSeek reasoning is excluded from public steps. Without a plan tool, the list shows tool actions as they occur. OpenCode currently cancels hidden, conditional, numeric, and multiple-choice forms without inventing answers.

## Read crafting status

| Field | Meaning |
|---|---|
| Steps | The Agent's public plan, up to 24 steps. Without a plan, the list displays types of tool actions that occurred |
| Elapsed time | Native CPU time, not estimated remaining time |
| CTX | Total Agent context capacity, not used Tokens or physical CPU bytes |
| Tokens | Input plus output for this execution. Cached input is part of input and does not count twice |

Local settings accept a context capacity. A value of `0` means unknown. The order screen displays the default Agent's capacity. Task reports can update the capacity during execution.

Unknown capacity displays as `?`. Missing usage displays as `—`. A reported zero displays as `0`. The CLI controls usage updates and may report only after completion. The current window retains the last task's usage. A new idle window does not display unrelated history.

AE2 still calculates crafting percentage from item output. Several CPUs with the same pattern prevent exact step identification. This situation also limits additional instructions and cancellation.

## Answer questions and add instructions

Click **Needs input** in the crafting table to select a suggestion or enter text. Submit answers for all questions to continue the task. Returning to the list sends no empty answer. Ordinary text questions do not create answer forms.

The Agent's ten-minute execution timeout pauses while it waits for answers. CPU elapsed time continues.

The **Steering** field accepts up to 4,000 characters per message. Enter inserts a line break. The Send button submits the text. The field retains text until acknowledgment, then displays **Added** and clears it. After ten seconds without acknowledgment, a retry becomes available. A rejection restores editing.

The current window stores a separate draft for each task. CPU changes and question screens retain drafts. Closing the entire crafting window removes drafts. Answer a pending question before sending instructions. Answers and instructions do not grant additional tool permissions.

## Images and books

Click **Images · N** in the crafting table to open the viewer. It supports thumbnails, previous/next controls, fit, 100%, wheel zoom, and drag controls.

A result becomes a native written book when it enters your inventory. The book stores text and links to images on its attachment pages. Native book pages do not embed pictures. Conversion of multiple results needs one empty inventory slot. A full inventory keeps the result item unchanged.

| Property | Limit or scope |
|---|---|
| Formats | PNG and JPEG |
| Images per task | Eight, with duplicate content removed |
| File size | Both the source file and converted PNG must fit within 2 MiB |
| Dimensions | Up to 4096 pixels per side and 4,194,304 pixels in total |
| Sources | PNG/JPEG images reported through supported Agent interfaces, images viewed or generated by Codex, MCP image data, and final references to images inside the workspace |

The mod does not download external image URLs or follow symbolic links. It skips unreadable or excessive source images. It converts accepted images to PNG without source metadata.

The local computer receives and checks every image before it delivers the result. Archived images remain available after a game restart. Copying only the book into another world does not transfer its images.

## Cancellation and failure

- Cancel in this mod's crafting screen to request Agent interruption. Task cancellation acknowledgment precedes CPU order cancellation. Several CPUs with the same pattern can prevent cancellation.
- Failed tasks produce no book. Sneak-use the assembler with an empty hand to return its paper and reset it. Then cancel the old waiting CPU order.
- Other mods that cancel AE2 orders do not automatically stop submitted CLI tasks.
- Removing a working assembler cancels its task.
- Closing the world interrupts local tasks without automatic execution after restart.

## Storage and backups

| Content | Location |
|---|---|
| Local settings | Game instance: `config/applied-intelligence-local.json` |
| Task history | World: `data/appliedintelligence/jobs.json` |
| Report records | World: `data/appliedintelligence_reports.dat` |
| Archived images | World: `data/appliedintelligence/report-images/` |

Preserve the entire world in a backup. Task history retains at most 100 entries. Unarchived results remain protected from removal. Books store text, and the world archive stores images.

The assembler waits when the report archive is full. Claim existing reports to let it continue. Claimed books retain their image indexes in the world archive.
