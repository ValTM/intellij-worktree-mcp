# Design

## Context

See proposal.md for motivation. This is a standalone local macOS project, not part of Payhawk. Implementation is now authorized; installation into the user's working IDE still requires explicit approval.

### Evidence from the 2026-11-04 experiments

- Opening `mor-integration-service-FT-48821-ecbo-credit-schema` made its exact projectPath available to WebStorm MCP. Selecting its tab was not required for subsequent MCP requests.
- Merge invoked from the new small window retained that small container. Merge invoked from the existing FT-48816 window retained its window ID, position (0,30), size (2560x1330), selected project and AccountBalance.ts. Firefox remained foreground. No geometry correction was needed in the second experiment.
- File / Close Project closed FT-48821 only; MCP then rejected that path while listing the other projects. Pressing the inactive native tab's AX close control did not close the project.
- A normal IDE quit removed the process and produced `Transport not connected`. Launching the real FT-48816 path through macOS reopened WebStorm. The first MCP probe started at 11.106 seconds and completed successfully at 11.374 seconds. This is one observed upper bound, not an exact readiness threshold or timeout recommendation. The IDE was still analyzing the project.
- The existing agent session reconnected without manual intervention. New custom-tool discovery was not tested. Only the supplied project reopened on cold start; the other two were subsequently restored and merged manually.
- One `webstorm .` launch failed with DirectoryLock/CannotActivate during overlapping user activity. Launcher reliability is not established either way; no process kill or lock-file removal was used.

### Sources and API status

- JetBrains documents the `com.intellij.mcpServer.mcpToolset` extension without Internal/Experimental marking: https://plugins.jetbrains.com/docs/intellij/intellij-community-plugins-extension-point-list.html#comintellijmcpserver
- The official tutorial describes pure-platform toolsets using the same runtime, without MPS dependencies: https://www.jetbrains.com/help/mps/writing-mps-mcp-tools-plugin.html
- Upstream MergeAllWindowsAction reads CONTEXT_COMPONENT, resolves its window and calls native mergeAllWindows: on that window. It also updates tab bars and handles fullscreen details: https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/ui/mac/MergeAllWindowsAction.java
- This merge implementation is Internal. Installed WebStorm 2026.2.3 (WS-262.10968.77) registers `MergeAllWindowsAction`; `javap -c` confirms its actionPerformed reads CONTEXT_COMPONENT and resolves that component's window. This verifies the installed binary's targeting path, but direct plugin invocation still needs runtime proof.
- Sandbox runtime probe: custom `worktree_status`, `worktree_merge` and `worktree_action` were discovered by a disposable HTTP MCP client; `worktree_status` returned real project/window state. Discovery in the mounted agent client remains unverified.
- The uncorrected explicit-context merge gate failed: the requested native window received the call, but another project remained selected. A later isolated native probe reproduced this before JetBrains tab-bar updates: with `src` last active in a separate zoomed window (0,30,2560x1331), native `mergeAllWindows:` on the repository-root window selected `src` immediately. The tab-bar update retained that selection. This localizes the selection mismatch to native merging, not the Action System's project resolution or the later tab-bar rebuild.
- A diagnostic explicit `NSWindowTabGroup.setSelectedWindow:` after the tab-bar update selected the repository-root project and remained selected at the one-second observation. Fresh AX and screenshot verification showed all three projects as tabs, the original root window ID 30486 and its original geometry (580,195,1400x1000); Firefox remained foreground. No geometry correction was necessary. This proves the correction for the exercised normal-window case, not fullscreen, minimized windows, Spaces, or future builds.
- The approved candidate was then exercised through the registered `MergeAllWindowsAction`, without reflection or direct native merging. A native-main-thread queue barrier followed by an EDT callback schedules explicit selection after the action's native merge and tab-bar update. With the separate last-active `src` window at (0,30,2560x1331), the resulting group retained original root window ID 30763, geometry (580,195,1400x1000), and root-project selection; Firefox remained foreground. MCP status, fresh AX and a screenshot confirmed the outcome. Temporary diagnostic logging and the standalone Swift probe have since been removed.
- The disposable HTTP client lost sessions because the host requires an SSE stream: the sandbox log reports `Pending StreamableHttp transport timed out without SSE stream` after 15 seconds. This is not evidence that project opening or merge restarts MCP.
- Window merging is macOS-only; Attach Project is not equivalent and changes project semantics: https://www.jetbrains.com/help/webstorm/opening-reopening-and-closing-projects.html

