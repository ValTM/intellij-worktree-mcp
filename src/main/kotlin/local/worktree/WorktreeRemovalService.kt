package local.worktree

import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
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

    fun remove(source: Project, repository: String, selection: WorktreeRemovalDialog.Selection): Job = launch {
        val operations = WorktreeOperations()
        var liveProject = source
        var liveRepository = repository
        var selected = selection
        var preparedClosures = false
        var removalResult: JsonObject? = null
        try {
            while (true) {
                if (selected.closeProjects.isNotEmpty()) {
                    val approved = withContext(Dispatchers.EDT) {
                        !liveProject.isDisposed && RemovalReviewDialog(liveProject, "Close Projects Before Removal",
                            removalDetailsPanel {
                                detail("Switch to the main worktree and close only these projects:")
                                selected.closeProjects.forEach { detail(it, heading = true) }
                                detail("Save prompts and close vetoes are respected. If a close fails, deletion stops.")
                                detail("Projects already closed will remain closed, even if you cancel deletion.")
                                detail("No worktree is deleted until you approve a fresh preview.")
                            }, "Switch to Main and Close Projects").showAndGet()
                    }
                    if (!approved) break
                    liveProject = operations.prepareRemovalProjects(liveRepository, selected.closeProjects)
                    preparedClosures = true
                    liveRepository = requireNotNull(liveProject.basePath)
                    withContext(Dispatchers.EDT) { showWorktreeLifecycle(liveProject) }
                }

                val directories = selected.plans.map { it.get("directory").asString }
                val preview = operations.worktree_remove_preview(
                    liveRepository, directories, selected.cleanup, selected.deleteLocalBranches,
                    selected.deleteLocalChanges, selected.includeClosedUnmerged,
                )
                val fresh = preview.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
                val ready = directories.isNotEmpty() && fresh.size == directories.size && fresh.all {
                    it.get("status")?.asString == "preview" &&
                        it.get("confirmation")?.takeIf { token -> token.isJsonPrimitive }?.asString?.isNotEmpty() == true
                }
                val failure = if (ready) null else {
                    preview.get("reason")?.takeUnless { it.isJsonNull }?.asString
                        ?: "Deletion is blocked. Go back to edit the selection or cancel."
                }
                val decision = withContext(Dispatchers.EDT) {
                    if (liveProject.isDisposed) DialogWrapper.CANCEL_EXIT_CODE else {
                        val shown = fresh.ifEmpty { directories.map { directory ->
                            JsonObject().apply { addProperty("directory", directory) }
                        } }
                        RemovalReviewDialog(liveProject,
                            if (ready) "Confirm Worktree Deletion" else "Deletion Blocked — Review Fresh Preview",
                            removalReviewPanel(liveRepository, shown, failure, preparedClosures),
                            "Delete ${directories.size}".takeIf { ready }, allowBack = true,
                        ).apply { show() }.exitCode
                    }
                }
                if (decision == DialogWrapper.NEXT_USER_EXIT_CODE) {
                    selected = selectWorktreesForRemoval(liveProject, liveRepository, selected) ?: break
                    continue
                }
                val result = removeAfterConfirmation(
                    confirm = { ready && decision == DialogWrapper.OK_EXIT_CODE },
                    remove = {
                        operations.worktree_remove(
                            liveRepository, directories, fresh.map { it.get("confirmation").asString }, selected.cleanup,
                            selected.deleteLocalBranches, selected.deleteLocalChanges, selected.includeClosedUnmerged,
                        ).also { removalResult = it }
                    },
                    refresh = {
                        service<WorktreeInventoryService>().inventory(liveRepository).refreshStatus(liveProject, liveRepository)
                    },
                ) ?: break
                withContext(Dispatchers.EDT) {
                    val results = result.getAsJsonArray("results")
                    if (result.get("status")?.asString == "completed" &&
                        results != null && results.size() == directories.size &&
                        results.all { it.asJsonObject.get("status")?.asString == "removed" }) {
                        val message = if (directories.size == 1) "Removed 1 worktree." else "Removed ${directories.size} worktrees."
                        notifyRemoval(liveProject, "Worktree Removal Complete", message)
                    } else if (!liveProject.isDisposed) {
                        RemovalReviewDialog(liveProject, "Worktree Removal Results",
                            removalResultsPanel(result, preparedClosures), null).show()
                    }
                }
                return@launch
            }
            if (preparedClosures) withContext(Dispatchers.EDT) {
                notifyRemoval(liveProject, "Worktree Deletion Cancelled", "Nothing was deleted. $PROJECTS_REMAIN_CLOSED")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is RemovalPreparationException) liveProject = error.project
            withContext(Dispatchers.EDT) {
                if (!liveProject.isDisposed) {
                    showWorktreeLifecycle(liveProject)
                    val reason = error.message ?: error.javaClass.simpleName
                    val closed = preparedClosures || error is RemovalPreparationException
                    val content = removalResult?.let { removalResultsPanel(it, closed, reason) } ?: removalDetailsPanel {
                        detail(reason)
                        detail("No further worktrees will be deleted. Refresh and start a new removal preview to retry.")
                        if (closed) detail(PROJECTS_REMAIN_CLOSED)
                    }
                    RemovalReviewDialog(liveProject, "Worktree Removal Stopped", content, null).show()
                }
            }
        }
    }
}

private const val PROJECTS_REMAIN_CLOSED = "Projects already closed remain closed."

