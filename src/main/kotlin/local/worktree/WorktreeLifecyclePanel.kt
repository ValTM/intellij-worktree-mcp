package local.worktree

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
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
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitWorkingTree
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.HierarchyEvent
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTable
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

internal class WorktreeLifecyclePanel(private val project: Project) : JBPanel<WorktreeLifecyclePanel>(BorderLayout()), Disposable, UiDataProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val operations = WorktreeOperations()
    private val rows = mutableListOf<WorktreeStatusSnapshot>()
    private var windows: Map<String, String> = emptyMap()
    private var busy = false
        set(value) {
            if (field == value) return
            field = value
            refreshActions()
        }
    private val repositories = ComboBox<String>()
    private val message = JBLabel("Loading worktrees…")
    private var activeTask: Job? = null
    private var inventory: WorktreeInventory? = null
    private var inventoryRepository: String? = null
    private var inventoryUpdates: Job? = null
    private var localReloadPending = false
    private var inventoryRefreshPending = false
    private var updatingRepositories = false
    private val cancelTask = JButton("Cancel").apply {
        isVisible = false
        addActionListener { activeTask?.cancel(); isEnabled = false }
    }
    private val progress = JProgressBar().apply { isIndeterminate = true; isVisible = false }
    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("Worktree", "Git", "PR")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(row: Int, column: Int): String {
            val value = rows[row]
            return when (column) {
                0 -> value.entry.branch ?: if (value.entry.bare) "Bare repository" else "Detached HEAD"
                1 -> value.status?.syncLabel ?: if (value.failure != null) "Unavailable" else "Loading…"
                2 -> value.status?.let { it.reviewLabel + if (it.reviewCached) " · Cached" else "" }
                    ?: if (value.failure != null) "Unknown" else "Loading…"
                else -> ""
            }
        }
    }
    internal val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = rowAtPoint(event.point)
            if (index !in rows.indices) return null
            val column = columnAtPoint(event.point)
            if (column == 0) {
                val entry = rows[index].entry
                val states = stateIcons(entry)
                val cell = getCellRect(index, column, false)
                val leading = if (entry.bare) null else states.first()
                if (leading != null && event.x - cell.x - JBUI.scale(8) in 0 until leading.first.iconWidth) return leading.second
                val trailing = if (leading == null) states else states.drop(1)
                var x = event.x - (cell.x + cell.width - JBUI.scale(8) - trailing.sumOf { it.first.iconWidth })
                for ((icon, description) in trailing) {
                    if (x in 0 until icon.iconWidth) return description
                    x -= icon.iconWidth
                }
            }
            val details = cellDescription(index, column)
            return "<html><div style='width:420px'>${StringUtil.escapeXmlEntities(details).replace("\n", "<br>")}</div></html>"
        }
    }
    private val actions = actions()
    private val toolbar = ActionManager.getInstance().createActionToolbar("WorktreeLifecycle.Toolbar", actions, true)

    init {
        border = JBUI.Borders.empty(4)
        repositories.accessibleContext.accessibleName = "Repository"
        repositories.toolTipText = "Select the Git repository whose linked worktrees are shown"
        repositories.addActionListener { if (!updatingRepositories) reload(fetch = false) }
        repositories.setMinimumAndPreferredWidth(JBUI.scale(180))
        repositories.maximumSize = Dimension(JBUI.scale(240), repositories.preferredSize.height)
        table.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.rowHeight = JBUI.scale(38)
        table.setShowGrid(false)
        table.setStriped(true)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.accessibleContext.accessibleName = "Worktree Lifecycle"
        table.accessibleContext.accessibleDescription = "Select worktrees for lifecycle actions. Enter opens; F5 refreshes all worktrees."
        table.emptyText.text = "No worktrees available. Refresh to retry."
        table.setDefaultRenderer(Any::class.java, RowRenderer())
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(260)
        for (column in 1..2) table.columnModel.getColumn(column).apply {
            minWidth = JBUI.scale(40)
            preferredWidth = JBUI.scale(48)
            maxWidth = JBUI.scale(56)
        }
        table.columnModel.getColumn(1).apply {
            minWidth = JBUI.scale(56)
            preferredWidth = JBUI.scale(64)
            maxWidth = JBUI.scale(72)
        }
        if (project.getService(WorktreeReviewStatusProvider::class.java) == null) {
            table.removeColumn(table.columnModel.getColumn(2))
        }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) refreshActions() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1 && !busy) openSelected()
            }
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "worktree.open")
        table.actionMap.put("worktree.open", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (!busy) openSelected()
            }
        })
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "worktree.refresh")
        table.actionMap.put("worktree.refresh", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) { refresh() }
        })
        toolbar.targetComponent = table
        toolbar.setMiniMode(true)
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toolbar.component)
            add(repositories)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(message.apply { border = JBUI.Borders.empty(5); accessibleContext.accessibleName = "Worktree operation status" }, BorderLayout.CENTER)
            add(JPanel().apply { add(progress); add(cancelTask) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        PopupHandler.installRowSelectionTablePopup(table, actions, "WorktreeLifecycle.Popup")
        var shown = false
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                if (shown) {
                    inventoryRefreshPending = true
                    reload(fetch = false)
                }
                shown = true
            }
        }
        updateRepositories()
        reload(fetch = false)
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (isShowing) {
                    refreshWindowStates()
                    refreshActions()
                }
                drainInventoryUpdates()
            }
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[CommonDataKeys.PROJECT] = project
        if (operationBusy()) return
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
    private fun refreshActions() {
        // Native action snapshots are cached until the platform activity count changes.
        ActivityTracker.getInstance().inc()
        toolbar.updateActionsImmediately()
    }

    private fun repository(): String? = repositories.selectedItem as? String
    private fun selected(): List<WorktreeStatusSnapshot> = table.selectedRows.asSequence().mapNotNull { rows.getOrNull(it) }.toList()
    private fun one(): WorktreeStatusSnapshot? = selected().singleOrNull()
    private fun operationBusy(): Boolean = busy || WorktreeOperations.lifecycleBusy
    private fun usable(): Boolean = one()?.entry?.let { !it.bare && it.prunable == null } == true
    private fun reviewAvailable(): Boolean = usable() && one()?.entry?.branch != null &&
        ExtensionPointName.create<Any>("Git4Idea.gitBranchReviewPresenter").extensionList.isNotEmpty()

    private fun actions(): DefaultActionGroup = DefaultActionGroup().apply {
        fun action(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) {
            add(object : DumbAwareAction(text, description, icon) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = !operationBusy() && enabled()
                    when (text) {
                        "Open Worktree" -> {
                            val state = one()?.entry?.directory?.let(windows::get)
                            event.presentation.text = when (state) {
                                "current" -> "Current Worktree"
                                "same_window", "other_window" -> "Switch to Worktree"
                                else -> text
                            }
                            event.presentation.description = when (state) {
                                "current" -> "The selected worktree is the current IDE project"
                                "same_window" -> "Select the worktree tab in this IDE window"
                                "other_window" -> "Switch to the worktree in another IDE window"
                                else -> description
                            }
                            event.presentation.icon = if (state == null) icon else AllIcons.Actions.Forward
                        }
                        "Open and Merge All…" -> event.presentation.text =
                            if (one()?.entry?.directory in windows) "Merge All…" else text
                        "Lock Worktree" -> {
                            val locked = one()?.entry?.locked != null
                            event.presentation.text = if (locked) "Unlock Worktree" else text
                            event.presentation.description = if (locked) "Unlock the selected worktree to allow removal"
                                else "Lock the selected worktree to protect it from removal"
                            event.presentation.icon = if (locked) AllIcons.Ide.Readwrite else AllIcons.Ide.Readonly
                        }
                        "Refresh" -> {
                            event.presentation.text = if (busy) "Refreshing…" else text
                            event.presentation.description = if (busy) message.text else description
                        }
                    }
                }
                override fun actionPerformed(event: AnActionEvent) { if (!operationBusy() && enabled()) perform() }
            })
        }
        action("Create Worktree…", "Preview a new or existing branch checkout before creating it", AllIcons.General.Add, { repository() != null }, ::create)
        addSeparator()
        action("Open Worktree", "Open or reuse the selected independent project without merging windows", AllIcons.Actions.MenuOpen,
            { usable() && one()?.entry?.directory?.let(windows::get) != "current" }) { openSelected() }
        if (SystemInfoRt.isMac) action("Open and Merge All…", "Confirm merging ALL eligible project windows, including unrelated projects",
            AllIcons.Actions.MoveToWindow, ::usable) { openSelected(merge = true) }
        addSeparator()
        action("Open Review in Worktree", "Open the worktree and its native review view", AllIcons.Vcs.Branch, ::reviewAvailable) { openSelected(review = true) }
        if (SystemInfoRt.isMac) action("Open Review and Merge All…", "Open the native review and confirm merging ALL eligible project windows",
            AllIcons.Actions.MoveToWindow, ::reviewAvailable) { openSelected(merge = true, review = true) }
        addSeparator()
        action("Lock Worktree", "Lock the selected worktree to protect it from removal", AllIcons.Ide.Readonly,
            { usable() && one()?.entry?.main == false }, ::toggleLock)
        val prune = ActionUtil.wrap("Git.WorkingTrees.Prune")
        add(object : DumbAwareAction() {
            init {
                copyFrom(prune)
                if (templatePresentation.icon == null) templatePresentation.icon = AllIcons.Actions.GC
            }
            override fun getActionUpdateThread(): ActionUpdateThread = prune.actionUpdateThread
            override fun update(event: AnActionEvent) {
                prune.update(event)
                if (event.presentation.icon == null) event.presentation.icon = AllIcons.Actions.GC
                event.presentation.isEnabled = event.presentation.isEnabled && !operationBusy()
            }
            override fun actionPerformed(event: AnActionEvent) { if (!operationBusy()) prune.actionPerformed(event) }
        })
        action("Remove Worktrees…", "Select worktrees, cleanup eligibility and optional branch or local change deletion",
            AllIcons.General.Delete, { repository() != null && rows.isNotEmpty() }, ::remove)
        addSeparator()
        action("Refresh", "Fetch remotes and refresh inventory, sync and provider review data", AllIcons.Actions.Refresh, { true }, ::refresh)
    }

    private fun updateRepositories() {
        val selected = repository()
        val paths = GitRepositoryManager.getInstance(project).repositories.map { it.root.path }.sorted()
        updatingRepositories = true
        try {
            repositories.removeAllItems()
            paths.forEach(repositories::addItem)
            if (selected in paths) repositories.selectedItem = selected
            repositories.isVisible = paths.size > 1
        } finally { updatingRepositories = false }
    }

    private fun stateIcons(entry: GitWorktree): List<Pair<javax.swing.Icon, String>> = buildList {
        add(when {
            entry.bare -> AllIcons.General.Vcs to "Bare repository — no working directory"
            entry.main -> AllIcons.Nodes.HomeFolder to "Main worktree directory"
            else -> AllIcons.Vcs.Branch to "Worktree directory"
        })
        when (windows[entry.directory]) {
            "current" -> add(AllIcons.Nodes.Target to "Current IDE project")
            "same_window" -> add(AllIcons.Actions.MoveToWindow to "Open in this IDE window")
            "other_window" -> add(AllIcons.Actions.MoveToWindow to "Open in another IDE window")
        }
        if (entry.locked != null) add(AllIcons.Ide.Readonly to "Worktree locked — unlock before removal${entry.locked.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}")
        if (entry.detached) add(AllIcons.Vcs.CommitNode to "Detached HEAD — no branch checked out")
        if (entry.prunable != null) add(AllIcons.General.Warning to "Stale worktree registration — can be pruned${entry.prunable.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}")
    }

    private suspend fun refreshWindowStates() {
        if (project.isDisposed) return
        val current = operations.windowStates(project)
        withContext(Dispatchers.EDT) {
            if (windows != current) {
                windows = current
                if (rows.isNotEmpty()) model.fireTableRowsUpdated(0, rows.lastIndex)
                refreshActions()
            }
        }
    }

    private fun task(title: String, operation: suspend () -> Unit) {
        if (operationBusy()) return
        busy = true
        repositories.isEnabled = false
        message.text = title
        cancelTask.isEnabled = true
        cancelTask.isVisible = true
        progress.isVisible = true
        if (rows.isNotEmpty()) model.fireTableRowsUpdated(0, rows.lastIndex)
        activeTask = scope.launch {
            try { withContext(Dispatchers.Default) { operation() } }
            catch (cancelled: CancellationException) { message.text = "Operation cancelled; refresh to inspect current state"; throw cancelled }
            catch (cancelled: ProcessCanceledException) { message.text = "Operation cancelled; refresh to inspect current state"; throw cancelled }
            catch (error: Exception) { message.text = "$title failed: ${error.message ?: error.javaClass.simpleName}"; message.toolTipText = message.text }
            finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try { refreshWindowStates() }
                    finally {
                        withContext(Dispatchers.EDT) {
                            busy = false
                            repositories.isEnabled = true
                            cancelTask.isVisible = false
                            progress.isVisible = false
                            activeTask = null
                            if (rows.isNotEmpty()) model.fireTableRowsUpdated(0, rows.lastIndex)
                            drainInventoryUpdates()
                        }
                    }
                }
            }
        }
    }

    private fun refresh() {
        if (operationBusy()) return
        updateRepositories()
        reload(fetch = true)
    }

    private fun reload(fetch: Boolean) {
        if (operationBusy()) {
            localReloadPending = true
            return
        }
        val repository = repository()
        if (repository == null) {
            inventoryUpdates?.cancel()
            inventoryUpdates = null
            inventory = null
            inventoryRepository = null
            localReloadPending = false
            inventoryRefreshPending = false
            rows.clear(); model.fireTableDataChanged()
            message.text = "No Git repositories mapped in this project. Refresh after configuring Git."
            return
        }
        if (fetch) GitRepositoryManager.getInstance(project).repositories.singleOrNull { it.root.path == repository }
            ?.workingTreeHolder?.scheduleReload()
        localReloadPending = false
        val reconcile = inventoryRefreshPending || (inventory != null && inventoryRepository == repository)
        inventoryRefreshPending = false
        task(if (fetch) "Refreshing remote and review status…" else "Reading local worktrees…") {
            val current = observeInventory(repository)
            val snapshot = if (fetch || reconcile) current.refreshStatus(project, repository, fetch) else current.current(project, repository)
            withContext(Dispatchers.EDT) { applyInventory(snapshot) }
        }
    }

    private suspend fun observeInventory(repository: String): WorktreeInventory = withContext(Dispatchers.EDT) {
        inventory?.takeIf { inventoryRepository == repository }?.let { return@withContext it }
        val current = service<WorktreeInventoryService>().inventory(repository)
        inventoryUpdates?.cancel()
        inventory = current
        inventoryRepository = repository
        inventoryUpdates = scope.launch {
            current.snapshots.filterNotNull().collect { snapshot ->
                if (inventory === current && repository() == repository) applyInventory(snapshot)
            }
        }
        current
    }

    private fun drainInventoryUpdates() {
        if (!scope.isActive || operationBusy() || project.isDisposed) return
        if (localReloadPending || inventoryRefreshPending) reload(fetch = false)
    }

    private fun applyInventory(snapshot: WorktreeInventorySnapshot) {
        val selectedPaths = selected().map { it.entry.directory }.toSet()
        rows.clear()
        rows.addAll(snapshot.rows)
        model.fireTableDataChanged()
        rows.forEachIndexed { index, row -> if (row.entry.directory in selectedPaths) table.addRowSelectionInterval(index, index) }
        message.text = when {
            snapshot.error != null -> "Worktree inventory unavailable: ${snapshot.error}"
            snapshot.refreshing -> "Refreshing worktree status…"
            snapshot.refreshFailure != null -> "Refresh failed: ${snapshot.refreshFailure}. Showing local/cached status; Refresh retries."
            else -> "${rows.size} worktrees"
        }
        message.toolTipText = message.text
        refreshActions()
    }

    private fun toggleLock() {
        val entry = one()?.entry ?: return
        val repository = repository() ?: return
        task(if (entry.locked == null) "Locking worktree…" else "Unlocking worktree…") {
            val result = try {
                operations.setLocked(repository, entry.directory, entry.locked == null)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { loadInventory(repository) }
            }
            withContext(Dispatchers.EDT) { message.text = outcome(result) }
        }
    }

    private fun openSelected(merge: Boolean = false, review: Boolean = false) {
        if (operationBusy() || !usable() || (review && !reviewAvailable())) return
        if (!merge && !review && one()?.entry?.directory?.let(windows::get) == "current") return
        val entry = one()?.entry ?: return
        val repository = repository() ?: return
        val target = project.basePath ?: return
        val switch = !merge && windows[entry.directory] != null
        task(when {
            review -> "Opening worktree review…"
            merge -> "Opening and merging project windows…"
            switch -> "Switching worktree…"
            else -> "Opening worktree…"
        }) {
            val result = project.service<WorktreeOpener>().openAndSelect(
                entry.directory, target, merge, entry.branch.takeIf { review },
                repository.takeIf { switch },
            )
            withContext(Dispatchers.EDT) {
                message.text = result?.let(::outcome) ?: "Window merging cancelled; nothing changed"
            }
        }
    }

    private fun create() {
        val repository = repository() ?: return
        task("Creating worktree…") {
            var input = WorktreeCreateInput()
            while (true) {
                val edited = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) null else CreateDialog(project, repository, input).let {
                        if (it.showAndGet()) it.input else null
                    }
                }
                if (edited == null) {
                    withContext(Dispatchers.EDT) { message.text = "Creation cancelled; nothing changed" }
                    return@task
                }
                input = edited
                val directory = input.directory.trim()
                val branch = input.branch.trim()
                val start = input.start.trim()
                val preview = operations.worktree_create_preview(repository, directory, branch, start, input.newBranch)
                require(preview.get("status")?.asString == "preview") { outcome(preview) }
                val decision = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) DialogWrapper.CANCEL_EXIT_CODE else {
                        val review = CreationReviewDialog(project, preview, input.copySettings)
                        review.show()
                        review.exitCode
                    }
                }
                if (decision == CreationReviewDialog.BACK_EXIT_CODE) continue
                if (decision != DialogWrapper.OK_EXIT_CODE) {
                    withContext(Dispatchers.EDT) { message.text = "Creation cancelled; nothing changed" }
                    return@task
                }
                val result = operations.worktree_create(
                    repository, directory, branch, start, preview.get("confirmation").asString, input.newBranch,
                )
                val transfer = if (input.copySettings && result.get("status")?.asString == "created") {
                    try {
                        operations.copySettings(repository, result.get("directory").asString).summary()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (cancelled: ProcessCanceledException) { throw cancelled }
                    catch (error: Exception) {
                        "IDE settings: 0 copied, 0 skipped; transfer failed: ${error.message ?: error.javaClass.simpleName}. Created worktree retained"
                    }
                } else null
                withContext(Dispatchers.EDT) {
                    message.text = listOfNotNull(outcome(result), transfer).joinToString(" · ")
                    message.toolTipText = message.text
                }
                loadInventory(repository)
                return@task
            }
        }
    }

    private suspend fun loadInventory(repository: String) {
        val snapshot = observeInventory(repository).refreshStatus(project, repository)
        refreshWindowStates()
        withContext(Dispatchers.EDT) { applyInventory(snapshot) }
    }

    private fun remove() {
        val repository = repository() ?: return
        task("Preparing worktree removal preview…") {
            val selection = selectWorktreesForRemoval(project, repository)
            if (selection == null) {
                withContext(Dispatchers.EDT) { message.text = "Deletion cancelled; nothing changed" }
                return@task
            }
            // Application scope survives closure of the project that opened this panel.
            service<WorktreeRemovalService>().remove(project, repository, selection).join()
        }
    }

    private fun outcome(value: JsonObject): String = listOfNotNull(value.get("status")?.asString,
        value.get("reason")?.takeUnless { it.isJsonNull }?.asString).joinToString(": ")

    private fun cellDescription(index: Int, column: Int, states: List<Pair<javax.swing.Icon, String>>? = null): String {
        val row = rows[index]
        return when (column) {
            1 -> listOfNotNull(model.getValueAt(index, column), row.status?.syncLabel, row.failure,
                "Local changes".takeIf { row.status?.hasLocalChanges == true },
                row.status?.syncDetails, row.status?.error).distinct().joinToString("\n")
            2 -> listOfNotNull(model.getValueAt(index, column), row.failure.takeIf { row.status == null },
                "Loading pull request status…".takeIf { row.status?.reviewLoading == true },
                "Showing cached review evidence for this exact branch and HEAD; deletion requires fresh checks.".takeIf { row.status?.reviewCached == true },
                row.status?.reviewDetails, row.status?.reviewError, row.status?.error).distinct().joinToString("\n")
            else -> (listOf(row.entry.directory, "HEAD: ${row.entry.head ?: "unknown"}") +
                (states ?: stateIcons(row.entry)).map { it.second }).joinToString("\n")
        }
    }

    private inner class RowRenderer : DefaultTableCellRenderer() {
        private var statesIcon: javax.swing.Icon? = null
        private val loadingIcon = AnimatedIcon.Default()

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            statesIcon?.let { it.paintIcon(this, graphics, width - JBUI.scale(8) - it.iconWidth, (height - it.iconHeight) / 2) }
        }

        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column)
            foreground = if (selected) table.selectionForeground else table.foreground
            border = JBUI.Borders.empty(4, 8)
            horizontalAlignment = if (column in 1..2) SwingConstants.CENTER else SwingConstants.LEFT
            text = when (column) {
                0 -> "<html>${StringUtil.escapeXmlEntities(value.toString())}<br><small>${StringUtil.escapeXmlEntities(rows[row].entry.directory)}</small></html>"
                1, 2 -> ""
                else -> "<html>${StringUtil.escapeXmlEntities(value.toString())}</html>"
            }
            val status = rows[row].status
            val states = if (column == 0) stateIcons(rows[row].entry) else null
            val trailing = states?.let { if (rows[row].entry.bare) it else it.drop(1) }
            statesIcon = trailing?.takeIf { it.isNotEmpty() }?.let { RowIcon(*it.map { state -> state.first }.toTypedArray()) }
            border = javax.swing.border.EmptyBorder(JBUI.insets(4, 8).apply {
                right += statesIcon?.let { it.iconWidth + JBUI.scale(8) } ?: 0
            })
            val failed = when (column) {
                1 -> rows[row].failure != null || status?.error != null
                2 -> (rows[row].failure != null && status == null) || status?.error != null || status?.reviewError != null
                else -> false
            }
            icon = when (column) {
                0 -> states?.first()?.first?.takeUnless { rows[row].entry.bare }
                1 -> {
                    val sync = when {
                        rows[row].localLoading -> loadingIcon
                        failed -> AllIcons.General.Warning
                        else -> status?.syncIcon ?: AllIcons.General.Remove
                    }
                    if (status?.hasLocalChanges == true) RowIcon(sync, AllIcons.Actions.Edit) else sync
                }
                2 -> when {
                    rows[row].localLoading || status?.reviewLoading == true -> loadingIcon
                    failed -> AllIcons.General.Warning
                    else -> status?.reviewIcon ?: AllIcons.General.Remove
                }
                else -> null
            }
            val description = cellDescription(row, column, states)
            toolTipText = description
            getAccessibleContext().accessibleName = if (column == 0) "$value\n$description" else description
            if (!selected && failed) foreground = UIUtil.getErrorForeground()
            return this
        }
    }

}

