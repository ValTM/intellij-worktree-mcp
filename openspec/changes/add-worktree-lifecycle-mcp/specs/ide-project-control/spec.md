# Spec Delta

## Purpose

Provide agent-accessible control of independent WebStorm projects and optional macOS window merging without relying on user input focus.

## ADDED Requirements

### Requirement: Open an independent project by path

The system SHALL open the requested existing project directory as an independent project, or reuse the already-open project with the same canonical path. It SHALL distinguish newly opened from reused projects and SHALL NOT attach a worktree to another project's settings.

#### Scenario: Open a new worktree
- **WHEN** a valid unopened worktree is requested without merge
- **THEN** the project becomes addressable by its exact path through MCP, without merging other project windows

#### Scenario: Reuse an open worktree
- **WHEN** the requested canonical project path is already open
- **THEN** the system reuses it without opening a duplicate or selecting its tab merely to make MCP available

#### Scenario: Concurrent requests for one project
- **WHEN** overlapping open requests resolve to the same canonical project path
- **THEN** they converge on one open project rather than duplicate frames

#### Scenario: Invalid directory
- **WHEN** the supplied project directory does not exist
- **THEN** the operation reports the error without opening another project

### Requirement: Explicit opt-in Merge All

The system SHALL expose standalone merge and open with optional merge. Merge SHALL be disabled by default and SHALL be described as merging all eligible project windows, not a selected pair. Close and arbitrary-action requests SHALL NOT implicitly run merge.

#### Scenario: Open and merge
- **WHEN** open is requested with merge enabled
- **THEN** the system opens or reuses the project and attempts Merge All into the resolved container

#### Scenario: Separate merge
- **WHEN** the user requests standalone merge with multiple eligible project windows
- **THEN** the system combines them into the resolved container without opening a project

### Requirement: Stable merge target selection

The system SHALL use an explicitly supplied merge target, or capture the last active WebStorm project window at operation start before opening any new project. Later user focus changes SHALL NOT redirect that operation.

#### Scenario: Another application is foreground
- **WHEN** Firefox is foreground and open with merge has no explicit target
- **THEN** the last active WebStorm project window from before the open is the merge container

#### Scenario: User changes focus during opening
- **WHEN** the target was captured and the user selects another window before opening finishes
- **THEN** merge still addresses the captured target without restoring an obsolete global focus snapshot

#### Scenario: No previous project window
- **WHEN** open with merge starts with no project windows
- **THEN** the project opens normally and no merge is needed

#### Scenario: Nothing to merge
- **WHEN** standalone merge has fewer than two project windows or the projects are already in one tab group
- **THEN** it reports that no merge is needed without changing the layout

#### Scenario: Target disappears
- **WHEN** the selected merge target is closed before merge executes
- **THEN** the system reports that merge was not performed, does not silently choose another target, and retains any project already opened successfully

#### Scenario: Preserve the existing container
- **WHEN** a normal-window open-and-merge targets an existing project window
- **THEN** the existing container and its selected project/editor are preserved without deliberate focus stealing or geometry changes; small platform-imposed adjustments are permitted

### Requirement: Close exactly one project without bypassing safeguards

The system SHALL close the exact requested project without closing unrelated projects or quitting the IDE intentionally. It SHALL preserve normal save, cancellation and running-process safeguards and SHALL report whether the project actually closed.

#### Scenario: Close a project in a tab group
- **WHEN** closing project X is authorized and accepted by the IDE
- **THEN** X is no longer addressable as an open project through MCP while other projects remain open

#### Scenario: Close is cancelled or vetoed
- **WHEN** a user or platform safeguard prevents closing
- **THEN** the project remains open and the operation reports cancellation or blocking rather than force-closing it

#### Scenario: Project is already closed
- **WHEN** close targets a valid path that is not open
- **THEN** it reports already closed without affecting another project

### Requirement: Execute an action by ID in explicit context

The system SHALL support requesting an arbitrary registered IDE action by exact ID with a specified project/window context. It SHALL respect action availability and required context, and SHALL NOT fall back to whichever unrelated project is focused. It SHALL distinguish action dispatch from proven completion when the action is asynchronous.

#### Scenario: Available action
- **WHEN** a registered action is enabled for the supplied context
- **THEN** it is dispatched through the IDE action mechanism using that context, without keyboard or menu automation

#### Scenario: Missing or unavailable action
- **WHEN** the action ID is unknown, its target is closed, or the action is disabled or lacks required context
- **THEN** the request returns a clear failure without executing it in another context

#### Scenario: Action opens a modal
- **WHEN** a valid action requires an IDE confirmation or dialog
- **THEN** the system preserves that interaction and does not claim the action completed merely because it was dispatched

### Requirement: Use the existing MCP integration

The system SHALL expose its operations through the installed IDE's existing MCP server and preserve its access controls. Operations SHALL NOT require a separate HTTP server, additional login system, simulated input, or temporary window activation to resolve their target.

#### Scenario: Custom tools are discovered
- **WHEN** the plugin is installed and the configured MCP client discovers tools
- **THEN** the lifecycle and action operations are available through that existing connection

#### Scenario: Unsupported host behavior
- **WHEN** the installed IDE cannot perform a requested explicit-context operation
- **THEN** it reports the limitation rather than silently substituting UI automation
