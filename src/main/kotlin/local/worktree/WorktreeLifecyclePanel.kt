package local.worktree

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitWorkingTree
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

internal class WorktreeLifecyclePanel(private val project: Project) : JBPanel<WorktreeLifecyclePanel>(BorderLayout()), Disposable, UiDataProvider {
    private data class Row(val entry: GitWorktree, var status: WorktreeDisplayStatus? = null, var failure: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val tools = WorktreeTools()
    private val rows = mutableListOf<Row>()
    private var busy = false
    private var refreshingDirectory: String? = null
    private val repositories = ComboBox<String>()
    private val message = JBLabel("Loading worktrees…")
    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("Worktree / branch", "Sync", "Review", "State", "")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 4 && !busy
        override fun getValueAt(row: Int, column: Int): String {
            val value = rows[row]
            return when (column) {
                0 -> value.entry.branch ?: if (value.entry.bare) "Bare repository" else "Detached HEAD"
                1 -> if (value.failure != null) { if (value.status == null) "Unavailable" else "Refresh failed" } else value.status?.syncLabel ?: "Loading…"
                2 -> value.status?.reviewLabel ?: if (value.failure != null) "Unknown" else "Loading…"
                3 -> state(value.entry)
                else -> if (busy && (refreshingDirectory == null || refreshingDirectory == value.entry.directory)) "Refreshing…" else "Refresh"
            }
        }
    }
    internal val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rows.getOrNull(rowAtPoint(event.point)) ?: return null
            val details = when (columnAtPoint(event.point)) {
                1 -> listOfNotNull(row.failure, row.status?.syncDetails, row.status?.error).joinToString("\n")
                2 -> listOfNotNull(row.failure, row.status?.reviewDetails).joinToString("\n")
                4 -> "Fetch remotes and refresh sync, review, and worktree inventory. No pull, merge, push, or deletion."
                else -> listOfNotNull(row.entry.directory, "HEAD: ${row.entry.head ?: "unknown"}",
                    row.entry.locked?.let { "Locked: $it" }, row.entry.prunable?.let { "Prunable: $it" }).joinToString("\n")
            }
            return "<html><div style='width:420px'>${StringUtil.escapeXmlEntities(details).replace("\n", "<br>")}</div></html>"
        }
    }

    init {
        border = JBUI.Borders.empty(4)
        repositories.accessibleContext.accessibleName = "Repository"
        repositories.toolTipText = "Select the Git repository whose linked worktrees are shown"
        repositories.addActionListener { if (!busy) reload(fetch = false) }
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.rowHeight = JBUI.scale(38)
        table.setShowGrid(false)
        table.setStriped(true)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.accessibleContext.accessibleName = "Worktree Lifecycle"
        table.accessibleContext.accessibleDescription = "Select worktrees for lifecycle actions. Enter opens; F5 refreshes the selected row. Refresh buttons also support keyboard activation."
        table.emptyText.text = "No worktrees available. Refresh to retry."
        table.setDefaultRenderer(Any::class.java, RowRenderer())
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(260)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(140)
        table.columnModel.getColumn(4).apply {
            preferredWidth = JBUI.scale(110)
            cellRenderer = RefreshRenderer()
            cellEditor = RefreshEditor()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1 && table.columnAtPoint(event.point) != 4 && !busy) openSelected()
            }
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "worktree.open")
        table.actionMap.put("worktree.open", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (table.selectedColumn == 4 && table.selectedRow >= 0) refreshRow(table.selectedRow) else if (!busy) openSelected()
            }
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "worktree.refresh")
        table.actionMap.put("worktree.refresh", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) { if (!busy) refreshRow(table.selectedRow) }
        })
        val actions = actions()
        val toolbar = ActionManager.getInstance().createActionToolbar("WorktreeLifecycle.Toolbar", actions, true)
        toolbar.targetComponent = table
        val header = JPanel(BorderLayout()).apply {
            add(JBLabel("Repository: ").apply { labelFor = repositories }, BorderLayout.WEST)
            add(repositories, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(message.apply { border = JBUI.Borders.empty(5); accessibleContext.accessibleName = "Worktree operation status" }, BorderLayout.SOUTH)
        PopupHandler.installRowSelectionTablePopup(table, actions, "WorktreeLifecycle.Popup")
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
            scope.launch {
                if (!busy && repository.root.path == this@WorktreeLifecyclePanel.repository()) reload(fetch = false)
            }
        })
        updateRepositories()
        reload(fetch = false)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[CommonDataKeys.PROJECT] = project
        if (busy) return
        val repository = GitRepositoryManager.getInstance(project).repositories.singleOrNull { it.root.path == repository() } ?: return
        sink[DataKey.create<GitRepository>("CURRENT_REPOSITORY_IN_WORKING_TREE_TAB")] = repository
        // The native lock handler accepts one worktree even when its update permits several.
        sink[DataKey.create<List<GitWorkingTree>>("SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB")] = selected().takeIf { it.size == 1 }.orEmpty().map { row ->
            val entry = row.entry
            GitWorkingTree(entry.directory, entry.branch, entry.main, entry.directory == repository.root.path,
                entry.locked != null, entry.prunable != null, entry.head)
        }
    }

    override fun dispose() { scope.cancel() }

    private fun repository(): String? = repositories.selectedItem as? String
    private fun selected(): List<Row> = table.selectedRows.asSequence().mapNotNull { rows.getOrNull(it) }.toList()
    private fun one(): Row? = selected().singleOrNull()
    private fun usable(): Boolean = one()?.entry?.let { !it.bare && it.prunable == null } == true
    private fun reviewAvailable(): Boolean = usable() && one()?.entry?.branch != null &&
        ExtensionPointName.create<Any>("Git4Idea.gitBranchReviewPresenter").extensionList.isNotEmpty()

    private fun actions(): DefaultActionGroup = DefaultActionGroup().apply {
        fun action(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) {
            add(object : DumbAwareAction(text, description, icon) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = !busy && enabled()
                    if (text == "Refresh") {
                        event.presentation.text = if (busy) "Refreshing…" else "Refresh"
                        event.presentation.description = if (busy) message.text else description
                    }
                }
                override fun actionPerformed(event: AnActionEvent) { if (!busy && enabled()) perform() }
            })
        }
        action("Create Worktree…", "Preview a new or existing branch checkout before creating it", AllIcons.General.Add, { repository() != null }, ::create)
        action("Open Worktree", "Open or reuse the selected independent project without merging windows", AllIcons.Actions.MenuOpen, ::usable, ::openSelected)
        action("Switch to Worktree", "Open or reuse and select the exact worktree project window", AllIcons.Actions.Forward, ::usable) { openSelected(switch = true) }
        if (SystemInfoRt.isMac) action("Open and Merge All…", "Confirm merging ALL eligible project windows, including unrelated projects", AllIcons.Actions.MoveToWindow, ::usable) {
            val row = one() ?: return@action
            project.service<WorktreeOpener>().openAndMerge(row.entry.directory, project.basePath ?: return@action)
        }
        action("Open Review in Worktree", "Open the worktree and its native review view", AllIcons.Vcs.Branch, ::reviewAvailable) { openReview(merge = false) }
        if (SystemInfoRt.isMac) action("Open Review and Merge All…", "Open the native review and confirm merging ALL eligible project windows", AllIcons.Actions.MoveToWindow, ::reviewAvailable) { openReview(merge = true) }
        addSeparator()
        add(ActionUtil.wrap("Git.WorkingTrees.ToggleLock"))
        add(ActionUtil.wrap("Git.WorkingTrees.Prune"))
        action("Remove Worktrees…", "Preview selected directories and optional local branch or local change deletion", AllIcons.General.Delete, { selected().isNotEmpty() }, { remove(cleanup = false) })
        action("Cleanup Worktrees…", "Select safe worktrees or explicitly opt into closed-unmerged and local data deletion", AllIcons.Actions.GC, { repository() != null && rows.isNotEmpty() }, { remove(cleanup = true) })
        addSeparator()
        action("Refresh", "Fetch remotes and refresh inventory, sync and provider review data", AllIcons.Actions.Refresh, { true }) {
            updateRepositories()
            reload(fetch = true)
        }
    }

    private fun updateRepositories() {
        val selected = repository()
        val paths = GitRepositoryManager.getInstance(project).repositories.map { it.root.path }.sorted()
        busy = true
        try {
            repositories.removeAllItems()
            paths.forEach(repositories::addItem)
            if (selected in paths) repositories.selectedItem = selected
        } finally { busy = false }
    }

    private fun state(entry: GitWorktree): String = buildList {
        if (entry.main) add("Main")
        if (entry.bare) add("Bare")
        if (entry.detached) add("Detached")
        if (entry.locked != null) add("Locked")
        if (entry.prunable != null) add("Prunable")
        val opened = runCatching { ProjectUtil.findProject(Path.of(entry.directory)) }.getOrNull()
        if (opened === project) add("This project") else if (opened != null) add("Open")
    }.joinToString(" · ").ifEmpty { "Linked" }

    private fun task(title: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        repositories.isEnabled = false
        message.text = title
        table.setPaintBusy(true)
        if (rows.isNotEmpty()) model.fireTableRowsUpdated(0, rows.lastIndex)
        scope.launch {
            try { withContext(Dispatchers.Default) { operation() } }
            catch (cancelled: CancellationException) { message.text = "Operation cancelled"; throw cancelled }
            catch (cancelled: ProcessCanceledException) { message.text = "Operation cancelled"; throw cancelled }
            catch (error: Exception) { message.text = "$title failed: ${error.message ?: error.javaClass.simpleName}"; message.toolTipText = message.text }
            finally {
                busy = false
                refreshingDirectory = null
                repositories.isEnabled = true
                table.setPaintBusy(false)
                if (rows.isNotEmpty()) model.fireTableRowsUpdated(0, rows.lastIndex)
            }
        }
    }

    private fun refreshRow(index: Int) {
        if (busy) return
        refreshingDirectory = rows.getOrNull(index)?.entry?.directory ?: return
        reload(fetch = true)
    }

    private fun reload(fetch: Boolean) {
        val repository = repository()
        if (repository == null) {
            rows.clear(); model.fireTableDataChanged()
            message.text = "No Git repositories mapped in this project. Refresh after configuring Git."
            return
        }
        val selection = selected().map { it.entry.directory }.toSet()
        if (fetch) GitRepositoryManager.getInstance(project).repositories.singleOrNull { it.root.path == repository }
            ?.workingTreeHolder?.scheduleReload()
        task(if (fetch) "Refreshing remote and review status…" else "Reading local worktrees…") {
            var fetchFailure: String? = null
            if (fetch) try { WorktreeStatusReader.refresh(project, repository) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cancelled: ProcessCanceledException) { throw cancelled }
            catch (error: Exception) { fetchFailure = error.message ?: "Remote refresh failed" }
            val entries = GitWorktrees.discover(repository)
            withContext(Dispatchers.EDT) {
                rows.clear(); rows.addAll(entries.map(::Row)); model.fireTableDataChanged()
                rows.forEachIndexed { index, row -> if (row.entry.directory in selection) table.addRowSelectionInterval(index, index) }
            }
            for ((index, entry) in entries.withIndex()) {
                val status = try { WorktreeStatusReader.read(project, repository, entry) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cancelled: ProcessCanceledException) { throw cancelled }
                catch (error: Exception) {
                    withContext(Dispatchers.EDT) { rows[index].failure = error.message ?: "Status unavailable"; model.fireTableRowsUpdated(index, index) }
                    continue
                }
                withContext(Dispatchers.EDT) { rows[index].status = status; rows[index].failure = fetchFailure; model.fireTableRowsUpdated(index, index) }
            }
            withContext(Dispatchers.EDT) {
                message.text = fetchFailure?.let { "Refresh failed: $it. Showing local/cached status; Refresh retries." }
                    ?: "${entries.size} worktrees · ${if (fetch) "Refreshed" else "Local/cached status; Refresh fetches remotes"} · Branch deletion requires explicit opt-in"
                message.toolTipText = message.text
            }
        }
    }

    private fun openSelected(switch: Boolean = false) {
        val entry = one()?.entry ?: return
        val repository = repository() ?: return
        task(if (switch) "Switching worktree…" else "Opening worktree…") {
            val result = json(if (switch) tools.worktree_switch(repository, entry.directory) else tools.worktree_open(entry.directory))
            withContext(Dispatchers.EDT) { message.text = outcome(result) }
        }
    }

    private fun openReview(merge: Boolean) {
        val entry = one()?.entry ?: return
        project.service<WorktreeOpener>().openReview(entry.directory, project.basePath ?: return, entry.branch ?: return, merge)
    }

    private fun create() {
        val repository = repository() ?: return
        val dialog = CreateDialog(project, repository)
        if (!dialog.showAndGet()) return
        val directory = dialog.directory.text.trim()
        val branch = dialog.branch.text.trim()
        val start = dialog.start.text.trim()
        val newBranch = dialog.newBranch.isSelected
        task("Creating worktree…") {
            val preview = json(tools.worktree_create_preview(repository, directory, branch, start, newBranch))
            require(preview.get("status")?.asString == "preview") { outcome(preview) }
            if (!confirm("Create Worktree", creationText(preview), "Create Worktree")) {
                withContext(Dispatchers.EDT) { message.text = "Creation cancelled; nothing changed" }
                return@task
            }
            val result = json(tools.worktree_create(repository, directory, branch, start, preview.get("confirmation").asString, newBranch))
            withContext(Dispatchers.EDT) { message.text = outcome(result) }
            loadInventory(repository)
        }
    }

    private suspend fun loadInventory(repository: String) {
        val entries = GitWorktrees.discover(repository)
        val loaded = entries.map { entry ->
            try { Row(entry, WorktreeStatusReader.read(project, repository, entry)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cancelled: ProcessCanceledException) { throw cancelled }
            catch (error: Exception) { Row(entry, failure = error.message ?: "Status unavailable") }
        }
        withContext(Dispatchers.EDT) { rows.clear(); rows.addAll(loaded); model.fireTableDataChanged() }
    }

    private fun remove(cleanup: Boolean) {
        val repository = repository() ?: return
        val selectedDirectories = selected().map { it.entry.directory }
        task(if (cleanup) "Preparing cleanup preview…" else "Preparing removal preview…") {
            val directories = if (cleanup) GitWorktrees.discover(repository).map { it.directory } else selectedDirectories
            val preview = json(tools.worktree_remove_preview(repository, directories, cleanup = cleanup))
            val plans = preview.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
            fun previewText(plans: List<JsonObject>): String = buildString {
                appendLine("Repository: $repository")
                appendLine("Only selected worktrees are deleted. Local branches and local changes are retained unless separately checked. Remote branches are never deleted. Open projects are never closed here.")
                appendLine("Deletion is irreversible: deleted local changes and commits losing their last retaining ref may not be recoverable.")
                for (plan in plans) {
                    appendLine()
                    appendLine("Directory: ${plan.get("directory") ?: "unknown"}")
                    appendLine(lifecycleRemovalText(plan))
                }
            }
            if (plans.none { it.get("selectable")?.asBoolean == true }) {
                withContext(Dispatchers.EDT) {
                    PreviewDialog(project, "No Selectable Worktrees", previewText(plans) + if (plans.isEmpty()) "\n$preview" else "", null).show()
                    message.text = "Nothing deleted; no selectable worktrees"
                }
                return@task
            }
            val selection = withContext(Dispatchers.EDT) {
                if (project.isDisposed) null else {
                    val dialog = WorktreeLifecycleCleanupDialog(project, previewText(plans), plans, cleanup)
                    if (dialog.showAndGet()) dialog.selection else null
                }
            }
            if (selection == null) {
                withContext(Dispatchers.EDT) { message.text = "Deletion cancelled; nothing changed" }
                return@task
            }
            val chosenDirectories = selection.plans.map { it.get("directory").asString }
            val freshPreview = json(tools.worktree_remove_preview(
                repository, chosenDirectories, cleanup, selection.deleteLocalBranches,
                selection.deleteLocalChanges, selection.includeClosedUnmerged,
            ))
            val fresh = freshPreview.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()
            val ready = fresh.size == chosenDirectories.size && fresh.all {
                it.get("status")?.asString == "preview" && it.get("confirmation")?.isJsonPrimitive == true
            }
            if (!ready) {
                withContext(Dispatchers.EDT) {
                    PreviewDialog(project, "Deletion Blocked — Review Fresh Preview", previewText(fresh) + if (fresh.isEmpty()) "\n$freshPreview" else "", null).show()
                    message.text = "Nothing deleted; review blockers and select the required options in a new preview"
                }
                return@task
            }
            if (!confirm("Confirm Worktree Deletion", previewText(fresh), "Delete ${fresh.size} Worktree(s)")) {
                withContext(Dispatchers.EDT) { message.text = "Deletion cancelled; nothing changed" }
                return@task
            }
            val result = json(tools.worktree_remove(
                repository, chosenDirectories, fresh.map { it.get("confirmation").asString }, cleanup,
                selection.deleteLocalBranches, selection.deleteLocalChanges, selection.includeClosedUnmerged,
            ))
            withContext(Dispatchers.EDT) {
                PreviewDialog(project, "Worktree Removal Results", result.toString(), null).show()
                message.text = "Deletion completed; inspect per-directory results."
            }
            loadInventory(repository)
        }
    }

    private suspend fun confirm(title: String, text: String, button: String): Boolean = withContext(Dispatchers.EDT) {
        !project.isDisposed && PreviewDialog(project, title, text, button).showAndGet()
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    private fun outcome(value: JsonObject): String = listOfNotNull(value.get("status")?.asString,
        value.get("reason")?.takeUnless { it.isJsonNull }?.asString).joinToString(": ")

    private fun creationText(plan: JsonObject): String = """
        Repository: ${plan.get("repositoryDirectory").asString}
        Destination: ${plan.get("directory").asString}
        Branch: ${plan.get("branch").asString} (${if (plan.get("newBranch").asBoolean) "new branch" else "existing branch; never reset"})
        Starting ref: ${plan.get("startRef").asString}
        Resolved HEAD: ${plan.get("head").asString}

        Create this exact checkout without opening a project, running installers or Git hooks.
        If the destination, branch or starting commit changes, creation is refused and needs a new preview.
    """.trimIndent()

    private inner class RowRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column)
            border = JBUI.Borders.empty(4, 8)
            icon = if (column == 0) AllIcons.Vcs.Branch else null
            if (column == 0) {
                val directory = rows[row].entry.directory
                text = "<html>${StringUtil.escapeXmlEntities(value.toString())}<br><small>${StringUtil.escapeXmlEntities(directory)}</small></html>"
            } else {
                text = "<html>${StringUtil.escapeXmlEntities(value.toString())}</html>"
            }
            if (!selected && (rows[row].failure != null || rows[row].status?.error != null) && column == 1) foreground = UIUtil.getErrorForeground()
            if (column in 1..3) border = BorderFactory.createCompoundBorder(JBUI.Borders.empty(6, 4),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIUtil.getBoundsColor()), JBUI.Borders.empty(2, 6)))
            return this
        }
    }

    private inner class RefreshRenderer : JButton(), TableCellRenderer {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            text = value?.toString()
            icon = AllIcons.Actions.Refresh
            isEnabled = !busy
            horizontalAlignment = SwingConstants.LEFT
            getAccessibleContext().accessibleName = "Refresh ${rows[row].entry.directory}"
            return this
        }
    }

    private inner class RefreshEditor : AbstractCellEditor(), TableCellEditor {
        private var row = -1
        private val button = JButton("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener { val selected = row; fireEditingStopped(); refreshRow(selected) }
        }
        override fun getCellEditorValue(): Any = "Refresh"
        override fun getTableCellEditorComponent(table: JTable, value: Any?, selected: Boolean, row: Int, column: Int): Component {
            this.row = row
            button.accessibleContext.accessibleName = "Refresh ${rows[row].entry.directory}"
            return button
        }
    }
}

