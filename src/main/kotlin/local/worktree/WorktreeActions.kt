package local.worktree

import com.google.gson.JsonObject
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import git4idea.GitWorkingTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

class OpenAndMergeWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = SystemInfoRt.isMac
        event.presentation.isEnabled = SystemInfoRt.isMac && event.project?.basePath != null &&
            !WorktreeOperations.lifecycleBusy && selectedWorktree(event) != null
        val directory = selectedWorktree(event)?.path?.path
        event.presentation.text = if (directory != null && ProjectUtil.findProject(Path.of(directory)) != null)
            "Merge All…" else "Open and Merge All…"
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!SystemInfoRt.isMac || WorktreeOperations.lifecycleBusy) return
        val project = event.project ?: return
        val target = project.basePath ?: return
        val directory = selectedWorktree(event)?.path?.path ?: return
        project.service<WorktreeOpener>().openAndMerge(directory, target)
    }

    private fun selectedWorktree(event: AnActionEvent): GitWorkingTree? =
        event.getData(selectedWorktrees)?.singleOrNull()?.takeUnless { it.isPrunable }

    companion object {
        private val selectedWorktrees = DataKey.create<List<GitWorkingTree>>("SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB")
    }
}

@Service(Service.Level.PROJECT)
class WorktreeOpener(private val project: Project, private val scope: CoroutineScope) {
    fun openAndMerge(directory: String, target: String) = launchOpen(directory, target, merge = true)

    fun openReview(directory: String, target: String, branch: String, merge: Boolean) =
        launchOpen(directory, target, merge, branch)

    private fun launchOpen(directory: String, target: String, merge: Boolean, branch: String? = null) {
        if (WorktreeOperations.lifecycleBusy) return
        scope.launch(Dispatchers.Default) {
            try {
                openAndSelect(directory, target, merge, branch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) Messages.showWarningDialog(
                        project, error.message ?: "Opening the worktree or its requested view failed. Inspect open projects before retrying.",
                        "Open Worktree",
                    )
                }
            }
        }
    }

    internal suspend fun openAndSelect(
        directory: String,
        target: String,
        merge: Boolean = false,
        branch: String? = null,
        repositoryDirectory: String? = null,
    ): JsonObject? {
        val operations = WorktreeOperations()
        if (merge) {
            val verb = if (operations.windowStates(project).containsKey(Path.of(directory).toRealPath().toString()))
                "Merge All" else "Open and Merge All"
            val confirmed = withContext(Dispatchers.EDT) {
                !project.isDisposed && Messages.showYesNoDialog(
                    project,
                    "Project: $directory\n\nMerge ALL eligible project windows, including unrelated projects.\n" +
                        "Select this project's tab after merging.",
                    verb, verb, "Cancel", Messages.getQuestionIcon(),
                ) == Messages.YES
            }
            if (!confirmed) return null
        }
        val result = if (repositoryDirectory != null && !merge) operations.worktree_switch(repositoryDirectory, directory)
            else operations.worktree_open(directory, merge = merge, targetDirectory = target)
        val status = result.get("status")?.asString
        check(status == "opened" || status == "reused" || status == "switched") {
            "Project opening did not complete: $status${result.get("reason")?.takeUnless { it.isJsonNull }?.asString?.let { ": $it" }.orEmpty()}"
        }
        val mergeStatus = result.getAsJsonObject("merge")?.get("status")?.asString
        check(!merge || mergeStatus == "merged" || mergeStatus == "not_needed") {
            "Project $status, but window merging did not complete: $mergeStatus. The project remains open."
        }
        val opened = withContext(Dispatchers.EDT) { ProjectUtil.findProject(Path.of(directory).toRealPath()) }
        check(opened != null && !opened.isDisposed) { "Project is no longer open: $directory" }
        if (branch != null) openWorktreeReview(opened, directory, branch)
        else check(withTimeoutOrNull(5_000) {
            while (true) {
                val selected = withContext(Dispatchers.EDT) {
                    check(!opened.isDisposed) { "Project is no longer open: $directory" }
                    showWorktreeLifecycle(opened)
                    (ChangesViewContentManager.getInstance(opened) as ChangesViewContentManager)
                        .isContentSelected(WORKTREE_LIFECYCLE_TAB)
                }
                if (selected) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true) { "Project is open, but the Worktree Lifecycle tab did not become available." }
        return result
    }
}
