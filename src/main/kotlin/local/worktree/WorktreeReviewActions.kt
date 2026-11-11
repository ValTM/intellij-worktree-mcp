package local.worktree

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfoRt
import git4idea.GitWorkingTree

open class OpenReviewInWorktreeAction(private val merge: Boolean = false) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = available(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!available(event)) return
        val project = event.project ?: return
        val worktree = event.getData(selectedWorktrees)?.singleOrNull() ?: return
        openSelectedWorktreeReview(project, worktree, merge)
    }

    private fun available(event: AnActionEvent): Boolean =
        (!merge || SystemInfoRt.isMac) && event.project?.basePath != null &&
            providers.extensionList.isNotEmpty() && event.getData(selectedReview) != null &&
            event.getData(selectedWorktrees)?.singleOrNull()?.isPrunable == false

    companion object {
        private val providers = ExtensionPointName.create<Any>("Git4Idea.gitBranchReviewPresenter")
        private val selectedReview = DataKey.create<Any>("SELECTED_REVIEW_IN_WORKING_TREE_TAB")
        private val selectedWorktrees = DataKey.create<List<GitWorkingTree>>("SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB")
    }
}

class OpenReviewInWorktreeAndMergeAction : OpenReviewInWorktreeAction(merge = true)
