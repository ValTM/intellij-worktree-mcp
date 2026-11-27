package local.worktree

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class WorktreeTools : McpToolset {
    private val gson = Gson()
    private val operations = WorktreeOperations()

    @McpTool
    @McpDescription("List open project paths and window state without selecting or focusing them. Also reports the last active WebStorm project and the installed IDE build.")
    suspend fun worktree_status(): String = gson.toJson(operations.worktree_status())

    @McpTool
    @McpDescription("Discover all Git worktrees of an explicitly selected repository, including unopened and prunable worktrees. Reports canonical paths and IDE open/current state without selecting a window. Git failures return unavailable, never an empty successful inventory.")
    suspend fun worktree_discover(
        @McpDescription("Absolute directory of the explicitly selected Git repository.") repositoryDirectory: String,
    ): String = gson.toJson(operations.worktree_discover(repositoryDirectory))

    @McpTool
    @McpDescription("Explicitly switch to a worktree belonging to the selected repository. Reuses an already-open project or opens an independent project with normal trust safeguards, then selects its window. Does not merge windows.")
    suspend fun worktree_switch(
        @McpDescription("Absolute directory of the explicitly selected Git repository.") repositoryDirectory: String,
        @McpDescription("Absolute worktree directory to open or select.") directory: String,
    ): String = gson.toJson(operations.worktree_switch(repositoryDirectory, directory))

    @McpTool
    @McpDescription("Preview creation of a Git worktree without modifying Git or disk. Supply an explicit repository, destination, branch and starting ref. Returns the resolved plan and a confirmation token; show the plan to the user before applying it.")
    suspend fun worktree_create_preview(
        @McpDescription("Absolute selected repository directory.") repositoryDirectory: String,
        @McpDescription("Absolute destination; must not already exist.") directory: String,
        @McpDescription("Explicit local branch name.") branch: String,
        @McpDescription("Starting commit/ref; for an existing branch it must resolve to that branch's tip.") startRef: String,
        @McpDescription("Create a new branch rather than using an existing local branch.") newBranch: Boolean = false,
    ): String = gson.toJson(operations.worktree_create_preview(repositoryDirectory, directory, branch, startRef, newBranch))

    @McpTool
    @McpDescription("Apply an explicitly approved worktree creation preview. Revalidates the destination, branch and starting commit against the confirmation token. Never overwrites a directory, resets a branch, runs installation commands or invokes Git hooks. Optional opening preserves normal IDE trust safeguards; a created worktree is retained if opening fails or is cancelled.")
    suspend fun worktree_create(
        @McpDescription("Absolute selected repository directory from the preview.") repositoryDirectory: String,
        @McpDescription("Absolute destination from the approved preview.") directory: String,
        @McpDescription("Local branch name from the approved preview.") branch: String,
        @McpDescription("Starting ref from the approved preview.") startRef: String,
        @McpDescription("Token returned by worktree_create_preview after the user approves that exact plan.") confirmation: String,
        @McpDescription("Whether the approved plan creates a new branch.") newBranch: Boolean = false,
        @McpDescription("Open the created directory as an independent IDE project.") open: Boolean = false,
        @McpDescription("Explicit opt-in to merge ALL eligible IDE windows after opening.") merge: Boolean = false,
        @McpDescription("Optional already-open target for the approved Merge All operation.") targetDirectory: String? = null,
    ): String = gson.toJson(operations.worktree_create(repositoryDirectory, directory, branch, startRef, confirmation, newBranch, open, merge, targetDirectory))

    @McpTool
    @McpDescription("Preview selected worktree removals. Unconditionally blocked targets return concise results without file/history or cleanup checks. Cleanup refreshes remote history and classifies merged or closed-unmerged reviews. Branches and local changes are preserved by default. Destructive option lists name individual directories; disclose all files and unretained commits and obtain explicit approval before applying. Open/closing project checks cover this IDE process only; close projects in other IDE processes separately. Never deletes remote branches or closes projects.")
    suspend fun worktree_remove_preview(
        @McpDescription("Explicit absolute repository directory.") repositoryDirectory: String,
        @McpDescription("Absolute worktree directories selected for removal.") directories: List<String>,
        @McpDescription("Require refreshed merge or explicitly opted-in closed-unmerged evidence.") cleanup: Boolean = false,
        @McpDescription("Directories whose local branches may be deleted, only with verified remote absence.") deleteLocalBranches: List<String> = emptyList(),
        @McpDescription("Directories whose disclosed tracked and untracked local changes may be irreversibly deleted.") deleteLocalChanges: List<String> = emptyList(),
        @McpDescription("Directories explicitly opted into closed-unmerged cleanup; never implicitly selected.") includeClosedUnmerged: List<String> = emptyList(),
    ): String = gson.toJson(operations.worktree_remove_preview(repositoryDirectory, directories, cleanup, deleteLocalBranches, deleteLocalChanges, includeClosedUnmerged))

    @McpTool
    @McpDescription("Apply explicitly approved per-worktree previews with exactly the same options and confirmation tokens. Revalidates remote proof, files, refs and open/closing projects in this IDE process; projects in other IDE processes cannot be detected and must be closed separately. Destructive flags require explicit approval of the disclosed loss. Never deletes remote branches or closes IDE projects. Reports each target independently.")
    suspend fun worktree_remove(
        @McpDescription("Explicit repository directory used for the preview.") repositoryDirectory: String,
        @McpDescription("Approved absolute worktree directories.") directories: List<String>,
        @McpDescription("Preview confirmation tokens, in the same order as directories.") confirmations: List<String>,
        @McpDescription("Same cleanup mode as the approved preview.") cleanup: Boolean = false,
        @McpDescription("Same explicitly approved local branch deletion directories.") deleteLocalBranches: List<String> = emptyList(),
        @McpDescription("Same explicitly approved irreversible local-change deletion directories.") deleteLocalChanges: List<String> = emptyList(),
        @McpDescription("Same explicitly approved closed-unmerged directories.") includeClosedUnmerged: List<String> = emptyList(),
    ): String = gson.toJson(operations.worktree_remove(repositoryDirectory, directories, confirmations, cleanup, deleteLocalBranches, deleteLocalChanges, includeClosedUnmerged))

    @McpTool
    @McpDescription("Open an existing directory as an independent project, or reuse its canonical path. Merge is OFF by default. When requested, Merge All includes ALL eligible IDE project windows, not only this project and its target. Captures the previous last-active IDE window before opening; never attaches projects or bypasses trust prompts.")
    suspend fun worktree_open(
        @McpDescription("Existing absolute project directory.") directory: String,
        @McpDescription("Explicit opt-in to Merge All Project Windows after opening or reusing.") merge: Boolean = false,
        @McpDescription("Already-open merge target; omit to capture the last-active IDE project before opening.") targetDirectory: String? = null,
    ): String = gson.toJson(operations.worktree_open(directory, merge, targetDirectory))

    @McpTool
    @McpDescription("Opt-in macOS Merge All Project Windows. Merges ALL eligible project windows, not a selected pair. Uses targetDirectory when supplied, otherwise the last active IDE project window captured at entry. Never switches focus to resolve a target.")
    suspend fun worktree_merge(
        @McpDescription("Existing target project directory; omit for the last active WebStorm project window.") targetDirectory: String? = null,
    ): String = gson.toJson(operations.worktree_merge(targetDirectory))

    @McpTool
    @McpDescription("Close exactly the project at the supplied canonical path using normal IDE save, running-process and cancellation safeguards. Never force-disposes, merges other windows or intentionally quits the IDE. Requires user authorization for this exact project.")
    suspend fun worktree_close(
        @McpDescription("Existing absolute directory of the project authorized for closure.") directory: String,
    ): String = gson.toJson(operations.worktree_close(directory))

    @McpTool
    @McpDescription("Dispatch a registered IDE action by exact ID in the specified project window context. Optional filePath supplies an already-open text editor in that exact project, without selecting it; unopened files return file_not_open. Does not redirect to the focused file or project. Actions may open dialogs or change focus by their own behavior. Dispatched does not imply asynchronous completion; normal confirmation rules still apply.")
    suspend fun worktree_action(
        @McpDescription("Exact registered IDE action ID, not a search abbreviation.") actionId: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
        @McpDescription("Optional absolute or project-relative text file inside this project, already open in a text editor. Uses its existing caret and selection without selecting its tab.") filePath: String? = null,
    ): String = gson.toJson(operations.worktree_action(actionId, directory, filePath))

    @McpTool
    @McpDescription("Find registered IDE actions by case-insensitive name or ID substring without executing them. Optional filePath uses an already-open text editor in the exact requested project, as worktree_action does. Never opens or selects editor tabs or redirects to the focused file. Without filePath, uses only project/window context. Action groups are excluded.")
    suspend fun worktree_search_actions(
        @McpDescription("Non-empty substring of the action name or ID.") query: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
        @McpDescription("Maximum results, from 1 to 100.") limit: Int = 50,
        @McpDescription("Optional absolute or project-relative text file inside this project, already open in a text editor. Never opens or selects a tab.") filePath: String? = null,
    ): String = gson.toJson(operations.worktree_search_actions(query, directory, limit, filePath))
}
