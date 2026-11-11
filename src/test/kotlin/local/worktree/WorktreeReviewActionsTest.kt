package local.worktree

import com.intellij.openapi.application.EDT
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.ExtensionTestUtil
import git4idea.GitStandardLocalBranch
import git4idea.GitWorkingTree
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitBranchReviewPresenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class WorktreeReviewActionsTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testReviewRequiresProviderAndSingleUsableSelection() {
        val ep = ExtensionPointName.create<GitBranchReviewPresenter>("Git4Idea.gitBranchReviewPresenter")
        val provider = object : GitBranchReviewPresenter {
            override fun getReviewsFlow(
                repository: GitRepository,
                branches: Set<GitStandardLocalBranch>,
            ): Flow<Map<GitStandardLocalBranch, GitBranchReviewPresenter.Review?>> = flowOf(emptyMap())
        }
        ExtensionTestUtil.maskExtensions(ep, listOf(provider), testRootDisposable)
        val action = OpenReviewInWorktreeAction()
        val worktree = GitWorkingTree(project.basePath!!, "branch", false, false, false, false, "abc")
        val prunable = GitWorkingTree(project.basePath!!, "branch", false, false, false, true, "abc")
        for ((selection, review, visible) in listOf(
            Triple(listOf(worktree), Any(), true),
            Triple(listOf(worktree), null, false),
            Triple(emptyList(), Any(), false),
            Triple(listOf(worktree, prunable), Any(), false),
            Triple(listOf(prunable), Any(), false),
        )) {
            val event = event(selection, review)
            action.update(event)
            assertEquals(visible, event.presentation.isVisible)
            assertEquals(visible, event.presentation.isEnabled)
        }
    }

    fun testReviewHiddenWithoutProviderEvenWithStaleReviewSelection() {
        val ep = ExtensionPointName.create<GitBranchReviewPresenter>("Git4Idea.gitBranchReviewPresenter")
        val action = OpenReviewInWorktreeAction()
        val worktree = GitWorkingTree(project.basePath!!, "branch", false, false, false, false, "abc")
        ExtensionTestUtil.maskExtensions(ep, emptyList(), testRootDisposable)
        val event = event(listOf(worktree), Any())
        action.update(event)
        assertFalse(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
    }

    fun testReviewResolvesRepositoryInTargetProjectAndOpensOnEdt() = kotlinx.coroutines.runBlocking {
        val directory = project.basePath!!
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory))
        for (arguments in listOf(
            listOf("init", "--initial-branch=review-target"),
            listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "Initial"),
        )) {
            val command = com.intellij.execution.configurations.GeneralCommandLine(listOf("git", "-C", directory) + arguments)
            val result = com.intellij.execution.process.CapturingProcessHandler(command).runProcess()
            assertEquals(result.stderr, 0, result.exitCode)
        }
        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(directory)!!
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.EDT) {
            com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(
                listOf(com.intellij.openapi.vcs.VcsDirectoryMapping(directory, "Git")),
            )
        }
        val manager = git4idea.repo.GitRepositoryManager.getInstance(project)
        val targetRepository = manager.getRepositoryForRoot(root)!!
        var opened = false
        val provider = object : GitBranchReviewPresenter {
            override fun getReviewsFlow(
                repository: GitRepository,
                branches: Set<GitStandardLocalBranch>,
            ): Flow<Map<GitStandardLocalBranch, GitBranchReviewPresenter.Review?>> {
                assertSame(targetRepository, repository)
                assertSame(project, repository.project)
                assertEquals("review-target", branches.single().name)
                return flowOf(mapOf(branches.single() to GitBranchReviewPresenter.Review("Review") {
                    com.intellij.openapi.application.ApplicationManager.getApplication().assertIsDispatchThread()
                    opened = true
                }))
            }
        }
        ExtensionTestUtil.maskExtensions(
            ExtensionPointName.create("Git4Idea.gitBranchReviewPresenter"), listOf(provider), testRootDisposable,
        )
        openWorktreeReview(project, directory, "review-target")
        assertTrue(opened)
    }

    private fun event(worktrees: List<GitWorkingTree>, review: Any?): AnActionEvent =
        AnActionEvent.createFromDataContext("test", null, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                "SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB" -> worktrees
                "SELECTED_REVIEW_IN_WORKING_TREE_TAB" -> review
                else -> null
            }
        })
}
