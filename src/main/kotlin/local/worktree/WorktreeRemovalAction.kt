package local.worktree

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import git4idea.GitVcs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

class WorktreeRemovalAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !it.isDisposed } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<WorktreeRemover>()?.show()
    }
}

@Service(Service.Level.PROJECT)
class WorktreeRemover(private val project: Project, private val scope: CoroutineScope) {
    private val tools = WorktreeTools()

    fun show() {
        scope.launch(Dispatchers.Default) {
            try {
                val repositories = readAction {
                    ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project))
                        .map { it.canonicalPath ?: it.path }.distinct().sorted()
                }
                if (repositories.isEmpty()) {
                    showError("No Git repositories are configured in this project.")
                    return@launch
                }
                val repository = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) null else RemovalRepositoryDialog(project, repositories).let { dialog ->
                        if (dialog.showAndGet()) dialog.repository() else null
                    }
                } ?: return@launch
                val inventory = JsonParser.parseString(tools.worktree_discover(repository)).asJsonObject
                if (inventory.text("status") != "ok") {
                    showError(inventory.failure())
                    return@launch
                }
                val choices = inventory.getAsJsonArray("worktrees").map { it.asJsonObject }
                    .filter { !it.flag("main") && !it.flag("bare") }
                    .map { row ->
                        val directory = requireNotNull(row.text("directory"))
                        val branch = row.text("branch")?.removePrefix("refs/heads/")
                            ?: "detached ${row.text("head")?.take(12).orEmpty()}"
                        val flags = buildList {
                            if (row.flag("open")) add("open — will be skipped")
                            row.text("locked")?.let { add("locked${if (it.isBlank()) "" else ": $it"}") }
                            row.text("prunable")?.let { add("prunable${if (it.isBlank()) "" else ": $it"}") }
                        }
                        RemovalChoice(directory, "$directory — $branch${if (flags.isEmpty()) "" else " [${flags.joinToString(", ")}]"}")
                    }
                if (choices.isEmpty()) {
                    showError("No linked worktrees were found for $repository. The main worktree and bare repositories cannot be removed.")
                    return@launch
                }
                val directories = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) null else RemovalSelectionDialog(project, repository, choices).let { dialog ->
                        if (dialog.showAndGet()) dialog.directories() else null
                    }
                } ?: return@launch
                val preview = JsonParser.parseString(tools.worktree_remove_preview(repository, directories)).asJsonObject
                if (preview.text("status") != "preview") {
                    showError(preview.failure())
                    return@launch
                }
                val plans = preview.getAsJsonArray("results").map { it.asJsonObject }
                val eligible = plans.filter { row ->
                    row.text("status") == "preview" && !row.flag("open") &&
                        row.strings("blockedReasons").isEmpty() && !row.text("confirmation").isNullOrBlank()
                }
                val details = buildString {
                    appendLine("Repository: $repository")
                    appendLine("Remove ${eligible.size} eligible linked worktree(s); skip ${plans.size - eligible.size} blocked/open target(s).")
                    appendLine("Removal deletes the selected worktree directories. Branches are retained. No force removal is used.")
                    appendLine("Open projects are skipped. Close them separately using the IDE's normal Close Project action, then preview again.")
                    appendLine("Unknown upstream does not mean zero unpushed commits; retained local history is checked separately.")
                    plans.forEach { row ->
                        appendLine()
                        appendLine(row.text("directory") ?: "Unknown target")
                        appendLine("Status: ${if (row in eligible) "eligible for removal" else "SKIPPED"}")
                        appendLine("Open project: ${row.flag("open")}")
                        appendLine("Branch: ${row.text("branch") ?: "detached / unavailable"}")
                        appendLine("HEAD: ${row.text("head") ?: "unknown"}")
                        appendLine("Tracked changes:")
                        if (row.has("trackedChanges")) appendItems(row.strings("trackedChanges")) else appendLine("  unknown")
                        appendLine("Untracked files:")
                        if (row.has("untrackedFiles")) appendItems(row.strings("untrackedFiles")) else appendLine("  unknown")
                        appendLine("Unpushed commits: ${row.text("unpushedCommits") ?: "unknown (no upstream or history unavailable)"}")
                        appendLine("History known: ${row.flag("historyKnown")}")
                        appendLine("Commits not retained by persistent refs: ${row.text("unsafeCommits") ?: "unknown"}")
                        appendLine("Protection / blocked reasons:")
                        if (row.has("blockedReasons")) appendItems(row.strings("blockedReasons")) else appendLine("  protection checks unavailable")
                        row.text("reason")?.let { appendLine("  $it") }
                    }
                }.trimEnd()
                val confirmed = withContext(Dispatchers.EDT) {
                    !project.isDisposed && RemovalDetailsDialog(project, "Confirm Worktree Removal", details, eligible.size).showAndGet()
                }
                if (!confirmed || eligible.isEmpty()) return@launch
                val result = JsonParser.parseString(tools.worktree_remove(
                    repositoryDirectory = repository,
                    directories = eligible.map { requireNotNull(it.text("directory")) },
                    confirmations = eligible.map { requireNotNull(it.text("confirmation")) },
                )).asJsonObject
                val results = result.getAsJsonArray("results")
                val message = buildString {
                    appendLine("Repository: $repository")
                    if (results == null) appendLine(result.failure())
                    else results.forEach { element ->
                        val row = element.asJsonObject
                        appendLine("${row.text("directory")}: ${row.failure()}")
                    }
                    plans.filter { it !in eligible }.forEach { row ->
                        appendLine("${row.text("directory")}: skipped — ${row.strings("blockedReasons").joinToString("; ").ifEmpty { row.text("reason") ?: "not eligible" }}")
                    }
                    append("Branches were not deleted. Open projects were not closed.")
                }
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) RemovalDetailsDialog(project, "Worktree Removal Results", message).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) Messages.showWarningDialog(project, message, "Cannot Remove Worktree")
        }
    }
}

