package local.worktree

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.util.io.FileUtil
import git4idea.GitWorkingTree
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import kotlinx.coroutines.CompletableDeferred
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JLabel

class WorktreeLifecycleRendererTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testFullCompactToolbarUsesThePopupActionInstances() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            val toolbar = panel.javaClass.getDeclaredField("toolbar").apply { isAccessible = true }.get(panel) as ActionToolbarImpl
            val popup = panel.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(panel) as DefaultActionGroup
            assertSame(popup, toolbar.actionGroup)
            val children = popup.getChildren(null)
            assertEquals(4, children.count { it is Separator })
            val actions = children.filterNot { it is Separator }
            assertEquals(if (SystemInfoRt.isMac) 9 else 7, actions.size)
            val expected = buildList {
                add("Create Worktree…")
                add("Open Worktree")
                if (SystemInfoRt.isMac) add("Open and Merge All…")
                add("Open Review in Worktree")
                if (SystemInfoRt.isMac) add("Open Review and Merge All…")
                add("Lock Worktree")
            }
            assertEquals(expected, actions.take(expected.size).map { it.templatePresentation.text })
            assertTrue(actions[expected.size].templatePresentation.text.orEmpty().contains("Prune", ignoreCase = true))
            assertEquals(listOf("Remove Worktrees…", "Refresh"), actions.takeLast(2).map { it.templatePresentation.text })
            assertEquals(1, actions.count { it.templatePresentation.text.orEmpty().contains("Lock Worktree") })
            assertEquals(1, actions.count { it.templatePresentation.text == "Remove Worktrees…" })
            val repositories = panel.javaClass.getDeclaredField("repositories").apply { isAccessible = true }.get(panel) as javax.swing.JComboBox<*>
            assertSame(toolbar.component.parent, repositories.parent)
            assertFalse(repositories.isVisible)
        } finally { Disposer.dispose(panel) }
    }

    fun testOpenAndMergeActionsAndWindowDescriptionsFollowLiveSnapshot() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            addRow(panel, WorktreeDisplayStatus("In sync", "Clean", "No PR", "No matching PR"))
            panel.table.setRowSelectionInterval(0, 0)
            val actions = (panel.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(panel) as DefaultActionGroup).getChildren(null)
            val open = actions.single { it.templatePresentation.text == "Open Worktree" }
            val merge = actions.singleOrNull { it.templatePresentation.text == "Open and Merge All…" }
            val windows = panel.javaClass.getDeclaredField("windows").apply { isAccessible = true }
            for ((state, text, description) in listOf(
                Triple(null, "Open Worktree", null),
                Triple("current", "Current Worktree", "Current IDE project"),
                Triple("same_window", "Switch to Worktree", "Open in this IDE window"),
                Triple("other_window", "Switch to Worktree", "Open in another IDE window"),
                Triple(null, "Open Worktree", null),
            )) {
                windows.set(panel, if (state == null) emptyMap<String, String>() else mapOf("/worktree" to state))
                val event = AnActionEvent.createFromDataContext("test", null, DataContext { null })
                open.update(event)
                assertEquals(text, event.presentation.text)
                assertEquals(state != "current", event.presentation.isEnabled)
                assertSame(if (state == null) AllIcons.Actions.MenuOpen else AllIcons.Actions.Forward, event.presentation.icon)
                if (merge != null) {
                    merge.update(event)
                    assertEquals(if (state == null) "Open and Merge All…" else "Merge All…", event.presentation.text)
                    assertTrue(event.presentation.isEnabled)
                }
                val cell = panel.table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                    panel.table, panel.table.model.getValueAt(0, 0), false, false, 0, 0,
                ) as JLabel
                if (description != null) assertTrue(cell.accessibleContext.accessibleName.contains(description))
                else assertFalse(cell.accessibleContext.accessibleName.contains("IDE project") || cell.accessibleContext.accessibleName.contains("IDE window"))
            }
        } finally { Disposer.dispose(panel) }
    }

    fun testLockToggleUpdatesItsNameTooltipAndIconWithoutDuplicateActions() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            val actions = (panel.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(panel) as DefaultActionGroup).getChildren(null)
            val lock = actions.single { it.templatePresentation.text == "Lock Worktree" }
            val rows = panel.javaClass.getDeclaredField("rows").apply { isAccessible = true }.get(panel) as MutableList<*>
            for (locked in listOf(false, true, false)) {
                rows.clear()
                addRow(panel, WorktreeDisplayStatus("In sync", "Clean", "No PR", "No matching PR"),
                    GitWorktree("/worktree", "head", "branch", false, false, if (locked) "Keep" else null, null))
                panel.table.clearSelection()
                panel.table.setRowSelectionInterval(0, 0)
                val event = AnActionEvent.createFromDataContext("test", null, DataContext { null })
                lock.update(event)
                assertTrue(event.presentation.isEnabled)
                assertEquals(if (locked) "Unlock Worktree" else "Lock Worktree", event.presentation.text)
                assertEquals(if (locked) "Unlock the selected worktree to allow removal" else "Lock the selected worktree to protect it from removal",
                    event.presentation.description)
            }
        } finally { Disposer.dispose(panel) }
    }

    fun testFailedAndCancelledTasksClearBusyAndRefreshWindowSnapshot() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            val busy = panel.javaClass.getDeclaredField("busy").apply { isAccessible = true }
            val windows = panel.javaClass.getDeclaredField("windows").apply { isAccessible = true }
            val task = panel.javaClass.declaredMethods.single { it.name == "task" }.apply { isAccessible = true }
            val message = panel.javaClass.getDeclaredField("message").apply { isAccessible = true }.get(panel) as JLabel
            val cancel = panel.javaClass.getDeclaredField("cancelTask").apply { isAccessible = true }.get(panel) as javax.swing.JButton
            val operations: List<Pair<String, suspend () -> Unit>> = listOf(
                "failed" to suspend { throw IOException("Expected failure") },
                "cancelled" to suspend { throw kotlinx.coroutines.CancellationException("Expected cancellation") },
            )
            for ((expected, operation) in operations) {
                windows.set(panel, mapOf("/worktree" to "other_window"))
                task.invoke(panel, "Opening worktree…", operation)
                PlatformTestUtil.waitWithEventsDispatching("Task clears busy after $expected", { !busy.getBoolean(panel) }, 10)
                assertTrue(message.text.contains(expected))
                assertFalse(cancel.isVisible)
                assertNull(panel.javaClass.getDeclaredField("activeTask").apply { isAccessible = true }.get(panel))
                assertFalse((windows.get(panel) as Map<*, *>).containsKey("/worktree"))
            }
        } finally { Disposer.dispose(panel) }
    }

    fun testPullRequestFailureDoesNotColorHealthyCellsOrFollowingRows() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            val field = panel.javaClass.getDeclaredField("rows").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val rows = field.get(panel) as MutableList<Any>
            val entry = GitWorktree("/worktree", "head", "branch", false, false, null, null)
            rows.add(WorktreeStatusSnapshot(entry, WorktreeDisplayStatus(
                "In sync", "Clean", "Unavailable", "Request failed", reviewError = "Request failed",
            )))
            rows.add(WorktreeStatusSnapshot(entry, WorktreeDisplayStatus("In sync", "Clean", "No PR", "No matching PR")))
            val table = panel.table
            val renderer = table.getDefaultRenderer(Any::class.java)
            fun color(row: Int, column: Int, selected: Boolean = false) = renderer.getTableCellRendererComponent(
                table, table.model.getValueAt(row, column), selected, false, row, column,
            ).foreground
            assertEquals(UIUtil.getErrorForeground(), color(0, 2))
            for (column in listOf(0, 1)) assertEquals(table.foreground, color(0, column))
            for (column in 0..2) assertEquals(table.foreground, color(1, column))
            assertEquals(table.selectionForeground, color(0, 2, selected = true))
            assertEquals(table.foreground, color(1, 0))
        } finally { Disposer.dispose(panel) }
    }

    fun testStatusIconsRetainLabelsAndDetailsWithoutRefreshCellsOrLinkedLabel() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            addRow(panel, WorktreeDisplayStatus(
                "Ahead 2", "Two local commits", "Open #12", "Review details",
                syncIcon = AllIcons.Actions.Upload,
                reviewIcon = AllIcons.Vcs.Branch,
            ))
            val table = panel.table
            assertEquals(3, table.columnCount)
            for ((index, icon) in listOf(1 to AllIcons.Actions.Upload, 2 to AllIcons.Vcs.Branch)) {
                val label = if (index == 1) "Ahead 2" else "Open #12"
                val details = if (index == 1) "Two local commits" else "Review details"
                val cell = table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                    table, table.model.getValueAt(0, index), false, false, 0, index,
                ) as JLabel
                assertEquals("", cell.text)
                assertSame(icon, cell.icon)
                assertTrue(cell.accessibleContext.accessibleName.contains(label))
                assertTrue(cell.accessibleContext.accessibleName.contains(details))
                val bounds = table.getCellRect(0, index, true)
                val tooltip = table.getToolTipText(MouseEvent(
                    table, MouseEvent.MOUSE_MOVED, 0, 0, bounds.x + 1, bounds.y + 1, 0, false,
                ))!!
                assertTrue(tooltip.contains(label))
                assertTrue(tooltip.contains(details))
                assertFalse(table.model.isCellEditable(0, index))
            }
        } finally { Disposer.dispose(panel) }
    }

    fun testSelectedRowActionsRecoverAfterBusyTaskWithoutReselection() {
        val directory = project.basePath!!
        val linkedRoot = Files.createTempDirectory("worktree-action-recovery")
        val linked = linkedRoot.resolve("linked")
        val application = ApplicationManager.getApplication()
        PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
            Files.createDirectories(Path.of(directory))
            for (arguments in listOf(
                listOf("init", "--initial-branch=panel-test"),
                listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                    "commit", "--allow-empty", "-m", "Initial"),
                listOf("worktree", "add", "-b", "linked", linked.toString()),
            )) {
                val result = CapturingProcessHandler(GeneralCommandLine(listOf("git", "-C", directory) + arguments)).runProcess(10_000)
                assertEquals(result.stderr, 0, result.exitCode)
            }
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(directory))
        }, 30_000)
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(directory, "Git")))
        PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
            assertNotNull(GitRepositoryManager.getInstance(project).getRepositoryForRoot(
                LocalFileSystem.getInstance().findFileByPath(directory)!!,
            ))
        }, 10_000)
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val panel = WorktreeLifecyclePanel(project)
        val release = CompletableDeferred<Unit>()
        try {
            val busy = panel.javaClass.getDeclaredField("busy").apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("Initial inventory finishes", { !busy.getBoolean(panel) }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Linked worktree appears", {
                (0 until panel.table.rowCount).any { panel.table.model.getValueAt(it, 0) == "linked" }
            }, 10)
            val row = (0 until panel.table.rowCount).single { panel.table.model.getValueAt(it, 0) == "linked" }
            panel.table.setRowSelectionInterval(row, row)
            val toolbar = panel.javaClass.getDeclaredField("toolbar").apply { isAccessible = true }.get(panel) as ActionToolbarImpl
            val actions = panel.javaClass.getDeclaredField("actions").apply { isAccessible = true }.get(panel) as DefaultActionGroup
            val children = actions.getChildren(null)
            val open = children.single { it.templatePresentation.text == "Open Worktree" }
            val lock = children.single { it.templatePresentation.text == "Lock Worktree" }
            fun enabled() = toolbar.getPresentation(open).isEnabled
            PlatformTestUtil.waitWithEventsDispatching("Selected row actions start enabled", { enabled() }, 10)
            val selectedKey = DataKey.create<List<GitWorkingTree>>("SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB")
            val operation: suspend () -> Unit = { release.await() }
            val activity = ActivityTracker.getInstance().count
            panel.javaClass.declaredMethods.single { it.name == "task" }.apply { isAccessible = true }
                .invoke(panel, "Refreshing remote and review status…", operation)
            assertTrue("Busy state invalidates native cached data", ActivityTracker.getInstance().count > activity)
            PlatformTestUtil.waitWithEventsDispatching("Busy task disables selected row actions", {
                !toolbar.getPresentation(open).isEnabled
            }, 10)
            assertNull(selectedKey.getData(Utils.createAsyncDataContext(panel.table)))
            val busyPopup = PresentationFactory()
            PlatformTestUtil.waitForPromise(Utils.expandActionGroupAsync(
                actions, busyPopup, Utils.createAsyncDataContext(panel.table), "WorktreeLifecycle.Popup", ActionUiKind.POPUP, false,
            ), 10_000)
            assertFalse(busyPopup.getPresentation(lock).isEnabled)
            release.complete(Unit)
            // No action.update(), toolbar refresh, or selection event is supplied by the test.
            PlatformTestUtil.waitWithEventsDispatching("Task completion restores selected row actions", {
                !busy.getBoolean(panel) && enabled()
            }, 10)
            assertEquals(listOf(row), panel.table.selectedRows.toList())
            val context = Utils.createAsyncDataContext(panel.table)
            assertEquals(linked.toRealPath().toString(), selectedKey.getData(context)!!.single().path.path)
            val popup = PresentationFactory()
            PlatformTestUtil.waitForPromise(Utils.expandActionGroupAsync(
                actions, popup, context, "WorktreeLifecycle.Popup", ActionUiKind.POPUP, false,
            ), 10_000)
            assertTrue(popup.getPresentation(open).isEnabled)
            assertTrue(popup.getPresentation(lock).isEnabled)
        } finally {
            release.complete(Unit)
            Disposer.dispose(panel)
            FileUtil.delete(linkedRoot.toFile())
        }
    }

    fun testEveryLocalStatusAppearsBeforeDelayedReviewsAndSurvivesReviewFailure() {
        val directory = project.basePath!!
        val linkedRoot = Files.createTempDirectory("worktree-local-first")
        val linked = linkedRoot.resolve("linked")
        val application = ApplicationManager.getApplication()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val releaseOne = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val reads = AtomicInteger()
        val peak = AtomicInteger()
        val fail = AtomicBoolean()
        var panel: WorktreeLifecyclePanel? = null
        var secondPanel: WorktreeLifecyclePanel? = null
        try {
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                Files.createDirectories(Path.of(directory))
                for (arguments in listOf(
                    listOf("init", "--initial-branch=local-first"),
                    listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-m", "Initial"),
                    listOf("worktree", "add", "-b", "linked", linked.toString()),
                ) + (1..4).map { listOf("worktree", "add", "-b", "linked-$it", linkedRoot.resolve("linked-$it").toString()) }) {
                    val result = CapturingProcessHandler(GeneralCommandLine(listOf("git", "-C", directory) + arguments)).runProcess(10_000)
                    assertEquals(result.stderr, 0, result.exitCode)
                }
                Files.writeString(linked.resolve("untracked.txt"), "local change")
                assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(directory))
            }, 30_000)
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(directory, "Git")))
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                assertNotNull(GitRepositoryManager.getInstance(project).getRepositoryForRoot(
                    LocalFileSystem.getInstance().findFileByPath(directory)!!))
            }, 10_000)
            project.replaceService(WorktreeReviewStatusProvider::class.java, object : WorktreeReviewStatusProvider {
                override suspend fun read(repository: GitRepository, remoteName: String?, branch: String): WorktreeReviewStatus {
                    reads.incrementAndGet()
                    val count = active.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    if (count == 4) started.complete(Unit)
                    try {
                        if (branch == "linked") releaseOne.await() else release.await()
                        if (fail.get()) throw IOException("Review offline")
                        return WorktreeReviewStatus("Open #42", "Review snapshot", icon = AllIcons.Vcs.Branch)
                    } finally { active.decrementAndGet() }
                }
                override fun invalidate() {}
            }, testRootDisposable)
            val view = WorktreeLifecyclePanel(project)
            panel = view
            view.addNotify()
            assertTrue(view.isShowing)
            val busy = view.javaClass.getDeclaredField("busy").apply { isAccessible = true }
            val inventory = view.javaClass.getDeclaredField("inventory").apply { isAccessible = true }
            fun refreshing() = (inventory.get(view) as? WorktreeInventory)?.snapshots?.value?.refreshing == true
            PlatformTestUtil.waitWithEventsDispatching("Four review requests overlap", { started.isCompleted }, 10)
            assertTrue(refreshing())
            assertEquals(6, view.table.rowCount)
            val linkedRow = (0 until view.table.rowCount).single { view.table.model.getValueAt(it, 0) == "linked" }
            val other = WorktreeLifecyclePanel(project)
            secondPanel = other
            PlatformTestUtil.waitWithEventsDispatching("Second panel renders the same progressive snapshot", { other.table.rowCount == 6 }, 10)
            assertSame(inventory.get(view), inventory.get(other))
            assertEquals("A second panel does not start another review refresh", 4, reads.get())
            for (row in 0 until view.table.rowCount) {
                assertTrue(view.table.model.getValueAt(row, 1).toString().startsWith("Unpublished"))
                assertEquals("Loading…", view.table.model.getValueAt(row, 2))
            }
            assertTrue(view.table.model.getValueAt(linkedRow, 1).toString().contains("Untracked (1)"))
            fun icon(row: Int, column: Int) = (view.table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                view.table, view.table.model.getValueAt(row, column), false, false, row, column,
            ) as JLabel).icon
            assertTrue(icon(linkedRow, 2) is AnimatedIcon)
            assertFalse(icon(linkedRow, 1) is AnimatedIcon)
            releaseOne.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Completed review appears while others wait", {
                view.table.model.getValueAt(linkedRow, 2) == "Open #42"
            }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Completed review fans out to both panels", {
                other.table.model.getValueAt(linkedRow, 2) == "Open #42"
            }, 10)
            Disposer.dispose(other)
            secondPanel = null
            assertTrue(refreshing())
            assertSame(AllIcons.Vcs.Branch, icon(linkedRow, 2))
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Reviews finish", { !refreshing() }, 10)
            assertEquals(4, peak.get())
            assertEquals("Open #42", view.table.model.getValueAt(linkedRow, 2))
            assertEquals("First showing reuses the initial load", 6, reads.get())
            view.removeNotify()
            view.addNotify()
            PlatformTestUtil.waitWithEventsDispatching("Showing again reconciles inventory", {
                !refreshing() && !busy.getBoolean(view) && reads.get() >= 12
            }, 10)
            assertEquals(12, reads.get())
            fail.set(true)
            view.javaClass.getDeclaredMethod("reload", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(view, false)
            PlatformTestUtil.waitWithEventsDispatching("Failed reviews finish", {
                !busy.getBoolean(view) && !refreshing() && reads.get() >= 18
            }, 10)
            assertTrue(view.table.model.getValueAt(linkedRow, 1).toString().contains("Untracked (1)"))
            assertEquals("Open #42 · Cached", view.table.model.getValueAt(linkedRow, 2))
            val reviewCell = view.table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                view.table, view.table.model.getValueAt(linkedRow, 2), false, false, linkedRow, 2,
            ) as JLabel
            assertTrue(reviewCell.toolTipText.contains("Review offline"))
            assertTrue(reviewCell.toolTipText.contains("cached review evidence"))
        } finally {
            releaseOne.complete(Unit)
            release.complete(Unit)
            panel?.let { Disposer.dispose(it) }
            secondPanel?.let { Disposer.dispose(it) }
            FileUtil.delete(linkedRoot.toFile())
        }
    }

    fun testSharedInventoryUpdatesBusyPanelWithoutOwningOperationStateAndPreservesSelection() {
        val directory = project.basePath!!
        val linkedRoot = Files.createTempDirectory("worktree-inventory-panel")
        val first = linkedRoot.resolve("first")
        val latest = linkedRoot.resolve("latest")
        val application = ApplicationManager.getApplication()
        val release = CompletableDeferred<Unit>()
        var panel: WorktreeLifecyclePanel? = null
        fun git(vararg arguments: String) {
            val result = CapturingProcessHandler(GeneralCommandLine(listOf("git", "-C", directory) + arguments)).runProcess(10_000)
            assertEquals(result.stderr, 0, result.exitCode)
        }
        try {
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                Files.createDirectories(Path.of(directory))
                git("init", "--initial-branch=inventory-panel")
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "Initial")
                assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(directory))
            }, 30_000)
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(directory, "Git")))
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                assertNotNull(GitRepositoryManager.getInstance(project).getRepositoryForRoot(
                    LocalFileSystem.getInstance().findFileByPath(directory)!!))
            }, 10_000)
            val view = WorktreeLifecyclePanel(project)
            panel = view
            val busy = view.javaClass.getDeclaredField("busy").apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("Initial inventory", {
                !busy.getBoolean(view) && view.table.rowCount == 1
            }, 10)
            view.table.setRowSelectionInterval(0, 0)
            val inventory = view.javaClass.getDeclaredField("inventory").apply { isAccessible = true }.get(view) as WorktreeInventory
            val operation: suspend () -> Unit = { release.await() }
            view.javaClass.declaredMethods.single { it.name == "task" }.apply { isAccessible = true }
                .invoke(view, "Opening worktree…", operation)
            val newest = PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Long> {
                git("worktree", "add", "-b", "first", first.toString())
                kotlinx.coroutines.runBlocking { inventory.refresh() }
                git("worktree", "remove", first.toString())
                git("worktree", "add", "-b", "latest", latest.toString())
                kotlinx.coroutines.runBlocking { inventory.refresh().version }
            }, 30_000)
            PlatformTestUtil.waitWithEventsDispatching("Latest shared inventory renders during busy operation", {
                view.table.rowCount == 2 &&
                    (0 until view.table.rowCount).map { view.table.model.getValueAt(it, 0) }.toSet() == setOf("inventory-panel", "latest")
            }, 10)
            assertTrue(busy.getBoolean(view))
            assertTrue(inventory.snapshots.value!!.version >= newest)
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Operation finishes without a second inventory reload", {
                !busy.getBoolean(view) && view.table.rowCount == 2 &&
                    (0 until view.table.rowCount).map { view.table.model.getValueAt(it, 0) }.toSet() == setOf("inventory-panel", "latest")
            }, 10)
            assertEquals("inventory-panel", view.table.model.getValueAt(view.table.selectedRow, 0))
            assertTrue(inventory.snapshots.value!!.version >= newest)
        } finally {
            release.complete(Unit)
            panel?.let { Disposer.dispose(it) }
            FileUtil.delete(linkedRoot.toFile())
        }
    }

    fun testLockDoubleClickAndUnlockRefreshSelectedWorktreeWithoutManualRefresh() {
        val directory = project.basePath!!
        val linkedRoot = Files.createTempDirectory("worktree-lock-test")
        val linked = linkedRoot.resolve("linked")
        val application = ApplicationManager.getApplication()
        var panel: WorktreeLifecyclePanel? = null
        try {
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                Files.createDirectories(Path.of(directory))
                for (arguments in listOf(
                    listOf("init", "--initial-branch=panel-lock"),
                    listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-m", "Initial"),
                    listOf("worktree", "add", "-b", "linked", linked.toString()),
                )) {
                    val result = CapturingProcessHandler(GeneralCommandLine(listOf("git", "-C", directory) + arguments)).runProcess(10_000)
                    assertEquals(result.stderr, 0, result.exitCode)
                }
                assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(directory))
            }, 30_000)
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(directory, "Git")))
            PlatformTestUtil.waitForFuture(application.executeOnPooledThread<Unit> {
                assertNotNull(GitRepositoryManager.getInstance(project).getRepositoryForRoot(
                    LocalFileSystem.getInstance().findFileByPath(directory)!!))
            }, 10_000)
            val view = WorktreeLifecyclePanel(project)
            panel = view
            val busy = view.javaClass.getDeclaredField("busy").apply { isAccessible = true }
            PlatformTestUtil.waitWithEventsDispatching("Initial inventory", { !busy.getBoolean(view) }, 10)
            val row = (0 until view.table.rowCount).single { view.table.model.getValueAt(it, 0) == "linked" }
            view.table.setRowSelectionInterval(row, row)
            val toggle = view.javaClass.getDeclaredMethod("toggleLock").apply { isAccessible = true }
            toggle.invoke(view)
            toggle.invoke(view)
            PlatformTestUtil.waitWithEventsDispatching("Lock finishes", { !busy.getBoolean(view) }, 10)
            assertEquals(row, view.table.selectedRow)
            fun stateDescription(): String = (view.table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                view.table, view.table.model.getValueAt(row, 0), false, false, row, 0,
            ) as JLabel).accessibleContext.accessibleName
            assertTrue(stateDescription().contains("Worktree locked"))
            val status = view.javaClass.getDeclaredField("message").apply { isAccessible = true }.get(view) as JLabel
            assertFalse(status.text.contains("failed"))
            toggle.invoke(view)
            PlatformTestUtil.waitWithEventsDispatching("Unlock finishes", { !busy.getBoolean(view) }, 10)
            assertEquals(row, view.table.selectedRow)
            assertFalse(stateDescription().contains("Worktree locked"))
        } finally {
            panel?.let { Disposer.dispose(it) }
            FileUtil.delete(linkedRoot.toFile())
        }
    }

    fun testDirtyMarkerDoesNotReplaceSyncStatusOrLeakToCleanRows() {
        val panel = WorktreeLifecyclePanel(project)
        try {
            val clean = WorktreeDisplayStatus("In sync", "Clean", "No PR", "No matching PR",
                syncIcon = AllIcons.General.GreenCheckmark)
            addRow(panel, clean.copy(hasLocalChanges = true))
            addRow(panel, clean)
            val table = panel.table
            fun cell(row: Int) = table.getDefaultRenderer(Any::class.java).getTableCellRendererComponent(
                table, table.model.getValueAt(row, 1), false, false, row, 1,
            ) as JLabel
            val dirty = cell(0)
            val icons = dirty.icon as RowIcon
            assertSame(clean.syncIcon, icons.getIcon(0))
            assertSame(AllIcons.Actions.Edit, icons.getIcon(1))
            assertTrue(dirty.accessibleContext.accessibleName.contains("Local changes"))
            assertSame(clean.syncIcon, cell(1).icon)
            assertFalse(cell(1).accessibleContext.accessibleName.contains("Local changes"))
        } finally { Disposer.dispose(panel) }
    }

    private fun addRow(panel: WorktreeLifecyclePanel, status: WorktreeDisplayStatus,
                       entry: GitWorktree = GitWorktree("/worktree", "head", "branch", false, false, null, null)) {
        @Suppress("UNCHECKED_CAST")
        val rows = panel.javaClass.getDeclaredField("rows").apply { isAccessible = true }.get(panel) as MutableList<Any>
        rows.add(WorktreeStatusSnapshot(entry, status))
    }
}
