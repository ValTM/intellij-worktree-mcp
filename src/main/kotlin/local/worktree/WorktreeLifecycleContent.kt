package local.worktree

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManagerListener
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.vcs.VcsShowToolWindowTabAction
import git4idea.repo.GitRepositoryManager
import java.util.function.Predicate

internal const val WORKTREE_LIFECYCLE_TAB = "Worktree Lifecycle"

internal object WorktreeLifecycleIcons {
    @JvmField val Lifecycle = IconLoader.getIcon("/icons/worktreeLifecycle.svg", WorktreeLifecycleIcons::class.java)
}

/** Runs before lazy content creation, so even an unopened tab has its icon and cannot be closed. */
class WorktreeLifecyclePreloader : ChangesViewContentProvider.Preloader {
    override fun preloadTabContent(content: Content) {
        content.isCloseable = false
        content.icon = WorktreeLifecycleIcons.Lifecycle
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        content.description = "Independent Git worktrees, sync and review status, and safe lifecycle operations"
    }
}

class WorktreeLifecycleVisibility : Predicate<Project> {
    override fun test(project: Project): Boolean = GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
}

class WorktreeLifecycleContentProvider(private val project: Project) : ChangesViewContentProvider {
    override fun initTabContent(content: Content) {
        WorktreeLifecyclePreloader().preloadTabContent(content)
        val panel = WorktreeLifecyclePanel(project)
        Disposer.register(project, panel)
        content.setDisposer(panel)
        content.component = panel
        content.setPreferredFocusableComponent(panel.table)
    }
}

class ShowWorktreeLifecycleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled = project != null && !project.isDisposed &&
            GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.messageBus.syncPublisher(ChangesViewContentManagerListener.TOPIC).toolWindowMappingChanged()
        VcsShowToolWindowTabAction.activateVcsTab(project, WORKTREE_LIFECYCLE_TAB, false)
    }
}
