package local.worktree

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

internal data class WorktreeIdentity(val directory: String, val branch: String?, val head: String?) {
    constructor(entry: GitWorktree) : this(entry.directory, entry.branch, entry.head)
}

internal data class WorktreeRemovalEligibility(
    val selectable: Boolean,
    val safe: Boolean,
    val cleanupKind: String,
    val blocked: Map<String, String>,
)

internal object WorktreeRemovalPolicy {
    fun blockers(entry: GitWorktree?, open: Boolean, closeProjects: Boolean = false): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        if (entry == null) put("FOREIGN_WORKTREE", "Target is not a registered worktree of this repository")
        if (entry?.main == true) put("MAIN_WORKTREE", "The main worktree cannot be removed")
        if (entry?.bare == true) put("BARE_REPOSITORY", "Bare repositories cannot be removed")
        if (entry?.locked != null) put("LOCKED", "Worktree is locked: ${entry.locked}")
        if (entry?.prunable != null) put("PRUNABLE", "Worktree is prunable: ${entry.prunable}")
        if (open && !closeProjects) put("OPEN_PROJECT", "Project is open or closing; close it separately using normal IDE safeguards")
    }

    fun evaluate(
        entry: GitWorktree?, open: Boolean, localStatusKnown: Boolean, hasLocalChanges: Boolean,
        cleanupKind: String = "NONE", cleanup: Boolean = false, closeProjects: Boolean = false, failure: String? = null,
    ): WorktreeRemovalEligibility {
        val blocks = blockers(entry, open, closeProjects)
        if (!localStatusKnown || failure != null) blocks["STATUS_UNKNOWN"] = failure ?: "Local Git status is unavailable; Refresh first"
        val kind = if (cleanup) cleanupKind else "NONE"
        if (cleanup && kind != "MERGED" && kind != "CLOSED_UNMERGED") {
            blocks["CLEANUP_UNPROVEN"] = "Merge or closed-review evidence is unavailable; Refresh or disable cleanup only"
        }
        return WorktreeRemovalEligibility(blocks.isEmpty(), blocks.isEmpty() && !open && !hasLocalChanges && kind != "CLOSED_UNMERGED", kind, blocks)
    }

    fun overlaps(directory: String, projectDirectories: Collection<String>): Boolean {
        val path = Path.of(directory)
        return projectDirectories.any { Path.of(it).let { project -> path.startsWith(project) || project.startsWith(path) } }
    }
}

/** Advisory selection data only. A selected checkout still requires a fresh destructive preview. */
internal data class WorktreeRemovalCandidate(
    val entry: GitWorktree, val status: WorktreeDisplayStatus?, val open: Boolean, val failure: String? = null,
) {
    fun eligibility(cleanup: Boolean, closeProjects: Boolean = false): WorktreeRemovalEligibility {
        val known = status != null && status.identity == WorktreeIdentity(entry) && status.localStatusKnown && status.error == null
        return WorktreeRemovalPolicy.evaluate(entry, open, known, status?.hasLocalChanges != false,
            status?.cleanupKind ?: "BLOCKED", cleanup, closeProjects, failure ?: status?.error)
    }

    fun plan(cleanup: Boolean, closeProjects: Boolean = false): WorktreeRemovalSelection =
        WorktreeRemovalSelection(entry, open, eligibility(cleanup, closeProjects))

    companion object {
        fun fromSnapshot(entry: GitWorktree, status: WorktreeDisplayStatus?, open: Boolean, failure: String? = null) =
            WorktreeRemovalCandidate(entry, status, open, failure)
    }
}

internal data class WorktreeRemovalSelection(val entry: GitWorktree, val open: Boolean, val eligibility: WorktreeRemovalEligibility) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("directory", entry.directory)
        addProperty("branch", entry.branch)
        addProperty("head", entry.head)
        addProperty("open", open)
        addProperty("cleanupKind", eligibility.cleanupKind)
        addProperty("selectable", eligibility.selectable)
        addProperty("safe", eligibility.safe)
        add("blockedCodes", JsonArray().apply { eligibility.blocked.keys.forEach { add(it) } })
        add("blockedReasons", JsonArray().apply { eligibility.blocked.values.forEach { add(it) } })
    }
}
