package local.worktree

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextArea

internal data class WorktreeCreateInput(
    val directory: String = "",
    val branch: String = "",
    val start: String = "HEAD",
    val newBranch: Boolean = true,
    val copySettings: Boolean = false,
)

internal class CreateDialog(
    project: Project,
    repository: String,
    initial: WorktreeCreateInput = WorktreeCreateInput(),
) : DialogWrapper(project) {
    private val repositoryField = JBTextField(repository, 40).apply { isEditable = false; caretPosition = 0 }
    private val branch = JBTextField(initial.branch, 40)
    private val start = JBTextField(initial.start, 40)
    private val directory = JBTextField(initial.directory, 40).apply {
        emptyText.text = "Absolute destination (must not exist)"
    }
    private val newBranch = JRadioButton("New branch", initial.newBranch)
    private val existingBranch = JRadioButton("Existing branch", !initial.newBranch)
    private val copySettings = JBCheckBox("Copy portable IDE settings", initial.copySettings)

    val input: WorktreeCreateInput
        get() = WorktreeCreateInput(directory.text, branch.text, start.text, newBranch.isSelected, copySettings.isSelected)

    init {
        ButtonGroup().apply { add(newBranch); add(existingBranch) }
        title = "Create Worktree"
        init()
        setOKButtonText("Preview…")
    }

    override fun createCenterPanel(): JComponent = createForm(
        "Repository" to repositoryField,
        "Branch mode" to JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(newBranch)
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(existingBranch)
        },
        "Branch" to branch,
        "Starting ref" to start,
        null to plainText("For an existing branch, the ref must resolve to its tip."),
        "Destination" to directory,
        null to copySettings,
        null to plainText(
            "Shared run configurations, inspection profiles and code styles only.\n" +
                "Conflicts and settings with literal source paths are skipped.\n" +
                "No workspace, project identity, module, database, UI or cache state is copied.",
        ),
    )

    override fun doValidate(): ValidationInfo? = when {
        branch.text.isBlank() -> ValidationInfo("Supply an explicit local branch", branch)
        start.text.isBlank() -> ValidationInfo("Supply an explicit starting ref", start)
        directory.text.isBlank() -> ValidationInfo("Supply an absolute destination", directory)
        else -> null
    }

    override fun getPreferredFocusedComponent(): JComponent = branch
}

internal class CreationReviewDialog(
    project: Project,
    private val plan: JsonObject,
    private val copySettings: Boolean,
) : DialogWrapper(project) {
    companion object {
        const val BACK_EXIT_CODE = 2
    }

    private val backAction = object : AbstractAction("Back") {
        override fun actionPerformed(event: ActionEvent) = close(BACK_EXIT_CODE)
    }

    init {
        title = "Review Worktree Creation"
        init()
        setOKButtonText("Create Worktree")
    }

    override fun createCenterPanel(): JComponent {
        fun value(key: String) = plainText(plan.get(key).asString, selectable = true)
        val content = createForm(
            "Repository" to value("repositoryDirectory"),
            "Destination" to value("directory"),
            "Branch" to value("branch"),
            "Branch mode" to plainText(if (plan.get("newBranch").asBoolean) "New branch" else "Existing branch (never reset)"),
            "Starting ref" to value("startRef"),
            "Resolved commit" to value("head"),
            "Portable settings" to plainText(if (copySettings) "Copy after Git creation" else "Do not copy"),
            null to plainText(
                if (copySettings) {
                    "Copies shared run configurations, project inspection profiles and code\n" +
                        "styles without changing payloads or environment values.\n" +
                        "Skips existing or tracked settings, duplicate run type/name pairs and\n" +
                        "settings with literal source-checkout paths. Never copies workspace,\n" +
                        "project identity, modules, database, UI or cache state.\n" +
                        "If copying fails, the created worktree is retained."
                } else {
                    "No IDE settings will be copied."
                },
            ),
            null to plainText(
                "Creates this checkout without opening a project, running installers or\n" +
                    "running Git hooks. If the destination, branch or starting commit changes,\n" +
                    "creation is refused and requires a new preview.",
            ),
        )
        return JBScrollPane(content).apply {
            preferredSize = JBUI.size(700, 360)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(backAction, cancelAction, okAction)
}

private fun createForm(vararg rows: Pair<String?, JComponent>): JPanel = JPanel(GridBagLayout()).apply {
    rows.forEachIndexed { row, (name, component) ->
        val constraints = GridBagConstraints().apply {
            gridy = row
            anchor = GridBagConstraints.BASELINE_LEADING
            insets = JBUI.insets(4)
        }
        if (name != null) {
            component.accessibleContext.accessibleName = name
            constraints.gridx = 0
            add(JBLabel("$name:").apply { labelFor = component }, constraints)
        }
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        add(component, constraints)
    }
}

private fun plainText(text: String, selectable: Boolean = false): JTextArea = JTextArea(text).apply {
    isEditable = false
    isFocusable = selectable
    isOpaque = false
    font = UIUtil.getLabelFont()
    caretPosition = 0
}