private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.flag(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean == true
private fun JsonObject.strings(name: String): List<String> = getAsJsonArray(name)?.map { it.asString }.orEmpty()
private fun JsonObject.failure(): String = listOfNotNull(text("status"), text("reason")).joinToString(": ")
private fun StringBuilder.appendItems(items: List<String>) {
    if (items.isEmpty()) appendLine("  none reported") else items.forEach { appendLine("  $it") }
}

private class RemovalRepositoryDialog(project: Project, repositories: List<String>) : DialogWrapper(project) {
    private val repository = JComboBox(repositories.toTypedArray())

    init {
        title = "Choose Repository for Worktree Removal"
        setOKButtonText("Discover Worktrees…")
        repository.selectedIndex = -1
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Git repository:", repository).panel

    override fun getPreferredFocusedComponent(): JComponent = repository
    override fun doValidate(): ValidationInfo? =
        if (repository.selectedItem == null) ValidationInfo("Choose a Git repository explicitly.", repository) else null

    fun repository(): String = repository.selectedItem as String
}

private data class RemovalChoice(val directory: String, val label: String) {
    override fun toString(): String = label
}

private class RemovalSelectionDialog(project: Project, repository: String, choices: List<RemovalChoice>) : DialogWrapper(project) {
    private val worktrees = JBList(choices).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        visibleRowCount = 10
        accessibleContext.accessibleName = "Linked worktrees to preview for removal"
        clearSelection()
    }
    private val repositoryLabel = JLabel("Repository: $repository")

    init {
        title = "Select Linked Worktrees to Remove"
        setOKButtonText("Preview Removal…")
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(repositoryLabel)
        .addComponent(JLabel("Select one or more linked worktrees. Open projects will be skipped, never closed."))
        .addComponent(JBScrollPane(worktrees).apply { preferredSize = Dimension(760, 260) }).panel

    override fun getPreferredFocusedComponent(): JComponent = worktrees
    override fun doValidate(): ValidationInfo? =
        if (worktrees.isSelectionEmpty) ValidationInfo("Select at least one linked worktree.", worktrees) else null

    fun directories(): List<String> = worktrees.selectedValuesList.map { it.directory }
}

private class RemovalDetailsDialog(project: Project, title: String, details: String, private val removeCount: Int = 0) : DialogWrapper(project) {
    private val content = JTextArea(details).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        accessibleContext.accessibleName = title
    }

    init {
        this.title = title
        setOKButtonText(if (removeCount > 0) "Remove $removeCount Worktree(s)" else "Close")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        preferredSize = Dimension(800, 520)
    }

    override fun createActions(): Array<Action> = if (removeCount > 0) super.createActions() else arrayOf(okAction)
    override fun getPreferredFocusedComponent(): JComponent = content
}