private class PreviewDialog(project: Project, title: String, text: String, confirm: String?) : DialogWrapper(project) {
    private val details = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        background = UIUtil.getPanelBackground()
        accessibleContext.accessibleName = title
    }
    private val confirmation = confirm
    init { this.title = title; init(); if (confirm != null) setOKButtonText(confirm) else setCancelButtonText("Close") }
    override fun createCenterPanel(): JComponent = JBScrollPane(details).apply { preferredSize = JBUI.size(720, 400) }
    override fun createActions(): Array<javax.swing.Action> = if (confirmation == null) arrayOf(cancelAction) else super.createActions()
    override fun getPreferredFocusedComponent(): JComponent = details
}

internal fun lifecycleRemovalText(plan: JsonObject): String = buildString {
    appendLine("Status: ${plan.get("status") ?: "unknown"} · Selectable: ${plan.get("selectable") ?: "unknown"} · Safe: ${plan.get("safe") ?: "unknown"}")
    appendLine("Branch: ${plan.get("branch") ?: "unknown"} · HEAD: ${plan.get("head") ?: "unknown"} · Open: ${plan.get("open") ?: "unknown"}")
    appendLine("Cleanup: ${plan.get("cleanupKind") ?: "NONE"} · ${plan.get("cleanupDetails") ?: "unknown"}")
    appendLine("Remote branch absent: ${plan.get("remoteBranchAbsent") ?: "unknown"} · ${plan.get("remoteBranchDetails") ?: "unknown"}")
    appendLine("History known: ${plan.get("historyKnown") ?: "unknown"} · Unretained commits: ${plan.get("unsafeCommits") ?: "unknown"} · Unpushed commits: ${plan.get("unpushedCommits") ?: "unknown"}")
    appendLine("Commits losing retaining refs: ${plan.get("unretainedCommitIds") ?: "unknown"}")
    appendLine("Tracked changes: ${plan.get("trackedChanges") ?: "unknown"}")
    appendLine("Untracked files: ${plan.get("untrackedFiles") ?: "unknown"}")
    appendLine("Ignored files: ${plan.get("ignoredFiles") ?: "unknown"}")
    appendLine("Deletion options: ${plan.get("options") ?: "unknown"}")
    append("Blockers: ${plan.get("blockedReasons") ?: plan.get("reason") ?: "unknown"} · Codes: ${plan.get("blockedCodes") ?: "unknown"}")
}

