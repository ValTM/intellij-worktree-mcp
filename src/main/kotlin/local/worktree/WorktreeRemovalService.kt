package local.worktree

import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.APP)
internal class WorktreeRemovalService(private val scope: CoroutineScope) {
    // Project closure must not cancel the deletion workflow that the user just approved.
    internal fun launch(operation: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Default + ModalityState.nonModal().asContextElement()) { operation() }

    fun closeAndRemove(source: Project, repository: String, selection: WorktreeRemovalDialog.Selection): Job = launch {
        var liveProject = source
        try {
            liveProject = WorktreeOperations().prepareRemovalProjects(repository, selection.closeProjects)
            withContext(Dispatchers.EDT) { showWorktreeLifecycle(liveProject) }
            previewAndRemove(liveProject, requireNotNull(liveProject.basePath), selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is RemovalPreparationException) liveProject = error.project
            withContext(Dispatchers.EDT) {
                if (!liveProject.isDisposed) {
                    showWorktreeLifecycle(liveProject)
                    PreviewDialog(liveProject, "Worktree Removal Stopped",
                        "Deletion stopped; no further worktrees will be deleted. ${error.message ?: error.javaClass.simpleName}\n\n" +
                            "Projects already closed remain closed. Refresh and start a new removal preview to retry.", null).show()
                }
            }
        }
    }

    suspend fun previewAndRemove(project: Project, repository: String, selection: WorktreeRemovalDialog.Selection): String {
        val operations = WorktreeOperations()
        val directories = selection.plans.map { it.get("directory").asString }
        val freshPreview = operations.worktree_remove_preview(
            repository, directories, selection.cleanup, selection.deleteLocalBranches,
            selection.deleteLocalChanges, selection.includeClosedUnmerged,
        )
        val fresh = freshPreview.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
        val ready = fresh.size == directories.size && fresh.all {
            it.get("status")?.asString == "preview" && it.get("confirmation")?.isJsonPrimitive == true
        }
        if (!ready) {
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) RemovalReviewDialog(project, "Deletion Blocked — Review Fresh Preview",
                    removalReviewPanel(repository, fresh, freshPreview.get("reason")?.asString), null).show()
            }
            return "Nothing deleted; review blockers and select the required options in a new preview"
        }
        val result = removeAfterConfirmation(
            confirm = {
                withContext(Dispatchers.EDT) {
                    !project.isDisposed && RemovalReviewDialog(project, "Confirm Worktree Deletion",
                        removalReviewPanel(repository, fresh), "Delete ${fresh.size}").showAndGet()
                }
            },
            remove = {
                operations.worktree_remove(
                    repository, directories, fresh.map { it.get("confirmation").asString }, selection.cleanup,
                    selection.deleteLocalBranches, selection.deleteLocalChanges, selection.includeClosedUnmerged,
                )
            },
            refresh = {
                service<WorktreeInventoryService>().inventory(repository).refreshStatus(project, repository)
            },
        ) ?: return "Deletion cancelled; nothing deleted"
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) RemovalReviewDialog(project, "Worktree Removal Results",
                removalResultsPanel(result), null).show()
        }
        return "Deletion completed; inspect per-directory results."
    }

}

internal suspend fun removeAfterConfirmation(
    confirm: suspend () -> Boolean,
    remove: suspend () -> JsonObject,
    refresh: suspend () -> Unit,
): JsonObject? {
    if (!confirm()) return null
    // Even a failed batch can have removed earlier directories or only the checkout, not its branch.
    var failure: Throwable? = null
    return try {
        remove()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            withContext(NonCancellable) { refresh() }
        } catch (refreshError: Throwable) {
            val removalError = failure
            if (removalError == null) throw refreshError
            if (removalError !== refreshError) removalError.addSuppressed(refreshError)
        }
    }
}