### Implemented lifecycle and verification

- `WorktreeTools.kt` exposes open/reuse, opt-in open-and-merge, merge, exact close, explicit-context action and read-only status. A single coroutine mutex serializes plugin opens; canonical path comparisons include symlinks. Trust and close-veto checks remain owned by the platform. Native merge outcomes are observed rather than equating dispatch with completion.
- Action updates use their declared EDT/BGT thread, followed by standard Action System dispatch. UI mutations use non-modal, write-safe EDT context. The first close smoke exposed a write-unsafe `ModalityState.any()` context; switching to non-modal dispatch removed that error in subsequent real lifecycle checks.
- Native tab selection can leave the IDE's last-focused frame stale while Firefox remains foreground. A reproduced sequence selected root natively but retained `src` as the IDE's last-focused project, causing the next default merge to target `src`. Default target capture now resolves the selected native tab **within that last-focused frame's group**, without choosing an unrelated window or activating the app. With no prior focused frame it still reports no prior target rather than guessing.
- `python3 src/test/smoke.py --url http://127.0.0.1:16432/stream --host-project /Users/<USER>/Playground/webstorm-worktree-mcp --project /Users/<USER>/Playground/webstorm-worktree-mcp/skills` passed in 2.48 seconds after priming prior IDE activity. It exercises actual MCP discovery, concurrent canonical/symlink reuse, invalid paths, context-dependent rejection, merge/no-op, unchanged target frame/geometry, exact close, and default-target open-and-merge. It requires an unopened disposable project and explicitly merges all sandbox windows.
- `make test` passed the real platform close-veto regression: a veto returns `close_cancelled` and leaves the project open and undisposed. The fixture creates a real directory-based project; no production safeguard is replaced.
- A real concurrent open/close experiment opened `skills` with explicit target `src`, then closed `src` during opening. The result was `opened` plus `merge.status=target_unavailable`; inventory retained `skills` and root, and did not redirect merge. This race is runtime evidence, not yet a deterministic retained regression.
- Explicit-context `HideAllWindows` hid only the non-selected `src` project's tool windows while root's Project pane remained visible. Selecting `src` confirmed the changed surface; `ActivateProjectToolWindow` restored it.
- `make build compatibility` passed for `WS-262.10968.77`; Plugin Verifier reports compatible with two experimental `writeIntentReadAction` usages. `ruff check src/test/smoke.py` and strict OpenSpec validation passed. No future-build compatibility is claimed.

### Remaining native-window limitation

A separate `skills` window was made fullscreen through the standard action. Merge into normal root succeeded: root ID 30959, its (580,195,1400x1000) geometry and selection remained, with Firefox foreground. The IDE's fullscreen flag on the merged source remained true despite its ordinary-sized grouped window; status therefore labels this diagnostic `ideFullscreen`, not authoritative native fullscreen state.

After a longer sequence of fullscreen restoration, repeated close/open, target-disappearance and concurrent native-tab/window input, the sandbox displayed duplicate `skills` native tabs alongside a separate `src` window. Both plugin invocation and the IDE's own Window menu then reported Merge All unavailable/disabled. The precise triggering sequence has not been isolated. The plugin preserves that unavailable result and the newly opened project; it does not force the disabled action, add reflection, or substitute UI automation. Geometry copying alone cannot enable a disabled merge.

The installed-build investigation confirmed that the per-dispatch AppKit-to-EDT barrier orders the IDE tab-bar callback before plugin verification. The failing callback in the sequential smoke belonged to the final open-and-merge; the next close started afterward. A close/merge overlap is not established. Installed Merge All enablement compares frame and native-tab counts rather than identities, explaining the disabled action in the observed equal-count/mismatched-membership state.

A separate minimal fullscreen sequence reproduced late restoration after successful open-and-merge, without concurrent clicks. Installed `updateFullScreenState` discards the `Job` returned by `toggleFullScreen`; frame readiness does not await that restoration. No reliable public completion signal was found. This explains the late fullscreen transition but does not prove the origin of the ghost tab. No speculative mutex, delay, or fullscreen workaround was added.

