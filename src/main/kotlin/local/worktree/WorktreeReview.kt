package local.worktree

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.GitWorkingTree
import git4idea.repo.GitRepositoryManager
import git4idea.ui.branch.GitBranchReviewPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

internal fun openSelectedWorktreeReview(project: Project, worktree: GitWorkingTree, merge: Boolean) {
    val branch = worktree.currentBranch?.name ?: return
    project.service<WorktreeOpener>().openReview(worktree.path.path, project.basePath ?: return, branch, merge)
}

internal suspend fun openWorktreeReview(project: Project, directory: String, branchName: String) {
    val repository = GitRepositoryManager.getInstance(project).repositories.singleOrNull {
        Path.of(it.root.path).toRealPath() == Path.of(directory).toRealPath()
    }
    val branch = repository?.branches?.findLocalBranch(branchName)
    val review = if (repository != null && branch != null) withTimeoutOrNull(30_000) {
        GitBranchReviewPresenter.getReviewsFlow(repository, setOf(branch)).first()[branch]
    } else null
    withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext
        if (review != null) review.open()
        else Messages.showWarningDialog(
            project,
            "The worktree is open, but its review is unavailable in this project. " +
                "Check the GitHub or GitLab connection in this project and try again.",
            "Open Review in Worktree",
        )
    }
}
