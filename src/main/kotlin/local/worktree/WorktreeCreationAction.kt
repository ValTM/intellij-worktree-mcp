package local.worktree

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.util.ui.FormBuilder
import git4idea.GitVcs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class WorktreeCreationAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !it.isDisposed } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<WorktreeCreator>()?.show()
    }
}

@Service(Service.Level.PROJECT)
class WorktreeCreator(private val project: Project, private val scope: CoroutineScope) {
    private val tools = WorktreeTools()

    fun show() {
        scope.launch(Dispatchers.Default) {
            try {
                val repositories = readAction {
                    ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(GitVcs.getInstance(project))
                        .map { it.canonicalPath ?: it.path }.distinct().sorted()
                }
                if (repositories.isEmpty()) {
                    showResult("Cannot Create Worktree", "No Git repositories are configured in this project.", true)
                    return@launch
                }
                val request = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) null else CreateWorktreeDialog(project, repositories).let { dialog ->
                        if (dialog.showAndGet()) dialog.request() else null
                    }
                } ?: return@launch
                val preview = JsonParser.parseString(tools.worktree_create_preview(
                    repositoryDirectory = request.repository,
                    directory = request.directory,
                    branch = request.branch,
                    startRef = request.startRef,
                    newBranch = request.newBranch,
                )).asJsonObject
                if (preview.text("status") != "preview") {
                    showResult("Cannot Create Worktree", preview.failure(), true)
                    return@launch
                }
                val directory = requireNotNull(preview.text("directory"))
                val confirmation = buildString {
                    appendLine("Repository: ${preview.text("repositoryDirectory")}")
                    appendLine("Destination: $directory")
                    appendLine("${if (request.newBranch) "New branch" else "Existing branch"}: ${preview.text("branch")}")
                    appendLine("Start ref: ${preview.text("startRef")}")
                    appendLine("Resolved HEAD: ${preview.text("head")}")
                    appendLine()
                    appendLine(if (request.open) "Open as an independent project using normal trust safeguards." else "Do not open the new worktree.")
                    if (request.merge) {
                        appendLine("WARNING: Merge ALL eligible project windows, including unrelated projects.")
                        appendLine("Merge target: ${project.basePath}")
                    }
                    append("No dependency installation or post-create hooks will run.")
                }
                val confirmed = withContext(Dispatchers.EDT) {
                    !project.isDisposed && Messages.showYesNoDialog(
                        project, confirmation, "Confirm Create Worktree", "Create Worktree", "Cancel", Messages.getQuestionIcon(),
                    ) == Messages.YES
                }
                if (!confirmed) return@launch
                withContext(Dispatchers.EDT) {
                    Path.of(directory).parent?.let { parent ->
                        PropertiesComponent.getInstance().setValue(parentKey(request.repository), parent.toString())
                    }
                }
                val result = JsonParser.parseString(tools.worktree_create(
                    repositoryDirectory = requireNotNull(preview.text("repositoryDirectory")),
                    directory = directory,
                    branch = requireNotNull(preview.text("branch")),
                    startRef = requireNotNull(preview.text("startRef")),
                    confirmation = requireNotNull(preview.text("confirmation")),
                    newBranch = request.newBranch,
                    open = request.open,
                    merge = request.merge,
                    targetDirectory = if (request.merge) project.basePath else null,
                )).asJsonObject
                val status = result.text("status")
                if (status != "created" && status != "created_open_failed") {
                    val message = if (status == "creation_incomplete") {
                        "${result.failure()}\nDestination: ${result.text("directory")}\n" +
                            "Inspect any remaining directory and branch before retrying."
                    } else result.failure()
                    showResult("Cannot Create Worktree", message, true)
                    return@launch
                }
                val opening = result.getAsJsonObject("opening")
                val merge = opening?.getAsJsonObject("merge")
                val mergeStatus = merge?.text("status")
                val mergeFailed = request.merge && merge != null && mergeStatus != "merged" && mergeStatus != "not_needed"
                val partial = status == "created_open_failed" || mergeFailed
                val message = buildString {
                    appendLine("Worktree created: ${result.text("directory")}")
                    if (status == "created_open_failed") {
                        appendLine("Creation succeeded, but opening did not complete. The worktree remains on disk.")
                        appendLine(opening?.failure() ?: result.failure())
                    } else if (request.open) {
                        appendLine("Project: ${opening?.text("status")}")
                    }
                    if (request.merge && merge != null) appendLine("Merge ALL: ${merge.failure()}")
                }.trimEnd()
                showResult(if (partial) "Worktree Created — Partial Success" else "Worktree Created", message, partial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showResult("Cannot Create Worktree", error.message ?: error.javaClass.simpleName, true)
            }
        }
    }

    private suspend fun showResult(title: String, message: String, warning: Boolean) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) {
                if (warning) Messages.showWarningDialog(project, message, title)
                else Messages.showInfoMessage(project, message, title)
            }
        }
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.failure(): String = listOfNotNull(text("status"), text("reason")).joinToString(": ")
}

