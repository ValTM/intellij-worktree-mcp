# Spec Delta

## Purpose

Guide an agent from an available or stopped WebStorm instance to usable worktree-scoped MCP tools, with bounded startup and user-controlled cleanup.

## ADDED Requirements

### Requirement: Bootstrap the real worktree

The skill SHALL use reachable MCP tools when available. When WebStorm is not running, it SHALL launch the IDE directly with the intended worktree, not an arbitrary temporary project. The skill SHALL identify plugin installation, enabled MCP service and configured client connectivity as one-time prerequisites.

#### Scenario: Cold start
- **WHEN** the IDE is stopped and the agent needs tooling for worktree X
- **THEN** the skill launches WebStorm with X and checks MCP readiness for X

#### Scenario: Existing connection
- **WHEN** the necessary MCP tools are already reachable
- **THEN** the skill uses them without restarting the IDE

#### Scenario: Welcome screen only
- **WHEN** the IDE runs without open projects
- **THEN** the skill uses the opener tool if available, otherwise launches the real worktree through the normal launcher, without creating a dummy project

### Requirement: Readiness is bounded and project-specific

The skill SHALL use bounded readiness checks rather than a fixed cold-start sleep. It SHALL distinguish process availability, MCP connectivity, custom-tool discovery, project availability and indexing-dependent action readiness. A transport retry SHALL NOT blindly repeat a potentially completed mutating action.

#### Scenario: Delayed MCP startup
- **WHEN** the process starts before its MCP service is ready
- **THEN** the skill retries read-only readiness checks until success or a finite deadline without spawning additional IDE processes

#### Scenario: MCP responds before indexing completes
- **WHEN** project-scoped MCP queries succeed while indexing is active
- **THEN** the skill treats the connection as ready without claiming that every indexing-dependent operation is available

#### Scenario: Custom tools are missing
- **WHEN** the host responds but the plugin tools are absent
- **THEN** the skill reports the discovery or installation problem and uses only verified client refresh mechanisms, rather than claiming the plugin is ready

### Requirement: Graceful degradation

If IDE tooling remains unavailable, the skill SHALL report the failure and continue with available non-IDE tools where safe. It SHALL report operations without a safe alternative rather than substituting keyboard/menu automation, force-killing WebStorm, or deleting lock files.

#### Scenario: Readiness deadline expires
- **WHEN** MCP cannot be reached within the startup deadline
- **THEN** the agent explains the limitation and continues feasible development through other tools instead of waiting indefinitely

#### Scenario: IDE running but MCP unreachable
- **WHEN** WebStorm is already running and its MCP connection fails
- **THEN** the skill attempts bounded connection diagnosis without repeatedly launching additional instances or silently changing access settings

### Requirement: Ask before task cleanup

At the end of a task, the skill SHALL ask whether to close the projects opened for that task. It SHALL identify exact paths, distinguish reused user projects, and avoid automatically closing projects known to be used by another active task. Explicitly authorized close operations remain available independently of end-of-task cleanup.

#### Scenario: Cleanup accepted
- **WHEN** the user approves closing a named task-opened project not known to be needed by another active task
- **THEN** the agent requests close for that exact path and verifies the result

#### Scenario: Cleanup declined
- **WHEN** the user declines cleanup
- **THEN** the projects remain open

#### Scenario: Reused or shared project
- **WHEN** a project was already open before the task or is known to be used by another active task
- **THEN** the agent does not treat task completion as authorization to close it and discloses that context before any explicit close decision

### Requirement: Explain merge scope and defaults

The skill SHALL describe open, open + merge, merge, close and action as five user-facing operations, with open + merge implemented as an option on open. It SHALL explain that merge is opt-in, includes all eligible project windows, and defaults to the last active WebStorm window captured before the operation, with an explicit target override.

#### Scenario: Ordinary open
- **WHEN** the user asks to make a worktree available without requesting merge
- **THEN** the agent opens it without merging windows

#### Scenario: Authorized merge
- **WHEN** the user requests open + merge or standalone merge
- **THEN** the agent uses the requested scope and target policy rather than treating merge as selective pairwise grouping
