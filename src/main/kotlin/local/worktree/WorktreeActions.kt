package local.worktree

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import git4idea.GitVcs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverWorktreesAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !it.isDisposed } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<WorktreePicker>()?.showRepositories()
    }
}

@Service(Service.Level.PROJECT)
class WorktreePicker(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val popupLifetime = Disposer.newDisposable("Worktree picker popups")
    private val tools = WorktreeTools()

    private data class Choice(val directory: String, val label: String, val selectable: Boolean = true)

    fun showRepositories() = runTask("Worktree Inventory Unavailable") {
        val repositories = readAction {
            ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project))
                .map { it.path }.distinct().sorted()
        }
        if (repositories.isEmpty()) {
            showError("Worktree Inventory Unavailable", "No Git repositories are configured in this project.")
            return@runTask
        }
        // Always ask, including single-root projects: the repository is explicit, never focus-derived.
        choose("Choose Repository for Worktrees", repositories.map { Choice(it, it) }) { repository ->
            showWorktrees(repository.directory)
        }
    }

    private fun showWorktrees(repository: String) = runTask("Worktree Inventory Unavailable") {
        val inventory = JsonParser.parseString(tools.worktree_discover(repository)).asJsonObject
        if (inventory.text("status") != "ok") {
            showError("Worktree Inventory Unavailable", "$repository\n${failure(inventory)}")
            return@runTask
        }
        val choices = inventory.getAsJsonArray("worktrees").map { entry ->
            val row = entry.asJsonObject
            val directory = requireNotNull(row.text("directory"))
            val branch = row.text("branch")?.removePrefix("refs/heads/")
                ?: if (row.flag("detached")) "detached ${row.text("head")?.take(12).orEmpty()}" else "no branch"
            val flags = buildList {
                if (row.flag("main")) add("main")
                if (row.flag("bare")) add("bare")
                row.text("locked")?.let { add(if (it.isEmpty()) "locked" else "locked: $it") }
                row.text("prunable")?.let { add(if (it.isEmpty()) "prunable" else "prunable: $it") }
                if (row.flag("open")) add("open")
                if (row.flag("current")) add("current")
            }
            Choice(directory, "$directory — $branch${if (flags.isEmpty()) "" else " [${flags.joinToString(", ")}]"}", !row.flag("bare"))
        }
        if (choices.isEmpty()) {
            showError("Worktree Inventory Unavailable", "Git returned no worktrees for $repository.")
            return@runTask
        }
        choose("Worktrees — $repository", choices) { choice ->
            runTask("Cannot Switch Worktree") {
                val result = JsonParser.parseString(tools.worktree_switch(repository, choice.directory)).asJsonObject
                if (result.text("status") != "switched") {
                    showError("Cannot Switch Worktree", "${choice.directory}\n${failure(result)}")
                }
            }
        }
    }

    private suspend fun choose(title: String, choices: List<Choice>, selected: (Choice) -> Unit) {
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val step = object : BaseListPopupStep<Choice>(title, choices) {
                override fun getTextFor(value: Choice): String = value.label
                override fun isSelectable(value: Choice): Boolean = value.selectable
                override fun isSpeedSearchEnabled(): Boolean = true
                override fun onChosen(value: Choice, finalChoice: Boolean): PopupStep<*>? =
                    doFinalStep { selected(value) }
            }
            val popup = JBPopupFactory.getInstance().createListPopup(step)
            Disposer.register(popupLifetime, popup)
            popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun runTask(errorTitle: String, task: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(errorTitle, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun showError(title: String, message: String) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) Messages.showErrorDialog(project, message, title)
        }
    }

    private fun failure(result: JsonObject): String =
        listOfNotNull(result.text("status"), result.text("reason")).joinToString(": ")

    private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.flag(name: String): Boolean = get(name)?.asBoolean == true

    override fun dispose() {
        Disposer.dispose(popupLifetime)
    }
}