internal class WorktreeLifecycleCleanupDialog(
    project: Project, text: String, candidates: List<JsonObject>, cleanup: Boolean = true,
) : DialogWrapper(project) {
    internal data class Choice(
        val plan: JsonObject, val selected: JBCheckBox, val deleteLocalBranch: JBCheckBox, val deleteLocalChanges: JBCheckBox,
    )
    internal data class Selection(
        val plans: List<JsonObject>, val deleteLocalBranches: List<String>,
        val deleteLocalChanges: List<String>, val includeClosedUnmerged: List<String>,
    )
    internal val choices = candidates.map { plan ->
        val directory = plan.get("directory").asString
        val selectable = plan.get("selectable")?.asBoolean == true
        Choice(
            plan,
            JBCheckBox("$directory — ${plan.get("branch") ?: "detached"} · ${plan.get("cleanupKind")?.asString ?: "NONE"}", selectable && plan.get("safe")?.asBoolean == true).apply {
                isEnabled = selectable
                toolTipText = lifecycleRemovalText(plan)
                addActionListener { updateSelection() }
            },
            JBCheckBox("Delete local branch").apply { accessibleContext.accessibleName = "Delete local branch for $directory" },
            JBCheckBox("Delete local changes").apply { accessibleContext.accessibleName = "Delete local changes for $directory" },
        )
    }
    val selectedPlans: List<JsonObject> get() = choices.filter { it.selected.isEnabled && it.selected.isSelected }.map { it.plan }
    internal val deleteSelectedAction get() = okAction
    val selection: Selection get() {
        val selected = choices.filter { it.selected.isEnabled && it.selected.isSelected }
        return Selection(
            selected.map { it.plan },
            selected.filter { it.deleteLocalBranch.isEnabled && it.deleteLocalBranch.isSelected }.map { it.plan.get("directory").asString },
            selected.filter { it.deleteLocalChanges.isSelected }.map { it.plan.get("directory").asString },
            selected.filter { it.plan.get("cleanupKind")?.asString == "CLOSED_UNMERGED" }.map { it.plan.get("directory").asString },
        )
    }
    internal val selectSafe = JButton("Select safe worktrees").apply { addActionListener {
        choices.forEach { it.selected.isSelected = it.selected.isEnabled && it.plan.get("safe")?.asBoolean == true }
        updateSelection()
    } }
    internal val selectAll = JButton("Select all worktrees").apply { addActionListener {
        choices.forEach { it.selected.isSelected = it.selected.isEnabled }
        updateSelection()
    } }
    internal val clearSelection = JButton("Clear selection").apply { addActionListener {
        choices.forEach { it.selected.isSelected = false }
        updateSelection()
    } }
    private val details = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        background = UIUtil.getPanelBackground()
        accessibleContext.accessibleName = "Worktree deletion safety preview"
    }
    init {
        title = if (cleanup) "Cleanup Worktrees" else "Remove Worktrees"
        init()
        rootPane?.defaultButton = selectSafe
        updateSelection()
    }
    private fun updateSelection() {
        choices.forEach {
            val selected = it.selected.isEnabled && it.selected.isSelected
            it.deleteLocalBranch.isEnabled = selected && it.plan.get("remoteBranchAbsent")?.asBoolean == true &&
                it.plan.get("branch")?.takeUnless { branch -> branch.isJsonNull } != null
            it.deleteLocalChanges.isEnabled = selected
        }
        val count = selectedPlans.size
        setOKButtonText("Delete selected worktrees ($count)…")
        isOKActionEnabled = count > 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        val selection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel().apply { add(selectSafe); add(selectAll); add(clearSelection) })
            add(JBLabel("Selection never enables branch or local change deletion. Continue to a fresh final preview."))
            choices.forEach { choice ->
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(4)
                    add(choice.selected)
                    add(JPanel().apply { add(choice.deleteLocalBranch); add(choice.deleteLocalChanges) })
                })
            }
        }
        add(JBScrollPane(selection).apply { preferredSize = JBUI.size(860, 240) }, BorderLayout.NORTH)
        add(JBScrollPane(details).apply { preferredSize = JBUI.size(860, 300) }, BorderLayout.CENTER)
    }
    override fun getPreferredFocusedComponent(): JComponent = selectSafe
}

