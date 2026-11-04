# Tasks

## 1. Verify the integration path on the installed IDE

- [x] 1.1 Inspect the installed WebStorm build and available JDK/Gradle tooling; select compatible plugin SDK/build versions and a separate development sandbox.
- [x] 1.2 Create the minimal local plugin project using the documented MCP toolset extension; run a real custom read-only probe through MCP and verify discovery by the agent client.
- [x] 1.3 Resolve the registered Merge All action ID and exercise direct Action System invocation with an explicit old-window context. Observe container, selected project, geometry and foreground app without keyboard/menu automation. Stop and report a blocker if explicit targeting cannot be supported; do not replace it with UI scripting.
- [x] 1.4 Check MCP access with Welcome screen/no projects, platform project open/close APIs and their save/veto behavior. Record the supported bootstrap route and any build-specific limitations.

## 2. Implement exact-path project lifecycle and opt-in merge

- [x] 2.1 Implement open/reuse with canonical path identity, independent project settings, clear newly-opened/reused results, and convergence of overlapping requests for one path.
- [x] 2.2 Implement standalone merge and the optional merge flag on open. Resolve explicit target or capture the last active WebStorm project window before opening; handle no target, already-merged state, and target disappearance without focus-based redirection.
- [x] 2.3 Implement exact-path close, including already-closed results, normal IDE cancellation/save/process safeguards, and confirmation of actual project removal rather than dispatch alone.
- [x] 2.4 Add runnable regression coverage for canonical reuse/concurrent opening, opt-in behavior, target selection and close cancellation; exercise target disappearance in the real sandbox.

## 3. Implement explicit-context action execution

- [x] 3.1 Expose exact action ID execution with explicit project/window context through the standard Action System, respecting enabled state, indexing and modal constraints.
- [x] 3.2 Return clear unknown-action, missing-target and unavailable-context outcomes; distinguish dispatch from asynchronous completion and preserve native confirmation dialogs.
- [x] 3.3 Exercise an available action on a non-selected project and an unavailable/context-dependent action. Verify neither is silently redirected to the focused project.

## 4. Add the local skill and bootstrap behavior

- [x] 4.1 Write the local skill describing the five user-facing operations, exact project targeting, opt-in Merge All scope, explicit target override and last-active-before-open default.
- [x] 4.2 Implement only the bootstrap helper needed by the verified setup: launch the real worktree when necessary, use finite read-only MCP readiness checks, distinguish missing custom tools/project readiness/indexing, and avoid duplicate launches or mutation retries.
- [x] 4.3 Include graceful fallback to non-IDE tools and end-of-task confirmation before closing exact task-opened project paths; distinguish pre-existing/shared projects without inventing a persistent ownership service.
- [ ] 4.4 Exercise cold start, already-running/no-project state, reconnect/tool discovery and startup timeout behavior. Record measured timings as observations, not fixed sleeps.
  - Sandbox cold Welcome, project opening, HTTP reconnect/discovery, last-project close and a 60.001-second project-aware readiness timeout were exercised. Installed-app document bootstrap and mounted-client discovery still require authorized installation; raw sandbox Java document launch failed.

## 5. End-to-end validation and local delivery

- [x] 5.1 Exercise open, open + merge, merge, close and arbitrary action through real MCP calls in the sandbox, including multiple project windows, an external foreground app and loss of a captured target.
- [x] 5.2 Exercise fullscreen/Spaces and user focus changes during the operation. Document the observed limitations without promising pixel-perfect or universal action behavior.
  - Late fullscreen restoration and malformed native tabs remain failures, not supported behavior. Concurrent-input stress did not establish successful merge under that timing. See design evidence; no workaround or scope decision has been applied.
- [x] 5.3 Build and run plugin compatibility checks for the selected installed build; remove temporary probes and retain minimal regression checks and required usage/setup instructions.
- [ ] 5.4 With explicit user authorization, install the plugin and local skill into the working setup, verify all intended tools are discoverable and repeat a non-destructive lifecycle smoke. Restore the user's project grouping after testing and report actual evidence and limitations.
  - Plugin 0.1.0 installed with user authorization through Install Plugin from Disk; loaded dynamically without restart. All five MCP tool schemas are mounted; direct status and open/reuse with merge=false passed in the working IDE, preserving its sole project/frame/geometry. Global skill installation and installed-app cold bootstrap remain unperformed.
