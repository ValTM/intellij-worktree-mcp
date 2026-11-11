package local.worktree

import com.google.gson.JsonParser
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfoRt
import git4idea.GitWorkingTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

class OpenAndMergeWorktreeAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = SystemInfoRt.isMac
        event.presentation.isEnabled = SystemInfoRt.isMac && event.project?.basePath != null &&
            selectedWorktree(event) != null
        val directory = selectedWorktree(event)?.path?.path
        event.presentation.text = if (directory != null && ProjectUtil.findProject(Path.of(directory)) != null)
            "Merge…" else "Open and Merge…"
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!SystemInfoRt.isMac) return
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
    fun openAndMerge(directory: String, target: String) {
        open(directory, target, merge = true) { _, tools -> showWorktrees(tools, directory) }
    }

    fun openReview(directory: String, target: String, branch: String, merge: Boolean) {
        open(directory, target, merge) { opened, _ -> openWorktreeReview(opened, directory, branch) }
    }

    private fun open(
        directory: String,
        target: String,
        merge: Boolean,
        onOpened: suspend (Project, WorktreeTools) -> Unit,
    ) {
        scope.launch(Dispatchers.Default) {
            val failure = try {
                val tools = WorktreeTools()
                val existing = ProjectUtil.findProject(Path.of(directory))
                if (existing != null && (!merge || tools.shareWindowGroup(project, existing))) {
                    withContext(Dispatchers.EDT) {
                        if (!existing.isDisposed) ProjectUtil.focusProjectWindow(existing, true)
                    }
                    onOpened(existing, tools)
                    return@launch
                }
                val verb = if (existing == null) "Open and Merge All" else "Merge All"
                val confirmed = !merge || withContext(Dispatchers.EDT) {
                    !project.isDisposed && Messages.showYesNoDialog(
                        project,
                        "Project: $directory\n\nMerge ALL eligible project windows, including unrelated projects.\n" +
                            "Select this project's tab after merging.",
                        verb, verb, "Cancel", Messages.getQuestionIcon(),
                    ) == Messages.YES
                }
                if (!confirmed) return@launch
                val result = JsonParser.parseString(
                    tools.worktree_open(directory, merge = merge, targetDirectory = target),
                ).asJsonObject
                val status = result.get("status").asString
                val mergeStatus = result.getAsJsonObject("merge")?.get("status")?.asString
                when {
                    status != "opened" && status != "reused" -> "Project opening did not complete: $status"
                    merge && mergeStatus != "merged" && mergeStatus != "not_needed" ->
                        "Project $status, but window merging did not complete: $mergeStatus. The project remains open."
                    else -> {
                        val opened = ProjectUtil.findProject(Path.of(directory))
                        withContext(Dispatchers.EDT) {
                            if (opened != null && !opened.isDisposed) ProjectUtil.focusProjectWindow(opened, true)
                        }
                        if (opened != null) onOpened(opened, tools)
                        if (opened == null) "Project is no longer open: $directory" else null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Opening the worktree or its requested view failed. Inspect the open projects before retrying."
            }
            if (failure != null) withContext(Dispatchers.EDT) {
                if (!project.isDisposed) Messages.showWarningDialog(project, failure, "Open Worktree")
            }
        }
    }

    private suspend fun showWorktrees(tools: WorktreeTools, directory: String) {
        val result = JsonParser.parseString(
            tools.worktree_action("Git.Show.WorkingTrees", directory),
        ).asJsonObject
        check(result.get("status").asString == "dispatched")
    }
}
