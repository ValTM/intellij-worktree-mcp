# Proposal

## Why

Agents need each working worktree open as a WebStorm project to use project-scoped MCP tooling. Opening, merging and closing projects through keystrokes or menu automation races with the user's work and can replace their window layout.

## What Changes

- Build a local macOS-only WebStorm plugin contributing tools to the existing JetBrains MCP server, rather than introducing another server or authentication mechanism.
- Support five user-facing operations: open, open + merge, merge, close, and arbitrary IDE action by ID. Open + merge is an option on open, sharing its implementation.
- Address projects by exact canonical path and actions by explicit project/window context, without simulated keyboard input, menu automation, or temporary focus changes.
- Make Merge All strictly opt-in. Use an explicit target or capture the last active WebStorm project window before the operation begins, especially before opening a new project.
- Add a local agent skill for cold-start bootstrap into the real target worktree, bounded MCP readiness checks, operation usage, and asking before end-of-task project cleanup.
- Continue development with available non-IDE tools when MCP is unavailable; report any operation that has no safe alternative.

## Capabilities

### New Capabilities

- `ide-project-control`: MCP project lifecycle, optional macOS Merge All, explicit-context action execution and preservation of user workflow.
- `ide-agent-bootstrap`: Agent startup/readiness behavior, tool discovery, graceful degradation and consent-driven cleanup.

### Modified Capabilities

None; this is a new standalone project.

## Impact

- New plugin and skill under `/Users/<USER>/Playground/webstorm-worktree-mcp`.
- Initial support targets the user's installed WebStorm build on macOS. Exact SDK, JDK and Gradle versions must be verified before implementation.
- Reuse the documented `com.intellij.mcpServer.mcpToolset` extension and existing MCP transport/security configuration.
- Merge uses a macOS-specific IDE action whose implementation is internal. Prefer invoking through the public Action System instead of importing internal classes; validate on the installed build.
- No marketplace release, cross-platform window merging, selective two-window merge, custom HTTP server, or generic scripting engine in this change.