internal data class RemovalReviewItem(
    val directory: String,
    val branch: String?,
    val deleteBranch: Boolean,
    val discardLocalChanges: Boolean,
    val trackedChanges: Int,
    val untrackedFiles: Int,
    val unretainedCommits: Int,
    val blockers: List<String>,
) {
    companion object {
        fun from(plan: JsonObject) = RemovalReviewItem(
            plan.get("directory")?.asString ?: "Unknown directory",
            plan.get("branch")?.takeUnless { it.isJsonNull }?.asString,
            plan.getAsJsonObject("options")?.get("deleteLocalBranch")?.asBoolean == true,
            plan.getAsJsonObject("options")?.get("deleteLocalChanges")?.asBoolean == true,
            plan.getAsJsonArray("trackedChanges")?.size() ?: 0,
            plan.getAsJsonArray("untrackedFiles")?.size() ?: 0,
            plan.getAsJsonArray("unretainedCommitIds")?.size() ?: 0,
            plan.getAsJsonArray("blockedReasons")?.map { it.asString }.orEmpty() +
                listOfNotNull(plan.get("reason")?.takeUnless { it.isJsonNull }?.asString),
        )
    }
}

internal fun removalReviewPanel(repository: String, plans: List<JsonObject>, failure: String? = null): JPanel =
    removalDetailsPanel {
        detail("Repository: $repository")
        detail("Selected directories will be deleted, including ignored contents.")
        detail("Remote branches are never deleted. Projects must be closed in every IDE instance.")
        failure?.let { detail(it) }
        for (plan in plans) {
            val item = RemovalReviewItem.from(plan)
            detail(item.directory, heading = true)
            detail(if (item.branch == null) "Detached HEAD — no local branch to delete"
                else "${if (item.deleteBranch) "Delete" else "Keep"} local branch: ${item.branch}")
            val changes = listOfNotNull(
                "${item.trackedChanges} tracked change(s)".takeIf { item.trackedChanges > 0 },
                "${item.untrackedFiles} untracked file(s)".takeIf { item.untrackedFiles > 0 },
            ).joinToString(", ")
            if (changes.isNotEmpty()) detail(if (item.discardLocalChanges) "Will be lost: $changes" else "Local changes: $changes")
            if (item.unretainedCommits > 0) detail("Warning: ${item.unretainedCommits} commit(s) will no longer have a branch.")
            for (blocker in item.blockers) detail("Cannot remove: $blocker")
        }
    }

internal fun removalResultsPanel(result: JsonObject): JPanel = removalDetailsPanel {
    val results = result.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
    for (item in results.ifEmpty { listOf(result) }) {
        item.get("directory")?.asString?.let { detail(it, heading = true) }
        detail(listOfNotNull(item.get("status")?.asString,
            item.get("reason")?.takeUnless { it.isJsonNull }?.asString).joinToString(": "))
        if (item.get("status")?.asString == "removed") {
            detail(if (item.get("localBranchDeleted")?.asBoolean == true) "Local branch deleted" else "Local branch kept")
        }
        item.getAsJsonArray("blockedReasons")?.forEach { detail(it.asString) }
    }
}

private fun removalDetailsPanel(content: JPanel.() -> Unit): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.empty(4)
    content()
}

private fun JPanel.detail(text: String, heading: Boolean = false) {
    add(JBLabel("<html>${StringUtil.escapeXmlEntities(text)}</html>").apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(if (heading) 12 else 3, 0, 0, 0)
        if (heading) font = font.deriveFont(java.awt.Font.BOLD)
        accessibleContext.accessibleName = text
    })
}

private class RemovalReviewDialog(
    project: Project, title: String, private val content: JComponent, private val confirm: String?,
) : DialogWrapper(project) {
    init {
        this.title = title
        init()
        if (confirm == null) setCancelButtonText("Close") else {
            setOKButtonText(confirm)
            setCancelButtonText("Cancel")
        }
    }
    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        preferredSize = JBUI.size(720, minOf(360, content.preferredSize.height + 24))
    }
    override fun createActions(): Array<javax.swing.Action> =
        if (confirm == null) arrayOf(cancelAction) else super.createActions()
}
