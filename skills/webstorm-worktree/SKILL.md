---
name: webstorm-worktree
description: Open/reuse WebStorm worktrees, optionally merge windows, close exact projects, or find and run project-scoped IDE actions.
compatibility: macOS; Worktree Lifecycle MCP plugin and connected JetBrains MCP server.
---

# WebStorm worktrees

Use mounted MCP tools and their current schemas. Paths must be existing canonical absolute directories. Projects stay independent; never substitute Attach Project or a dummy project. Installation, configuration changes and restarts require separate authorization.

## Operations

- `worktree_status()` — open-project inventory. Track pre-existing/shared versus task-opened projects in task context.
- `worktree_open(directory, merge=false, targetDirectory?)` — open or reuse; no duplicate to activate a tab. For open+merge use `merge=true`, not two separate calls.
- `worktree_merge(targetDirectory?)` — explicitly authorized **Merge All eligible windows**, not a selected pair. Target defaults to the last-active IDE project captured before opening; explicit target must already be open. Reuse still honors merge. Open+merge without a prior target only opens; standalone merge with nothing to combine is a no-op. A disappearing target leaves the new project open without choosing a replacement.
- `worktree_close(directory)` — close only the exact authorized project, preserving save/process/veto safeguards. At task end ask before closing task-opened projects; disclose pre-existing/shared/unknown ownership and leave them open without informed consent. Never quit the IDE as cleanup.
- `worktree_search_actions(query, directory, limit=50)` — find exact IDs by case-insensitive name/ID substring. Returns name and enabled status in the same project/window context as execution, not the active editor/file context. Includes disabled actions, excludes groups; narrow the query if `truncated`. Availability is a snapshot, not authorization to execute.
- `worktree_action(actionId, directory)` — exact registered action in that project's context. Action-specific authorization applies; disabled/indexing/modal/context failures are not grounds to redirect. `dispatched` does not mean async completion; actions may change focus or show prompts.

Host `projectPath` routes through an **already-open** project; it is not the unopened `directory` or merge `targetDirectory`. Never invent a projectless route. Address opened projects directly without selecting tabs.

## Bootstrap only when needed

Use the plugin when reachable. Otherwise inspect the running IDE: with open projects, diagnose connectivity; if stopped or projectless, launch the real requested directory **once** via `computer.apps.open(directory, {app: "/Users/valerivatchev/Applications/WebStorm.app"})` (discover the app path on other machines).

Retry only read-only mounted status/project discovery: maximum 60 seconds, 2-second cadence, each call bounded by remaining time. If calls cannot be bounded, stop. A process, responding transport or tool list alone is insufficient: the intended project must be routable; missing tools indicate installation/discovery trouble. Indexing/modality can still disable actions. On timeout, report the failed stage and continue feasible work outside the IDE. No guessed endpoints, duplicate launches, UI-scripted lifecycle fallback, forced restarts, lock deletion or security bypasses.

Bootstrap loses the pre-launch default merge target: preserve a known explicit target, otherwise open only and obtain a target before separately merging.

## Outcomes and limitations

Trust tool outcomes; do not add routine verification calls. After a mutation timeout, reconcile state read-only before any retry; if unknowable, report unknown and ask. Transport loss is not successful closure; pending/veto is not success. Verify an asynchronous action's relevant effect before claiming completion.

Fullscreen/Spaces merge is best-effort: later restoration may change selection/geometry even after immediate success. Respect unavailable results; no forced merge, arbitrary sleeps or blind retries. Merge only when requested.