internal class PreviewDialog(project: Project, title: String, text: String, confirm: String?) : DialogWrapper(project) {
    private val details = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        background = UIUtil.getPanelBackground()
        font = UIUtil.getLabelFont()
        accessibleContext.accessibleName = title
    }
    private val confirmation = confirm
    init { this.title = title; init(); if (confirm != null) setOKButtonText(confirm) else setCancelButtonText("Close") }
    override fun createCenterPanel(): JComponent = JBScrollPane(details).apply { preferredSize = JBUI.size(720, 400) }
    override fun createActions(): Array<javax.swing.Action> = if (confirmation == null) arrayOf(cancelAction) else super.createActions()
    override fun getPreferredFocusedComponent(): JComponent = details
}

internal suspend fun selectWorktreesForRemoval(
    project: Project, repository: String, initialSelection: WorktreeRemovalDialog.Selection? = null,
): WorktreeRemovalDialog.Selection? {
    val shared = service<WorktreeInventoryService>().inventory(repository)
    if (shared.snapshots.value == null) shared.refreshStatus(project, repository)
    return withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext null
        val dialog = WorktreeRemovalDialog(
            project, shared.snapshots,
            { ProjectManager.getInstance().openProjects.mapNotNull { it.basePath } },
            initialSelection, repository,
        )
        if (dialog.showAndGet()) dialog.selection else null
    }
}


