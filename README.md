# IntelliJ Worktree MCP

[![Build and release](https://github.com/ValTM/intellij-worktree-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/ValTM/intellij-worktree-mcp/actions/workflows/build.yml)

Manage independent IntelliJ Platform projects through the IDE's bundled MCP server: discover and switch Git worktrees, preview and create a worktree, open or reuse a directory, close an exact project, merge macOS windows, and discover or run IDE actions. For manual work, use the IDE's native Worktrees panel. On macOS, this plugin adds **Open and Merge…** to its toolbar and context menu (**Merge…** for an already-open project). Merging requires confirmation covering **all eligible project windows**, including unrelated projects, then selects the chosen worktree's tab. If that project already shares the invoking project's window group, the action only activates its tab, without confirmation or merging other windows.

## Compatibility

Requires IntelliJ Platform **263.7307.202 through 263.\***, the bundled MCP Server, and Git integration (`Git4Idea`). The build and verification baseline is WebStorm 2026.3 (263.7307.202).

Window merging is macOS-only and uses the IDE's native Merge All Windows action. Fullscreen/Spaces behavior is best-effort: later IDE restoration can change selection or geometry after a successful response. Linux CI checks compilation, platform tests and binary compatibility, not macOS window behavior.

Native creation, discovery, opening and removal remain owned by the IDE. To merge after creation, select the worktree and use **Open and Merge…**; native Create does not automatically merge.

## Install

1. Download the plugin ZIP from [Releases](https://github.com/ValTM/intellij-worktree-mcp/releases). Before a release exists, signed-in GitHub users can download the `plugin` artifact from a successful [workflow run](https://github.com/ValTM/intellij-worktree-mcp/actions/workflows/build.yml). Extract the artifact container, not the plugin ZIP inside it.
2. In the IDE, use **Settings → Plugins → gear → Install Plugin from Disk**, or Find Action → **Install Plugin from Disk**. Select the plugin ZIP and restart if requested.
3. Enable **Settings → Tools → MCP Server** and connect your MCP client using the IDE-provided configuration. Do not disable confirmation safeguards.
4. Open a real project. The tools are supplied through the existing JetBrains MCP connection; there is no separate server to start.

Plugin name: **Worktree Lifecycle MCP**. Stable plugin ID: `com.github.valtm.worktree.lifecycle`.

The optional [agent skill](skills/intellij-worktree/SKILL.md) describes lifecycle, authorization and bootstrap rules. Install it according to your agent's skill conventions; plugin installation does not install the skill.

## Tools

| Tool | Purpose |
| --- | --- |
| `worktree_status()` | List open projects, window state and last-active project. |
| `worktree_discover(repositoryDirectory)` | Discover worktrees of an explicitly selected repository, including unopened and prunable entries. |
| `worktree_switch(repositoryDirectory, directory)` | Open or select a worktree belonging to that repository, without merging windows. |
| `worktree_create_preview(repositoryDirectory, directory, branch, startRef, newBranch=false)` | Preview the exact creation plan and obtain its confirmation token without changing disk or Git. |
| `worktree_create(repositoryDirectory, directory, branch, startRef, confirmation, newBranch=false, open=false, merge=false, targetDirectory?)` | Revalidate and apply an approved creation plan; optionally open and explicitly merge windows. |
| `worktree_remove_preview(repositoryDirectory, directories)` | Preview selected linked worktrees, reporting changes, retained history, upstream uncertainty and blocked targets. |
| `worktree_remove(repositoryDirectory, directories, confirmations)` | Revalidate and remove approved linked worktrees independently, without force or branch deletion. |
| `worktree_open(directory, merge=false, targetDirectory?)` | Open or reuse a canonical directory without duplicate projects; optionally merge in the same call. |
| `worktree_close(directory)` | Close exactly that project, retaining normal save/process/veto safeguards. |
| `worktree_merge(targetDirectory?)` | Merge **all eligible windows**, not just a selected pair. Defaults to the captured last-active project. |
| `worktree_search_actions(query, directory, limit=50, filePath?)` | Find action IDs by case-insensitive name/ID substring; return names and availability. Limit: 1–100; groups excluded. |
| `worktree_action(actionId, directory, filePath?)` | Dispatch an exact registered action in that project's window or explicitly requested editor context. |

Show creation and removal previews to the user before applying their exact confirmation tokens. Creation rejects existing destinations and changed plans; a created worktree remains if optional opening fails. Removal blocks main, locked, unsafe and open-project targets, retains branches, and never closes projects implicitly. Close an authorized project separately through normal IDE safeguards before obtaining a fresh removal preview.

The host's `projectPath` argument routes a call through an **already-open** project. It is distinct from the `directory` being opened and from `targetDirectory`. Use canonical absolute paths and the schemas exposed by your client.

Action search and execution share the same context. Without `filePath`, they use only the project/window, never the active editor. With an absolute or project-relative `filePath`, they use that project's already-open text editor, including its existing caret and selection, without selecting its tab. Files must stay inside the project's canonical directory; missing, binary, outside-project and escaping symlink paths return `invalid_file`. A valid file without an open text editor returns `file_not_open`. Search does not open tabs or execute actions; `dispatched` does not mean asynchronous work has completed. Action-specific authorization still applies.

Unavailable actions include a `reason`: `disabled` or `hidden` describes the IDE presentation, not an inferred missing editor, selection or indexing requirement. `update_failed` / `update_rejected` and `dispatch_failed` / `dispatch_rejected` distinguish platform update and execution outcomes without exposing exception details. `target_unavailable` and `file_context_unavailable` identify context invalidation. A modal dialog returns `unavailable_modal` with `reason: modal_dialog`; dismiss it interactively before retrying. Search reports the same pre-dispatch reasons per action. Availability remains a snapshot; dispatch can still be rejected afterward.

Trust and other IDE prompts remain interactive. After a mutation timeout, inspect state before retrying; a lost connection is not evidence that a close succeeded. Never close pre-existing projects or merge windows without authorization.

## Build and checks

Requirements: **Gradle 9.3.1**, an IDE in the supported **263.7307.202 through 263.\*** build range with the bundled MCP Server, and its bundled **JBR/JDK 25**. CI uses WebStorm 2026.3 as the minimum baseline. The Makefile defaults to `~/Applications/WebStorm.app` and a locally cached Gradle distribution. Override paths when necessary:

```sh
make build test compatibility \
  WEBSTORM=/path/to/WebStorm.app \
  JBR=/path/to/WebStorm.app/Contents/jbr/Contents/Home \
  GRADLE=/path/to/gradle-9.3.1/bin/gradle
```

On Linux, use the extracted IDE directory for `WEBSTORM` and its `jbr` subdirectory for `JBR`; run the command under `xvfb-run --auto-servernum` on a headless machine. The build uses the supplied local SDK, so no build-configuration change is needed for CI.

- `make build`: packages `build/distributions/intellij-worktree-mcp-<version>.zip`.
- `make test`: real IntelliJ platform tests, including close-veto behavior.
- `make compatibility`: JetBrains Plugin Verifier against the supplied IDE.
- `make sandbox`: starts a disposable IDE with the plugin. The sandbox MCP port is `16432`; a cold Welcome screen may require opening a project before the server starts.
- `ruff check src/test/smoke.py`: lint the standalone MCP smoke client.

### Interactive macOS smoke

Run only against the disposable sandbox, **never your working IDE**. Open a host project there, select it once, and choose an existing, trusted, currently unopened directory as `--project`:

```sh
python3 src/test/smoke.py \
  --url http://127.0.0.1:16432/stream \
  --host-project /absolute/open/host \
  --project /absolute/disposable/project
```

The smoke opens and closes the supplied project and merges **all sandbox project windows**. It checks action search, background-file formatting without changing the selected file, invalid-file ownership, unopened-file rejection, bounded results, canonical concurrent reuse, exact closure and normal-window geometry. Trust prompts remain interactive; avoid saved fullscreen state for this normal-window scenario. This test is intentionally not part of headless Linux CI.

## CI and releases

Pushes to `main`, pull requests and manual workflow runs build the ZIP, run platform tests, lint the smoke client and run Plugin Verifier. CI downloads the exact WebStorm SDK and checks its vendor-provided SHA-256 checksum. ZIPs and verification reports are retained as workflow artifacts.

To publish a release, update `version` in `build.gradle.kts` and the plugin's change notes, commit and push, then tag that commit:

```sh
git tag v0.3.0
git push origin v0.3.0
```

Use a new version for subsequent releases. Tags must match the package version. A `v*` tag push runs all checks before a separate, write-enabled job signs and verifies the checked ZIP, publishes a GitHub Release with that ZIP, `SHA256SUMS` and generated release notes, then uploads the same signed ZIP to JetBrains Marketplace's default (Stable) channel. JetBrains approval still applies. Re-running the same tag workflow replaces GitHub release assets, but Marketplace rejects an already uploaded version.

GitHub releases use the built-in Actions token. Configure repository Actions secrets `PUBLISH_TOKEN` (Marketplace token), `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` and `CERTIFICATE_CHAIN` for Marketplace publishing and signing. Supply the complete multiline PEM key/certificate, or Base64 of the complete file; removing PEM line breaks is not a supported encoding. Only the tag-only release job receives these secrets and requests `contents: write`; PR/build jobs remain read-only and receive no signing or publishing secrets.