### Final sandbox verification, 2026-11-04

- Cold launch reached Welcome without an MCP listener; the 120-second launcher readiness bound expired normally. Opening the real repository through Welcome produced a successful project inventory within the first observation at 13.325 seconds. Reconnection and all custom tools were exercised through sandbox HTTP, not the working mounted client.
- The `src` smoke initially failed the selected-target assertion: its saved window state restored fullscreen after merge. Status showed `src` fullscreen and selected while the target retained normal geometry. After explicitly toggling the disposable `src` window back to normal, observing the settled state and closing it normally, the complete smoke passed in 1.84 seconds. This does not erase the fullscreen failure or change plugin behavior.
- Missing explicit merge/action targets returned `target_not_open`. A newly created empty temporary directory triggered the native trust dialog; choosing Don't Open left the project inventory unchanged. No trust permission was granted.
- Closing the last sandbox project returned `closed`, left the process and MCP listener alive, and left no visible window. Tool discovery still succeeded, but project-scoped calls returned an empty-project routing error. A project-aware readiness loop made 30 read-only attempts over 60.001 seconds and stopped without retrying a mutation. Reopening the sandbox's raw Java application produced no window; document launch through that raw executable failed in Launch Services. This is not a test of the installed WebStorm application's document bootstrap.
- Previously successful platform close-veto, explicit-context action, disappearing-target race, normal geometry/foreground and compatibility checks remain valid; production code was unchanged. Concurrent-user-input stress did not establish a successful merge under that timing, so no such guarantee is claimed.

The user accepted fullscreen/Spaces restoration as a best-effort merge limitation and accepted the real target-disappearance check without a deterministic race regression requirement. No additional fullscreen guards, sleeps or synchronization were added. A successful immediate native verification is not a guarantee against later fullscreen restoration; disabled actions remain unavailable.

### Working installation

With explicit user authorization, plugin 0.1.0 was installed from the verified distribution ZIP through the working IDE's Install Plugin from Disk flow. It loaded dynamically in `WS-262.10968.77`; no restart was needed. All five tool schemas became directly mounted in the agent connection. `worktree_status` and `worktree_open` with the already-open path and `merge=false` succeeded (`reused`), with the same sole project, frame identity and (0,33,1728x971) geometry afterward. No existing project was closed or merged. The existing enabled macOS Force Project Tabs plugin was observed and left unchanged; working-environment multiwindow interaction with it has not been tested. Global skill installation and installed-app cold document bootstrap have not been performed.

## Goals / Non-Goals

**Goals:** A small plugin using documented MCP extension and platform APIs; exact project targeting; opt-in merge into the pre-existing container; local skill-driven bootstrap; clear outcomes for partial success and unavailable actions.

**Non-Goals:** Cross-platform support, marketplace distribution, a second HTTP/auth server, arbitrary code evaluation, universal success for every IDE action, selective pairwise merge, or pixel-perfect/caret/scroll restoration. Do not implement a lease service, generic workflow engine, or broad compatibility layer without demonstrated need.

## Decisions

### Existing MCP runtime, not a new transport

Register a Kotlin toolset through com.intellij.mcpServer.mcpToolset and inherit the configured host's transport and access controls. Verify installed build, plugin dependencies and API signatures first. Do not disable host protections. Test new-tool discovery in the actual agent connection, rather than assuming transport reconnect also refreshes tools.

### Five operations, four mutation entry points

User-facing operations are open, open + merge, merge, close, and action. Implement open + merge as an optional flag on open. Tool names and minimal result types are chosen during implementation; the semantic contract is fixed here. Listing/discovery may be supplied by existing host facilities or a small read-only helper if required for safe targeting; it is not another workflow command.

Canonicalize supplied existing project directories and reuse already-open projects. Opening a project never means attaching it to another project's settings. Resolve pending opens before creating another frame for the same canonical path; reuse the platform's concurrency guarantees before adding locking. Close identifies one project and honors platform save/veto/process handling; never force-dispose a project to bypass a prompt.

### Target is captured before side effects