private fun notifyRemoval(project: Project, title: String, message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("Worktree Lifecycle")
        .createNotification(title, message, NotificationType.INFORMATION).notify(project.takeUnless { it.isDisposed })
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
    val trackedEntries: List<String>,
    val untrackedEntries: List<String>,
    val unretainedCommits: Int,
    val blockers: List<String>,
) {
    val trackedChanges: Int get() = trackedEntries.size
    val untrackedFiles: Int get() = untrackedEntries.size

    companion object {
        fun from(plan: JsonObject) = RemovalReviewItem(
            plan.get("directory")?.asString ?: "Unknown directory",
            plan.get("branch")?.takeUnless { it.isJsonNull }?.asString,
            plan.getAsJsonObject("options")?.get("deleteLocalBranch")?.asBoolean == true,
            plan.getAsJsonObject("options")?.get("deleteLocalChanges")?.asBoolean == true,
            plan.getAsJsonArray("trackedChanges")?.map { it.asString }.orEmpty(),
            plan.getAsJsonArray("untrackedFiles")?.map { it.asString }.orEmpty(),
            plan.getAsJsonArray("unretainedCommitIds")?.size() ?: 0,
            plan.getAsJsonArray("blockedReasons")?.map { it.asString }.orEmpty() +
                listOfNotNull(plan.get("reason")?.takeUnless { it.isJsonNull }?.asString),
        )
    }
}

internal fun removalReviewPanel(
    repository: String, plans: List<JsonObject>, failure: String? = null, projectsClosed: Boolean = false,
): JPanel =
    removalDetailsPanel {
        detail("Repository: $repository")
        detail("Ignored contents are not listed and will also be deleted.", heading = true)
        detail("Remote branches are never deleted. Projects must be closed in every IDE instance.")
        if (projectsClosed) detail(PROJECTS_REMAIN_CLOSED)
        failure?.let { detail(it, heading = true) }
        for (plan in plans) {
            val item = RemovalReviewItem.from(plan)
            detail(item.directory, heading = true)
            when {
                item.branch != null -> detail("${if (item.deleteBranch) "Delete" else "Keep"} local branch: ${item.branch}")
                plan.has("head") -> detail("Detached HEAD — no local branch to delete")
                else -> detail("Local branch fate unavailable")
            }
            for (blocker in item.blockers) detail("Cannot remove: $blocker")
            if (item.trackedChanges > 0 || item.untrackedFiles > 0) {
                detail(if (item.discardLocalChanges) "Local changes that would be lost:" else "Local changes:")
                if (item.trackedChanges > 0) detail("Tracked entries (${item.trackedChanges}):")
                item.trackedEntries.forEach { detail(it, indent = 16) }
                if (item.untrackedFiles > 0) detail("Untracked files (${item.untrackedFiles}):")
                item.untrackedEntries.forEach { detail(it, indent = 16) }
            }
            when {
                plan.get("historyKnown")?.asBoolean == false -> detail("Unretained commit count unavailable")
                plan.has("unretainedCommitIds") -> {
                    val warning = if (item.unretainedCommits == 0) "" else if (item.branch != null && !item.deleteBranch) {
                        " — kept by the local branch"
                    } else " — would no longer be retained"
                    detail("Commits not retained by another persistent ref: ${item.unretainedCommits}$warning")
                }
            }
        }
    }

internal fun removalResultsPanel(
    result: JsonObject, projectsClosed: Boolean = false, failure: String? = null,
): JPanel = removalDetailsPanel {
    failure?.let { detail(it, heading = true) }
    if (projectsClosed) detail(PROJECTS_REMAIN_CLOSED)
    val results = result.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
    for (item in results.ifEmpty { listOf(result) }) {
        item.get("directory")?.asString?.let { detail(it, heading = true) }
        detail(listOfNotNull(item.get("status")?.asString,
            item.get("reason")?.takeUnless { it.isJsonNull }?.asString).joinToString(": "))
        if (item.get("status")?.asString == "removed") {
            detail(if (item.get("localBranchDeleted")?.asBoolean == true) "Local branch deleted" else "No local branch deleted")
        }
        item.getAsJsonArray("blockedReasons")?.forEach { detail(it.asString) }
    }
}

private fun removalDetailsPanel(content: JPanel.() -> Unit): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.empty(4)
    content()
}

private fun JPanel.detail(text: String, heading: Boolean = false, indent: Int = 0) {
    add(JBLabel().apply {
        putClientProperty("html.disable", true)
        this.text = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(if (heading) 12 else 3, indent, 0, 0)
        if (heading) font = font.deriveFont(java.awt.Font.BOLD)
        accessibleContext.accessibleName = text
    })
}

private class RemovalReviewDialog(
    project: Project, title: String, private val content: JComponent, private val confirm: String?,
    private val allowBack: Boolean = false,
) : DialogWrapper(project) {
    private val backAction = DialogWrapperExitAction("Back", NEXT_USER_EXIT_CODE)

    init {
        this.title = title
        okAction.putValue(DEFAULT_ACTION, null)
        okAction.putValue(MAC_ACTION_ORDER, null)
        cancelAction.putValue(DEFAULT_ACTION, true)
        cancelAction.putValue(FOCUSED_ACTION, true)
        setCancelButtonText(if (confirm != null || allowBack) "Cancel" else "Close")
        confirm?.let { setOKButtonText(it) }
        init()
    }
    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        preferredSize = JBUI.size(720, minOf(360, content.preferredSize.height + 24))
    }
    override fun createActions(): Array<Action> = buildList {
        if (allowBack) add(backAction)
        if (confirm != null) add(okAction)
        add(cancelAction)
    }.toTypedArray()
}