private data class CreationRequest(
    val repository: String,
    val directory: String,
    val branch: String,
    val startRef: String,
    val newBranch: Boolean,
    val open: Boolean,
    val merge: Boolean,
)

private fun parentKey(repository: String): String = "local.worktree.creation.parent.$repository"

private class CreateWorktreeDialog(private val project: Project, repositories: List<String>) : DialogWrapper(project) {
    private val repository = JComboBox(repositories.toTypedArray())
    private val destination = JTextField(48)
    private val branch = JTextField(32)
    private val newBranch = JCheckBox("Create a new local branch", false)
    private val startRef = JTextField("HEAD", 32)
    private val open = JCheckBox("Open as an independent project", false)
    private val merge = JCheckBox("Merge ALL eligible project windows (including unrelated projects)", false)

    init {
        title = "Create Worktree"
        setOKButtonText("Preview…")
        repository.selectedIndex = if (repositories.size == 1) 0 else -1
        repository.addActionListener { restoreParent() }
        restoreParent()
        destination.accessibleContext.accessibleName = "Worktree destination"
        startRef.isEnabled = false
        startRef.toolTipText = "Used for new branches only. Existing branches keep their current tip."
        newBranch.addActionListener { startRef.isEnabled = newBranch.isSelected }
        merge.isEnabled = false
        open.addActionListener {
            merge.isEnabled = open.isSelected && project.basePath != null
            if (!merge.isEnabled) merge.isSelected = false
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val browse = JButton("Choose Parent…")
        browse.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Choose Worktree Destination Parent")
            FileChooser.chooseFile(descriptor, project, null)?.let { parent ->
                destination.text = "${parent.path}/"
                destination.requestFocusInWindow()
            }
        }
        val destinationRow = JPanel(BorderLayout(8, 0)).apply {
            add(destination, BorderLayout.CENTER)
            add(browse, BorderLayout.EAST)
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Repository:", repository)
            .addLabeledComponent("Destination (new directory):", destinationRow)
            .addLabeledComponent("Local branch:", branch)
            .addComponent(newBranch)
            .addLabeledComponent("Start ref (new branch only):", startRef)
            .addComponent(open)
            .addComponent(merge)
            .addComponent(JLabel("Merge ALL affects unrelated windows too; it is never selected automatically."))
            .addComponent(JLabel("Creates files only; no dependency installation or post-create hooks."))
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = if (repository.selectedItem == null) repository else destination

    override fun doValidate(): ValidationInfo? = when {
        repository.selectedItem == null -> ValidationInfo("Choose a Git repository explicitly.", repository)
        destination.text.isBlank() -> ValidationInfo("Enter the new worktree's absolute destination.", destination)
        branch.text.isBlank() -> ValidationInfo("Enter a local branch name.", branch)
        newBranch.isSelected && startRef.text.isBlank() -> ValidationInfo("Enter the new branch's start ref.", startRef)
        else -> null
    }

    fun request(): CreationRequest {
        val name = branch.text.trim().removePrefix("refs/heads/")
        return CreationRequest(
            repository.selectedItem as String, destination.text.trim(), name,
            if (newBranch.isSelected) startRef.text.trim() else "refs/heads/$name",
            newBranch.isSelected, open.isSelected, open.isSelected && merge.isSelected,
        )
    }

    private fun restoreParent() {
        val selected = repository.selectedItem as? String
        destination.text = selected?.let { PropertiesComponent.getInstance().getValue(parentKey(it)) }
            ?.let { "$it/" }.orEmpty()
    }
}