internal class WorktreeRemovalDialog(
    project: Project, private var candidates: List<WorktreeRemovalCandidate>, cleanup: Boolean = true,
    private val snapshots: StateFlow<WorktreeInventorySnapshot?>? = null,
    private val openProjects: () -> Collection<String> = { emptyList() },
    private val initialSelection: Selection? = null,
    private val repository: String = project.basePath.orEmpty(),
) : DialogWrapper(project) {
    constructor(
        project: Project, snapshots: StateFlow<WorktreeInventorySnapshot?>,
        openProjects: () -> Collection<String>,
        initialSelection: Selection? = null, repository: String = project.basePath.orEmpty(),
    ) : this(project, snapshots.value?.removalCandidates(openProjects()).orEmpty(),
        initialSelection?.cleanup ?: true, snapshots, openProjects, initialSelection, repository)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
    internal data class Choice(
        var plan: WorktreeRemovalSelection, val selected: JBCheckBox,
    )
    internal data class Selection(
        val plans: List<JsonObject>, val deleteLocalBranches: List<String>,
        val deleteLocalChanges: List<String>, val includeClosedUnmerged: List<String>, val cleanup: Boolean,
        val closeProjects: List<String> = emptyList(),
        val closeProjectsEnabled: Boolean = true,
        val deleteBranchesEnabled: Boolean = deleteLocalBranches.isNotEmpty(),
        val discardChangesEnabled: Boolean = deleteLocalChanges.isNotEmpty(),
    )
    private var confirmedSelection: Selection? = null
    private val status = JBLabel()
    internal val cleanupMode = JBCheckBox("Verified cleanup", initialSelection?.cleanup ?: cleanup).apply {
        isEnabled = candidates.isNotEmpty()
        toolTipText = "Uncheck for individually approved removal without merge classification. Other safety checks still apply."
        addActionListener { projectCandidates() }
    }
    internal val closeSelectedProjects = JBCheckBox("Close selected open projects before review", initialSelection?.closeProjectsEnabled ?: true).apply {
        toolTipText = "Switches to the main worktree, then normally closes only explicitly selected projects after confirmation."
        addActionListener { projectCandidates() }
    }
    internal val deleteLocalBranches = JBCheckBox("Delete local branches", initialSelection?.deleteBranchesEnabled ?: false).apply {
        addActionListener { updateSelection() }
    }
    internal val discardLocalChanges = JBCheckBox("Discard local changes", initialSelection?.discardChangesEnabled ?: false).apply {
        addActionListener { updateSelection() }
    }
    internal val choices = candidates.map { makeChoice(plan(it)) }.toMutableList()
    internal val detailsPanel = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty(8, 16)
        accessibleContext.accessibleName = "Focused worktree details"
    }
    private val optionsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val optionsToggle = JToggleButton("Additional deletion options").apply {
        addActionListener { updateSelection() }
    }
    private val optionsSummary = JBLabel()
    private val modeDescription = JBLabel()
    private var focusedWorktreeDirectory: String? = null
    private fun eligibility(plan: WorktreeRemovalSelection): String = when {
        "MAIN_WORKTREE" in plan.eligibility.blocked -> "Main checkout — cannot remove"
        plan.open && plan.eligibility.selectable -> "Will close selected project before fresh preview"
        plan.open -> "Opt in to closing selected projects; other blockers may still apply"
        "CLEANUP_UNPROVEN" in plan.eligibility.blocked -> "Merge status not verified"
        !plan.eligibility.selectable -> plan.eligibility.blocked.values.firstOrNull() ?: "Unavailable"
        plan.eligibility.cleanupKind == "CLOSED_UNMERGED" -> "PR closed without merge — select to opt in"
        !plan.eligibility.safe -> "Local changes — enable force to discard"
        plan.eligibility.cleanupKind == "MERGED" -> "Merged — can be removed"
        else -> "Can be removed"
    }
    private val tableModel = object : AbstractTableModel() {
        private val columns = arrayOf("", "Worktree", "State")
        override fun getRowCount() = choices.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any = choices[row].let { choice ->
            when (column) {
                0 -> choice.selected.isSelected
                1 -> choice.plan.entry.branch ?: Path.of(choice.plan.entry.directory).fileName.toString()
                else -> when {
                    choice.plan.entry.main -> "Main"
                    !choice.plan.eligibility.selectable -> "Blocked"
                    choice.plan.open -> "Open"
                    choice.plan.eligibility.cleanupKind == "CLOSED_UNMERGED" -> "Closed PR"
                    !choice.plan.eligibility.safe -> "Changes"
                    choice.plan.eligibility.cleanupKind == "MERGED" -> "Merged"
                    else -> "Available"
                }
            }
        }
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 && choices[row].selected.isEnabled
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (!isCellEditable(row, column)) return
            val choice = choices[row]
            choice.selected.isSelected = value == true
            updateSelection()
        }
    }
    internal val candidatesTable: JBTable = object : JBTable(tableModel) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component =
            super.prepareRenderer(renderer, row, column).apply {
                isEnabled = (column == 1 || column == 2) || tableModel.isCellEditable(row, column)
                if (column == 1 && this is javax.swing.JLabel) {
                    val entry = choices[row].plan.entry
                    val branch = StringUtil.escapeXmlEntities(entry.branch ?: "Detached HEAD")
                    val directory = StringUtil.escapeXmlEntities(Path.of(entry.directory).fileName.toString())
                    text = "<html>$branch<br><small>$directory</small></html>"
                }
            }
        override fun getToolTipText(event: MouseEvent): String? = choices.getOrNull(rowAtPoint(event.point))?.let {
            val reasons = it.plan.eligibility.blocked.values.joinToString("; ")
            "${it.plan.entry.directory} — ${reasons.takeIf(String::isNotEmpty) ?: eligibility(it.plan)}"
        }
    }.apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = JBUI.scale(46)
        setShowGrid(false)
        setStriped(true)
        accessibleContext.accessibleName = "Worktrees to remove"
        emptyText.text = "No candidates available"
        val widths = listOf(32, 270, 90)
        widths.forEachIndexed { index, width -> columnModel.getColumn(index).apply {
            preferredWidth = JBUI.scale(width)
            if (index == 0) { minWidth = JBUI.scale(width); maxWidth = JBUI.scale(width) }
        } }
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                focusedWorktreeDirectory = choices.getOrNull(selectedRow)?.plan?.entry?.directory
                renderDetails()
            }
        }
    }
    private fun plan(candidate: WorktreeRemovalCandidate): WorktreeRemovalSelection =
        candidate.plan(cleanupMode.isSelected, closeSelectedProjects.isSelected)

    private fun makeChoice(plan: WorktreeRemovalSelection): Choice {
        val directory = plan.entry.directory
        val selectable = plan.eligibility.selectable
        return Choice(
            plan,
            JBCheckBox("$directory — ${plan.entry.branch ?: "detached"} · ${plan.eligibility.cleanupKind}",
                selectable && plan.eligibility.safe && !plan.open).apply {
                isEnabled = selectable
                addActionListener { updateSelection() }
            },
        )
    }
    val selectedPlans: List<JsonObject> get() = choices.filter { it.selected.isEnabled && it.selected.isSelected }.map { it.plan.toJson() }
    internal val deleteSelectedAction get() = okAction
    val selection: Selection get() = confirmedSelection ?: currentSelection()
    private fun currentSelection(): Selection {
        val selected = choices.filter { it.selected.isEnabled && it.selected.isSelected }
        return Selection(
            selected.map { it.plan.toJson() },
            selected.filter { deleteLocalBranches.isSelected && it.plan.entry.branch != null }
                .map { it.plan.entry.directory },
            selected.filter { discardLocalChanges.isSelected }.map { it.plan.entry.directory },
            selected.filter { it.plan.eligibility.cleanupKind == "CLOSED_UNMERGED" }.map { it.plan.entry.directory },
            cleanupMode.isSelected,
            selected.filter { closeSelectedProjects.isSelected && it.plan.open }.map { it.plan.entry.directory },
            closeSelectedProjects.isSelected, deleteLocalBranches.isSelected, discardLocalChanges.isSelected,
        )
    }
    internal val selectSafe = JButton("Select Safe").apply { addActionListener {
        choices.forEach {
            it.selected.isSelected = it.selected.isEnabled && it.plan.eligibility.safe && !it.plan.open
        }
        updateSelection()
    } }
    internal val selectAll = JButton("Select All").apply { addActionListener {
        choices.forEach { it.selected.isSelected = it.selected.isEnabled }
        updateSelection()
    } }
    internal val clearSelection = JButton("Clear").apply { addActionListener {
        choices.forEach { it.selected.isSelected = false }
        updateSelection()
    } }
    init {
        title = "Remove Worktrees"
        init()
        rootPane?.defaultButton = null
        initialSelection?.let { previous ->
            val selected = previous.plans.associateBy { it["directory"].asString }
            choices.forEach { choice ->
                val old = selected[choice.plan.entry.directory]
                choice.selected.isSelected = old != null && choice.selected.isEnabled &&
                    old["head"]?.takeUnless { it.isJsonNull }?.asString == choice.plan.entry.head &&
                    old["branch"]?.takeUnless { it.isJsonNull }?.asString == choice.plan.entry.branch &&
                    (old["safe"]?.asBoolean != true || choice.plan.eligibility.safe) &&
                    (old["cleanupKind"]?.asString == choice.plan.eligibility.cleanupKind) &&
                    (old["open"]?.asBoolean == true || !choice.plan.open)
            }
        }
        renderCandidates()
        snapshots?.let { shared ->
            scope.launch {
                shared.filterNotNull().collect { snapshot ->
                    if (confirmedSelection == null) {
                        candidates = snapshot.removalCandidates(openProjects())
                        projectCandidates()
                    }
                }
            }
        }
    }
    private fun projectCandidates() {
        candidatesTable.cellEditor?.stopCellEditing()
        val existing = choices.associateBy { it.plan.entry.directory }.toMutableMap()
        val reconciled = candidates.map { candidate ->
            val next = plan(candidate)
            val choice = existing.remove(next.entry.directory) ?: return@map makeChoice(next).apply {
                selected.isSelected = false
            }
            val previous = choice.plan
            val newlyUnsafe = previous.eligibility.safe && !next.eligibility.safe
            val newlyClosed = next.eligibility.cleanupKind == "CLOSED_UNMERGED" &&
                previous.eligibility.cleanupKind != "CLOSED_UNMERGED"
            if (!next.eligibility.selectable || !choice.selected.isEnabled || newlyUnsafe || newlyClosed ||
                (!previous.open && next.open) || WorktreeIdentity(previous.entry) != WorktreeIdentity(next.entry)) {
                choice.selected.isSelected = false
            }
            choice.plan = next
            choice.selected.isEnabled = next.eligibility.selectable
            choice
        }
        existing.values.forEach { it.selected.isSelected = false }
        choices.clear()
        choices.addAll(reconciled)
        cleanupMode.isEnabled = candidates.isNotEmpty()
        renderCandidates()
    }
    private fun renderCandidates(focusedDirectory: String? = focusedWorktreeDirectory) {
        modeDescription.text = if (cleanupMode.isSelected) "Merge or closed-review evidence required"
            else "Manual removal — merge evidence is not required"
        tableModel.fireTableDataChanged()
        val row = choices.indexOfFirst { it.plan.entry.directory == focusedDirectory }.takeIf { it >= 0 }
            ?: 0.takeIf { choices.isNotEmpty() }
        if (row != null) candidatesTable.setRowSelectionInterval(row, row)
        updateSelection()
    }
    private fun updateSelection() {
        candidatesTable.repaint()
        selectSafe.isEnabled = choices.any {
            it.selected.isEnabled && it.selected.isSelected != (it.plan.eligibility.safe && !it.plan.open)
        }
        selectAll.isEnabled = choices.any { it.selected.isEnabled && !it.selected.isSelected }
        clearSelection.isEnabled = choices.any { it.selected.isSelected }
        val count = choices.count { it.selected.isEnabled && it.selected.isSelected }
        status.text = "$count selected · ${choices.count { it.selected.isEnabled }} available"
        val destructive = deleteLocalBranches.isSelected || discardLocalChanges.isSelected
        optionsToggle.isSelected = optionsToggle.isSelected || destructive
        optionsToggle.isEnabled = !destructive
        optionsPanel.isVisible = optionsToggle.isSelected
        optionsSummary.text = when {
            deleteLocalBranches.isSelected && discardLocalChanges.isSelected -> "Delete branches and discard changes"
            deleteLocalBranches.isSelected -> "Delete local branches"
            discardLocalChanges.isSelected -> "Discard local changes"
            else -> "All off · local branches kept"
        }
        optionsSummary.foreground = if (destructive) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
        closeSelectedProjects.isVisible = choices.any { it.plan.open && !it.plan.entry.main }
        setOKButtonText(if (currentSelection().closeProjects.isNotEmpty()) "Review Project Closure…" else "Review Removal…")
        renderDetails()
        isOKActionEnabled = count > 0
    }
    private fun renderDetails() {
        val content = StringBuilder("<html><body>")
        val choice = choices.getOrNull(candidatesTable.selectedRow)
        fun detail(text: String, heading: Boolean = false) {
            content.append("<p style='margin-top:").append(if (heading) 12 else 5).append("px;margin-bottom:0'>")
            if (heading) content.append("<b>")
            content.append(StringUtil.escapeXmlEntities(text).replace("\n", "<br>"))
            if (heading) content.append("</b>")
            content.append("</p>")
        }
        if (choice == null) {
            detail("Select a worktree to inspect its details")
        } else {
            val plan = choice.plan
            detail(plan.entry.branch ?: "Detached HEAD", true)
            detail(plan.entry.directory)
            detail(if (choice.selected.isSelected) "Selected for removal" else "Not selected for removal")
            detail("Eligibility", true)
            detail(eligibility(plan))
            plan.eligibility.blocked.values.forEach { detail(it) }
            candidates.firstOrNull { it.entry.directory == plan.entry.directory }?.status?.let {
                detail("Git and review", true)
                detail(it.syncDetails)
                detail(it.reviewDetails)
            }
            detail("On removal", true)
            detail(if (deleteLocalBranches.isSelected && plan.entry.branch != null)
                "Delete local branch: ${plan.entry.branch}" else "Keep local branch")
            detail(if (discardLocalChanges.isSelected)
                "Discard local changes. Exact affected files are shown in the fresh review."
                else "Local changes block deletion; no force removal.")
            detail("Ignored contents are deleted with the directory. Remote branches are unchanged.")
            if (plan.open && closeSelectedProjects.isSelected)
                detail("Project closure requires separate confirmation before the deletion review.")
        }
        detailsPanel.text = content.append("</body></html>").toString()
        detailsPanel.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JBLabel("Repository: $repository"), BorderLayout.NORTH)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                add(cleanupMode)
                add(modeDescription.apply { border = JBUI.Borders.emptyLeft(12) })
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        val list = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                add(selectSafe); add(selectAll); add(clearSelection)
            }, BorderLayout.NORTH)
            add(JBScrollPane(candidatesTable), BorderLayout.CENTER)
            minimumSize = JBUI.size(300, 220)
        }
        val details = JBScrollPane(detailsPanel).apply { minimumSize = JBUI.size(280, 220) }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, details).apply {
            resizeWeight = 0.48
            setDividerLocation(0.48)
            preferredSize = JBUI.size(860, 300)
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(closeSelectedProjects)
            add(JPanel(BorderLayout(12, 0)).apply {
                add(optionsToggle, BorderLayout.WEST)
                add(optionsSummary, BorderLayout.CENTER)
            })
            optionsPanel.add(deleteLocalBranches)
            optionsPanel.add(discardLocalChanges)
            add(optionsPanel)
            add(JBLabel("Selected directories and ignored contents will be removed.").apply {
                border = JBUI.Borders.emptyTop(8)
            })
            add(status.apply { border = JBUI.Borders.emptyTop(4) })
        }, BorderLayout.SOUTH)
    }
    override fun getPreferredFocusedComponent(): JComponent = candidatesTable
    override fun doOKAction() {
        candidatesTable.cellEditor?.stopCellEditing()
        if (!isOKActionEnabled) return
        confirmedSelection = currentSelection()
        scope.cancel()
        super.doOKAction()
    }
    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}