private class CreateDialog(project: Project, repository: String) : DialogWrapper(project) {
    val directory = JBTextField(40)
    val branch = JBTextField(40)
    val start = JBTextField("HEAD", 40)
    val newBranch = JBCheckBox("Create a new local branch", true)
    init {
        title = "Create Worktree — $repository"
        directory.emptyText.text = "Absolute destination (must not exist)"
        init()
        setOKButtonText("Preview…")
    }
    override fun createCenterPanel(): JComponent = JPanel(GridBagLayout()).apply {
        val constraints = GridBagConstraints().apply { insets = JBUI.insets(4); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
        listOf("Destination" to directory, "Branch" to branch, "Starting ref" to start).forEachIndexed { index, (name, field) ->
            field.accessibleContext.accessibleName = name
            constraints.gridx = 0; constraints.gridy = index; constraints.weightx = 0.0
            add(JBLabel("$name:").apply { labelFor = field }, constraints)
            constraints.gridx = 1; constraints.weightx = 1.0; add(field, constraints)
        }
        constraints.gridx = 1; constraints.gridy = 3; add(newBranch, constraints)
    }
    override fun doValidate(): ValidationInfo? = when {
        directory.text.isBlank() -> ValidationInfo("Supply an absolute destination", directory)
        branch.text.isBlank() -> ValidationInfo("Supply an explicit local branch", branch)
        start.text.isBlank() -> ValidationInfo("Supply an explicit starting ref", start)
        else -> null
    }
    override fun getPreferredFocusedComponent(): JComponent = directory
}