For merge, explicit target wins. Otherwise capture the last active WebStorm project window at operation entry, before opening a new project. Another foreground application does not change this choice. A later focus change must not redirect the operation. If there is no prior target, open + merge simply opens the project; standalone merge returns a no-op if there is nothing to combine. If a captured target disappears, do not select a replacement implicitly: report merge unavailable and retain an already opened project.

Merge All includes other project windows, not only the new one. Expose that scope in tool descriptions and the skill. Never merge after unrelated close/action operations. A project already open is reused; an explicitly requested merge still applies.

### Standard Action System with explicit context

Discover the actual registered merge action ID on the installed build. Invoke through ActionManager/Action System with the captured frame's component context, rather than importing MergeAllWindowsAction, reflecting into private helpers, or synthesizing input. Preserve normal enabled/dumb-mode/modal checks and dispatch on the appropriate IDE thread. The user approved supplementing this action with explicit native tab selection on 2026-11-04: after native merging and the queued IDE tab-bar update, select the captured target through `NSWindowTabGroup.setSelectedWindow:`. Do not activate the application or resolve the target again from focus. Verify the asynchronous ordering and final state; native merge dispatch alone does not establish successful preservation.

The arbitrary-action tool accepts an exact action ID and project/window context. Begin with that bounded context, not a programmable script engine or a guessed editor/selection API. Context-dependent actions may return unavailable; no fallback to a different focused project. An action can intentionally change focus or open a modal, so do not promise background behavior for every arbitrary action. Report dispatched versus completed honestly, especially for async actions.

The plugin itself must not temporarily activate windows to establish context. Avoid restoring old focus snapshots over user activity. Preserve the existing container by design; small native layout adjustments are acceptable. If direct invocation is impossible on the installed build, report the blocker and revisit scope with the user rather than silently substituting menu/keyboard automation.

### Skill handles bootstrap and consent

If the tools are reachable, use them. Otherwise determine whether WebStorm is running. Launch the real target directory only when needed, using the installed macOS launcher route; never create an arbitrary temporary project. When running without a usable MCP connection, attempt bounded reconnection/readiness checks and report the distinction from an IDE launch failure. Do not repeatedly spawn IDEs or delete locks.

Readiness must verify the intended project, not only that the process exists or an HTTP port answers. Use a finite deadline and modest retry cadence selected from measurements; do not encode 11 seconds as a fixed sleep. Tool availability and indexing readiness are different states.

Prerequisites are one-time installation of the plugin, enabling/configuring MCP and client connectivity. Probe the Welcome-screen/no-project case. If the host cannot dispatch our opener there, the skill can open the actual worktree via the launcher as bootstrap, without a dummy project.

At task end, ask before closing projects opened for the task. Do not automatically close a pre-existing user project or one known to be shared with another active task. The skill tracks what it opened in task context; do not introduce persistent ownership bookkeeping unless concurrent-session evidence requires it. User-approved explicit close remains available for any exact project. If confirmation is declined, leave it open.

### Local build and smoke execution

Use a separate IDE sandbox for plugin development where feasible, keeping the user's running instance untouched. Select compatible JDK, Gradle and IntelliJ Platform SDK after inspecting the installed build. Only install into the working IDE with explicit authorization. Prefer public platform APIs and isolate unavoidable action-ID compatibility assumptions in the small invocation path. No claim of compatibility with future IDE releases without checking them.

## Risks / Trade-offs

- Merge All is broad: the user's opt-in must make its all-project-window scope clear.
- The public Action System can invoke an internally implemented action, but its ID, context expectations or behavior can still change. A focused smoke check is required after relevant upgrades; no guarantee of zero maintenance.
- macOS fullscreen/Spaces behavior and modal handling need runtime validation. Do not infer them from the normal-window experiment.
- Generic actions can perform destructive operations. Tool access is not user authorization; the agent must follow its ordinary confirmation rules and preserve host prompts.
- Project close/open and native merging may finish asynchronously. Return observable state or an honest pending/cancelled result instead of treating dispatch as completion.
- Existing MCP reconnection worked, but custom tools may require client refresh. Diagnose this without weakening host security.

## Open Technical Checks

Exact installed build/toolchain; custom tool discovery; MCP dispatch with no open projects; registered merge action ID and explicit-context behavior; supported project open/close APIs and cancellation; full-screen/Spaces effects. These are implementation gates, not missing user product decisions.
