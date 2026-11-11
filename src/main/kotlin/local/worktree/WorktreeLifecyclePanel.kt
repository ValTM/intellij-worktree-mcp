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

/** Cleanup is stricter than individually approved removal: unknown upstream state is retained. */
internal fun lifecycleCleanupEligible(status: WorktreeDisplayStatus, preview: JsonObject): Boolean =
    status.mergedHeadCovered && status.error == null && preview.get("status")?.asString == "preview" &&
        preview.get("open")?.asBoolean == false && preview.get("historyKnown")?.asBoolean == true &&
        preview.get("unsafeCommits")?.takeUnless { it.isJsonNull }?.asInt == 0 &&
        preview.get("unpushedCommits")?.takeUnless { it.isJsonNull }?.asInt == 0 &&
        preview.getAsJsonArray("trackedChanges")?.isEmpty == true &&
        preview.getAsJsonArray("untrackedFiles")?.isEmpty == true &&
        preview.getAsJsonArray("blockedReasons")?.isEmpty == true &&
        preview.getAsJsonArray("blockedCodes")?.isEmpty == true

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
        action("Remove Worktrees…", "Preview exact selected directories; retain branches and block unsafe or open worktrees", AllIcons.General.Delete, { selected().isNotEmpty() }, { remove(cleanup = false) })
        action("Cleanup Merged…", "Preview only merged-HEAD-proven, clean, closed worktrees with known retained history; never delete branches", AllIcons.Actions.GC, { repository() != null && rows.isNotEmpty() }, { remove(cleanup = true) })
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
                    ?: "${entries.size} worktrees · ${if (fetch) "Refreshed" else "Local/cached status; Refresh fetches remotes"} · Branches are never deleted"
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
        task(if (cleanup) "Refreshing merge proof and preparing cleanup preview…" else "Preparing removal preview…") {
            if (cleanup) WorktreeStatusReader.refresh(project, repository)
            val inventory = GitWorktrees.discover(repository).associateBy { it.directory }
            val directories = if (cleanup) inventory.keys.toList() else selectedDirectories
            val preview = json(tools.worktree_remove_preview(repository, directories))
            val plans = preview.getAsJsonArray("results").map { it.asJsonObject }
            val statuses = if (cleanup) plans.associate { plan ->
                val directory = plan.get("directory").asString
                directory to inventory[directory]?.let { WorktreeStatusReader.read(project, repository, it) }
            } else emptyMap()
            val approved = plans.filter { plan ->
                plan.get("status")?.asString == "preview" && (!cleanup ||
                    statuses[plan.get("directory").asString]?.let { lifecycleCleanupEligible(it, plan) } == true)
            }
            val text = buildString {
                appendLine("Repository: $repository")
                appendLine(if (cleanup) "Select eligible directories below. Only selected worktrees will be removed. Local and remote branches are retained. No remote deletion. Open projects are never closed here."
                    else "Only the following approved worktree directories will be removed. Local and remote branches are retained. No remote deletion. Open projects are never closed here.")
                if (cleanup) appendLine("Cleanup additionally requires merged proof covering the exact current HEAD, clean files and known upstream/history. Proof and removal safety are rechecked before each deletion.")
                for (plan in plans) {
                    appendLine()
                    appendLine(if (plan in approved) "${if (cleanup) "ELIGIBLE" else "REMOVE"}: ${plan.get("directory").asString}" else "RETAIN: ${plan.get("directory")?.asString ?: "unknown"}")
                    appendLine(removalText(plan))
                    if (cleanup && plan !in approved) appendLine("Not eligible for proven merged cleanup: ${statuses[plan.get("directory")?.asString]?.reviewDetails ?: "merge or status evidence unknown"}")
                }
            }
            if (approved.isEmpty()) {
                withContext(Dispatchers.EDT) { PreviewDialog(project, "No Safe Worktrees to Remove", text, null).show(); message.text = "Nothing removed; no eligible worktrees" }
                return@task
            }
            val chosen = if (cleanup) withContext(Dispatchers.EDT) {
                if (project.isDisposed) emptyList() else {
                    val dialog = WorktreeLifecycleCleanupDialog(project, text, approved)
                    if (dialog.showAndGet()) dialog.selectedPlans else emptyList()
                }
            } else if (confirm("Remove Worktrees", text, "Remove ${approved.size} Worktree(s)")) approved else emptyList()
            if (chosen.isEmpty()) {
                withContext(Dispatchers.EDT) { message.text = "Removal cancelled; nothing changed" }
                return@task
            }
            if (cleanup) WorktreeStatusReader.refresh(project, repository)
            val outcomes = mutableListOf<String>()
            for (plan in chosen) {
                val directory = plan.get("directory").asString
                if (cleanup) {
                    val current = GitWorktrees.discover(repository).singleOrNull { it.directory == directory }
                    val status = current?.let { WorktreeStatusReader.read(project, repository, it) }
                    val fresh = json(tools.worktree_remove_preview(repository, listOf(directory))).getAsJsonArray("results").single().asJsonObject
                    if (current?.head != plan.get("head")?.takeUnless { it.isJsonNull }?.asString || status == null ||
                        !lifecycleCleanupEligible(status, fresh) || fresh.get("confirmation")?.asString != plan.get("confirmation")?.asString) {
                        outcomes.add("Retained $directory: merge proof or removal state changed; obtain a new preview")
                        continue
                    }
                }
                val result = json(tools.worktree_remove(repository, listOf(directory), listOf(plan.get("confirmation").asString)))
                outcomes.add("$directory: ${result.getAsJsonArray("results").joinToString { outcome(it.asJsonObject) }}")
            }
            withContext(Dispatchers.EDT) {
                PreviewDialog(project, "Worktree Removal Results", outcomes.joinToString("\n\n"), null).show()
                message.text = "Removal completed; inspect per-directory results. Branches retained."
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

    private fun removalText(plan: JsonObject): String = buildString {
        appendLine("Branch: ${plan.get("branch") ?: "unknown"} · HEAD: ${plan.get("head") ?: "unknown"}")
        appendLine("History known: ${plan.get("historyKnown") ?: "unknown"} · Unretained commits: ${plan.get("unsafeCommits") ?: "unknown"} · Unpushed commits: ${plan.get("unpushedCommits") ?: "unknown"} · Open: ${plan.get("open") ?: "unknown"}")
        appendLine("Tracked changes: ${plan.get("trackedChanges") ?: "unknown"}")
        appendLine("Untracked / ignored files: ${plan.get("untrackedFiles") ?: "unknown"}")
        append("Blockers: ${plan.get("blockedReasons") ?: plan.get("reason") ?: "unknown"}")
    }

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

internal class WorktreeLifecycleCleanupDialog(project: Project, text: String, candidates: List<JsonObject>) : DialogWrapper(project) {
    internal val choices = candidates.map { plan ->
        plan to JBCheckBox("${plan.get("directory").asString} — ${plan.get("branch") ?: "detached"}", true).apply {
            toolTipText = "Remove this directory only; preserve its branch. HEAD: ${plan.get("head") ?: "unknown"}"
            addActionListener { isOKActionEnabled = selectedPlans.isNotEmpty() }
        }
    }
    val selectedPlans: List<JsonObject> get() = choices.filter { it.second.isSelected }.map { it.first }
    private val details = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        background = UIUtil.getPanelBackground()
        accessibleContext.accessibleName = "Merged cleanup safety preview"
    }
    init {
        title = "Cleanup Merged Worktrees"
        init()
        setOKButtonText("Remove Selected Worktrees")
        isOKActionEnabled = choices.isNotEmpty()
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        val selection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("Select eligible worktrees to remove (branches retained):"))
            choices.forEach { add(it.second) }
        }
        add(JBScrollPane(selection).apply { preferredSize = JBUI.size(720, 130) }, BorderLayout.NORTH)
        add(JBScrollPane(details).apply { preferredSize = JBUI.size(720, 300) }, BorderLayout.CENTER)
    }
    override fun getPreferredFocusedComponent(): JComponent = choices.firstOrNull()?.second ?: details
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
