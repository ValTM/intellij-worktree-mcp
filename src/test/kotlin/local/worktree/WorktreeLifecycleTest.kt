package local.worktree

import com.google.gson.JsonObject
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentEP
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vcs.changes.ui.VcsToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.TabbedPaneContentUI
import com.intellij.ui.content.impl.ContentManagerImpl
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield
import com.google.gson.JsonParser
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JPanel

class WorktreeLifecycleTest : HeavyPlatformTestCase() {
    fun testTabIsNonclosableAndShowsPluginIconBeforePanelInitialization() {
        val content = ContentFactory.getInstance().createContent(JPanel(), WORKTREE_LIFECYCLE_TAB, false)
        try {
            content.isCloseable = true
            WorktreeLifecyclePreloader().preloadTabContent(content)
            assertFalse(content.isCloseable)
            assertSame(WorktreeLifecycleIcons.Lifecycle, content.icon)
            assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
            assertNotNull(content.description)
            assertEquals(
                ChangesViewContentManager.TabOrderWeight.VCS_LOG.weight + 1,
                content.getUserData(ChangesViewContentManager.ORDER_WEIGHT_KEY),
            )
        } finally { Disposer.dispose(content) }
    }

    fun testNativeLifecycleCreatesReusesAndRestoresTabAfterLogWithoutActivation() {
        val extension = ChangesViewContentEP.EP_NAME.getExtensions(project)
            .single { it.className == WorktreeLifecycleContentProvider::class.java.name }
        val predicate = extension.predicateClassName
        // Exercise the native EP lifecycle independently of asynchronous Git discovery.
        extension.predicateClassName = null
        val manager = ChangesViewContentManager.getInstance(project)
        val log = ContentFactory.getInstance().createContent(JPanel(), ChangesViewContentManager.VCS_LOG, false)
        val console = ContentFactory.getInstance().createContent(JPanel(), ChangesViewContentManager.CONSOLE, false)
        val workingTrees = ContentFactory.getInstance().createContent(JPanel(), ChangesViewContentManager.WORKING_TREES, false)
        manager.addContent(log)
        manager.addContent(console)
        manager.addContent(workingTrees)
        val windows = ToolWindowManager.getInstance(project)
        val contentsManager = ContentManagerImpl(TabbedPaneContentUI(), true, project, testRootDisposable)
        val component = JPanel()
        var activationRequests = 0
        var showRequests = 0
        // The stock headless window has an empty ID and returns a new component each call.
        val window = object : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
            override fun getId(): String = ChangesViewContentManager.TOOLWINDOW_ID
            override fun getComponent(): javax.swing.JComponent = component
            override fun getContentManager(): ContentManager = contentsManager
            override fun getContentManagerIfCreated(): ContentManager = contentsManager
            override fun getDisposable(): Disposable = contentsManager
            override fun activate(runnable: Runnable?, autoFocusContents: Boolean, forced: Boolean) {
                activationRequests++
            }
            override fun show(runnable: Runnable?) {
                showRequests++
            }
        }
        val factory = object : VcsToolWindowFactory() {
            override fun isAvailable(project: com.intellij.openapi.project.Project): Boolean = true
        }
        try {
            factory.init(window)
            factory.createToolWindowContent(project, window)
            window.contentManager.setSelectedContent(log)
            val activeWindow = windows.activeToolWindowId
            val visible = window.isVisible
            val lifecycle = manager.findContent(WORKTREE_LIFECYCLE_TAB)!!
            val contents = window.contentManager.contents.toList()
            assertEquals(contents.indexOf(log) + 1, contents.indexOf(lifecycle))
            assertTrue(contents.indexOf(lifecycle) < contents.indexOf(console))
            assertTrue(contents.indexOf(lifecycle) < contents.indexOf(workingTrees))

            fun repositoryMappingChanged() {
                project.messageBus.syncPublisher(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED).mappingChanged()
                UIUtil.dispatchAllInvocationEvents()
            }
            repositoryMappingChanged()
            assertSame(lifecycle, manager.findContent(WORKTREE_LIFECYCLE_TAB))
            assertEquals(1, manager.findContents { it.tabName == WORKTREE_LIFECYCLE_TAB }.size)

            manager.removeContent(lifecycle)
            repositoryMappingChanged()
            val restored = manager.findContent(WORKTREE_LIFECYCLE_TAB)!!
            assertNotSame(lifecycle, restored)
            assertFalse(restored.isCloseable)
            assertEquals(
                window.contentManager.contents.indexOf(log) + 1,
                window.contentManager.contents.indexOf(restored),
            )
            assertSame(log, window.contentManager.selectedContent)
            assertEquals(activeWindow, windows.activeToolWindowId)
            assertEquals(visible, window.isVisible)
            assertEquals(0, activationRequests)
            assertEquals(0, showRequests)
        } finally {
            extension.predicateClassName = predicate
        }
    }

    fun testCleanupUsesSharedDomainSelectionAndSafety() {
        val safe = preview("/worktrees/safe")
        val dirty = preview("/worktrees/dirty", safe = false)
        val closed = preview("/worktrees/closed", safe = false, kind = "CLOSED_UNMERGED")
        val blocked = preview("/worktrees/blocked", selectable = false, safe = false, kind = "BLOCKED")
        val dialog = removalDialog(listOf(safe, dirty, closed, blocked))
        try {
            assertEquals(listOf(safe), dialog.selectedPlans)
            assertTrue(dialog.isOKActionEnabled)
            assertFalse(dialog.choices[3].selected.isEnabled)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertTrue(dialog.selection.includeClosedUnmerged.isEmpty())
            assertFalse(dialog.selectSafe.isEnabled)
            assertTrue(dialog.selectAll.isEnabled)
            assertTrue(dialog.clearSelection.isEnabled)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testSelectAllIncludesDirtyAndClosedButNeverEnablesDestructionOrBlockedRows() {
        val candidates = listOf(
            preview("/worktrees/safe"),
            preview("/worktrees/dirty", safe = false),
            preview("/worktrees/closed", safe = false, kind = "CLOSED_UNMERGED"),
            preview("/worktrees/main", selectable = false, safe = false, kind = "BLOCKED"),
            preview("/worktrees/open", selectable = false, safe = false, kind = "BLOCKED"),
            preview("/worktrees/locked", selectable = false, safe = false, kind = "BLOCKED"),
        )
        val dialog = removalDialog(candidates)
        try {
            dialog.selectAll.doClick()
            assertEquals(candidates.take(3), dialog.selectedPlans)
            assertEquals(listOf("/worktrees/closed"), dialog.selection.includeClosedUnmerged)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertFalse(dialog.selectAll.isEnabled)
            assertTrue(dialog.selectSafe.isEnabled)
            dialog.selectSafe.doClick()
            assertEquals(listOf(candidates.first()), dialog.selectedPlans)
            assertTrue(dialog.selection.includeClosedUnmerged.isEmpty())
            assertFalse(dialog.selectSafe.isEnabled)
            dialog.clearSelection.doClick()
            assertFalse(dialog.clearSelection.isEnabled)
            assertTrue(dialog.selectSafe.isEnabled)
            assertTrue(dialog.selectAll.isEnabled)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testBlockedCleanupCandidatesDisableSelectionActions() {
        val dialog = removalDialog(listOf(
            preview("/worktrees/blocked", selectable = false, safe = false, kind = "BLOCKED"),
        ))
        try {
            assertFalse(dialog.selectSafe.isEnabled)
            assertFalse(dialog.selectAll.isEnabled)
            assertFalse(dialog.clearSelection.isEnabled)
            assertFalse(dialog.isOKActionEnabled)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testDestructiveOptionsRequireSeparateChecksAndUseSelectedAbsoluteDirectories() {
        val safe = preview("/worktrees/safe")
        val dirty = preview("/worktrees/dirty", safe = false)
        val dialog = removalDialog(listOf(safe, dirty))
        try {
            dialog.deleteLocalBranches.doClick()
            dialog.choices[1].selected.doClick()
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            dialog.discardLocalChanges.doClick()
            assertEquals(listOf("/worktrees/safe", "/worktrees/dirty"), dialog.selection.deleteLocalBranches)
            assertEquals(listOf("/worktrees/safe", "/worktrees/dirty"), dialog.selection.deleteLocalChanges)
            dialog.clearSelection.doClick()
            assertTrue(dialog.selectedPlans.isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertTrue(dialog.deleteLocalBranches.isSelected)
            assertTrue(dialog.discardLocalChanges.isSelected)
            dialog.selectSafe.doClick()
            assertEquals(listOf("/worktrees/safe"), dialog.selection.deleteLocalBranches)
            assertEquals(listOf("/worktrees/safe"), dialog.selection.deleteLocalChanges)
            dialog.selectAll.doClick()
            assertEquals(listOf("/worktrees/safe", "/worktrees/dirty"), dialog.selection.deleteLocalChanges)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testUnknownSelectionFlagsDoNotAuthorizeOrAutomaticallySelectRows() {
        val unknownSelection = preview("/worktrees/unknown").apply { remove("selectable") }
        val unknownSafety = preview("/worktrees/manual").apply { remove("safe") }
        val dialog = removalDialog(listOf(unknownSelection, unknownSafety), cleanup = false)
        try {
            assertTrue(dialog.selectedPlans.isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            dialog.selectAll.doClick()
            assertEquals(listOf("/worktrees/manual"), dialog.selectedPlans.map { it["directory"].asString })
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
        } finally { dialog.disposeIfNeeded() }
    }

    fun testGlobalBranchDeletionPassesAllSelectedBranchesToFreshValidation() {
        val present = preview("/worktrees/published").apply { addProperty("remoteBranchAbsent", false) }
        val unknown = preview("/worktrees/unknown-remote").apply { remove("remoteBranchAbsent") }
        val absent = preview("/worktrees/deleted-remote")
        val dialog = removalDialog(listOf(present, unknown, absent))
        try {
            dialog.deleteLocalBranches.doClick()
            assertEquals(listOf("/worktrees/published", "/worktrees/unknown-remote", "/worktrees/deleted-remote"),
                dialog.selection.deleteLocalBranches)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testRemovalChoicesImmediatelyReuseMainWindowRowsAndOpenProjectsSnapshot() {
        val status = WorktreeDisplayStatus("Any display wording", "Local snapshot", "Any review wording", "Review snapshot",
            localStatusKnown = true, cleanupKind = "MERGED")
        fun row(name: String, snapshot: WorktreeDisplayStatus?, failure: String? = null): WorktreeStatusSnapshot {
            val entry = GitWorktree("/worktrees/$name", "head", name, false, false, null, null)
            return WorktreeStatusSnapshot(entry, snapshot?.copy(identity = WorktreeIdentity(entry)), failure)
        }
        val rows = listOf(row("safe", status), row("dirty", status.copy(hasLocalChanges = true)), row("open", status),
            row("closed-review", status.copy(cleanupKind = "CLOSED_UNMERGED")), row("unknown", null), row("failed", status, "Git failed"))
        val snapshot = WorktreeInventorySnapshot(rows.map { it.entry }, null, 1, rows)
        val candidates = snapshot.removalCandidates(listOf("/worktrees/open/nested"))
        assertEquals(6, candidates.size)
        assertTrue(candidates[2].open)
        assertFalse(candidates[2].eligibility(false).selectable)
        assertTrue(candidates[2].eligibility(false, true).selectable)
        assertTrue(candidates.all { !it.plan(true).toJson().has("confirmation") })
            val dialog = WorktreeRemovalDialog(project, candidates)
            try {
                assertEquals(6, dialog.candidatesTable.rowCount)
                assertEquals(listOf("/worktrees/safe"), dialog.selectedPlans.map { it["directory"].asString })
                assertTrue(dialog.closeSelectedProjects.isSelected)
                assertFalse(dialog.deleteLocalBranches.isSelected)
                assertFalse(dialog.discardLocalChanges.isSelected)
                assertFalse(dialog.choices[4].selected.isEnabled)
                assertFalse(dialog.choices[5].selected.isEnabled)
                dialog.selectAll.doClick()
                assertEquals(listOf("/worktrees/open"), dialog.selection.closeProjects)
                assertEquals(listOf("/worktrees/closed-review"), dialog.selection.includeClosedUnmerged)
                assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
                assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            } finally { dialog.disposeIfNeeded() }
        assertEquals(candidates, snapshot.removalCandidates(listOf("/worktrees/open/nested")))
    }

    fun testCleanupTogglesReuseChoicesAndPreserveExplicitOptionsWithoutReselectingRows() {
        val first = candidate("/worktrees/first")
        val second = candidate("/worktrees/second")
        val dialog = WorktreeRemovalDialog(project, listOf(first, second))
        try {
            val firstChoice = dialog.choices.first()
            dialog.deleteLocalBranches.doClick()
            dialog.discardLocalChanges.doClick()
            firstChoice.selected.doClick()
            dialog.cleanupMode.doClick()
            assertEquals("NONE", firstChoice.plan.eligibility.cleanupKind)
            dialog.cleanupMode.doClick()
            assertEquals("MERGED", firstChoice.plan.eligibility.cleanupKind)
            assertSame(firstChoice, dialog.choices.first())
            assertFalse(firstChoice.selected.isSelected)
            assertEquals(listOf("/worktrees/second"), dialog.selection.deleteLocalBranches)
            assertEquals(listOf("/worktrees/second"), dialog.selection.deleteLocalChanges)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testCleanupChangesDoNotOptIntoNewlyEligibleOrClosedUnmergedCandidates() {
        val dialog = WorktreeRemovalDialog(project, listOf(
            candidate("/worktrees/manual", kind = "BLOCKED"),
            candidate("/worktrees/closed", kind = "CLOSED_UNMERGED"),
        ))
        try {
            dialog.cleanupMode.doClick()
            assertTrue(dialog.selectedPlans.isEmpty())
            assertTrue(dialog.choices.all { it.selected.isEnabled })
            dialog.selectSafe.doClick()
            dialog.deleteLocalBranches.doClick()
            dialog.discardLocalChanges.doClick()
            dialog.cleanupMode.doClick()
            assertTrue(dialog.selectedPlans.isEmpty())
            assertTrue(dialog.selection.includeClosedUnmerged.isEmpty())
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            dialog.choices[1].selected.doClick()
            assertEquals(listOf("/worktrees/closed"), dialog.selection.includeClosedUnmerged)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testOpenRemovalDialogReconcilesSharedInventoryWithoutReselectingAndStopsOnApproval() {
        var version = 0L
        fun snapshot(vararg candidates: WorktreeRemovalCandidate): WorktreeInventorySnapshot {
            val rows = candidates.map { WorktreeStatusSnapshot(it.entry, it.status, it.failure) }
            return WorktreeInventorySnapshot(rows.map { it.entry }, null, ++version, rows)
        }
        val first = candidate("/worktrees/first")
        val keep = candidate("/worktrees/keep")
        val late = candidate("/worktrees/late", kind = "BLOCKED")
        val shared = MutableStateFlow<WorktreeInventorySnapshot?>(snapshot(first, keep, late))
        val tableScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val tableObserver = tableScope.launch { shared.collect {} }
        val openProjects = mutableListOf<String>()
        val dialog = WorktreeRemovalDialog(project, shared, { openProjects })
        try {
            assertEquals(3, dialog.candidatesTable.rowCount)
            assertEquals(listOf("/worktrees/first", "/worktrees/keep"), dialog.selectedPlans.map { it["directory"].asString })
            val firstChoice = dialog.choices[0]
            val lateChoice = dialog.choices[2]
            firstChoice.selected.doClick()
            dialog.deleteLocalBranches.doClick()
            dialog.discardLocalChanges.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Both views observe the shared inventory",
                { shared.subscriptionCount.value == 2 }, 10)

            val merged = late.copy(status = late.status!!.copy(cleanupKind = "MERGED"))
            val arriving = candidate("/worktrees/arriving")
            shared.value = snapshot(merged, keep, first, arriving)
            PlatformTestUtil.waitWithEventsDispatching("Late merge proof updates the existing dialog",
                { dialog.choices.size == 4 && lateChoice.selected.isEnabled }, 10)
            assertSame(lateChoice, dialog.choices[0])
            assertSame(firstChoice, dialog.choices[2])
            assertEquals("Merged — can be removed", dialog.candidatesTable.model.getValueAt(0, 2))
            assertEquals(listOf("/worktrees/keep"), dialog.selectedPlans.map { it["directory"].asString })
            assertTrue(dialog.cleanupMode.isSelected)
            assertTrue(dialog.closeSelectedProjects.isSelected)
            assertTrue(dialog.deleteLocalBranches.isSelected)
            assertTrue(dialog.discardLocalChanges.isSelected)

            lateChoice.selected.doClick()
            dialog.choices[3].selected.doClick()
            firstChoice.selected.doClick()
            openProjects.add(arriving.entry.directory)
            shared.value = snapshot(merged.copy(status = merged.status!!.copy(hasLocalChanges = true)), keep, arriving)
            PlatformTestUtil.waitWithEventsDispatching("Unsafe, open and removed targets reconcile",
                { dialog.choices.size == 3 && dialog.choices[2].plan.open }, 10)
            assertEquals(listOf("/worktrees/keep"), dialog.selectedPlans.map { it["directory"].asString })
            assertFalse(lateChoice.selected.isSelected)
            assertFalse(dialog.choices[2].selected.isSelected)
            assertFalse(firstChoice.selected.isSelected)

            shared.value = snapshot(merged, keep, candidate("/worktrees/closed", kind = "CLOSED_UNMERGED"))
            PlatformTestUtil.waitWithEventsDispatching("Safe-again and closed-review targets stay unselected",
                { dialog.choices.last().plan.entry.directory == "/worktrees/closed" }, 10)
            assertEquals(listOf("/worktrees/keep"), dialog.selectedPlans.map { it["directory"].asString })
            lateChoice.selected.doClick()
            shared.value = snapshot(merged.copy(entry = merged.entry.copy(locked = "Keep")), keep,
                candidate("/worktrees/closed", kind = "CLOSED_UNMERGED"))
            PlatformTestUtil.waitWithEventsDispatching("New blockers deselect previously selected targets",
                { !lateChoice.selected.isEnabled }, 10)
            assertFalse(lateChoice.selected.isSelected)
            assertEquals(listOf("/worktrees/keep"), dialog.selectedPlans.map { it["directory"].asString })
            val choicesAtApproval = dialog.choices.toList()
            dialog.deleteSelectedAction.actionPerformed(java.awt.event.ActionEvent(dialog, java.awt.event.ActionEvent.ACTION_PERFORMED, "preview"))
            val approved = dialog.selection
            PlatformTestUtil.waitWithEventsDispatching("Only the dialog observer is cancelled",
                { shared.subscriptionCount.value == 1 }, 10)
            shared.value = snapshot()
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(tableObserver.isActive)
            assertEquals(choicesAtApproval, dialog.choices)
            assertSame(approved, dialog.selection)
            assertEquals(listOf("/worktrees/keep"), approved.plans.map { it["directory"].asString })
            assertEquals(listOf("/worktrees/keep"), approved.deleteLocalBranches)
            assertEquals(listOf("/worktrees/keep"), approved.deleteLocalChanges)
        } finally {
            dialog.disposeIfNeeded()
            tableScope.cancel()
        }
    }

    fun testDisposingRemovalDialogCancelsOnlyItsSharedInventorySubscription() {
        val shared = MutableStateFlow<WorktreeInventorySnapshot?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val otherObserver = scope.launch { shared.collect {} }
        val dialog = WorktreeRemovalDialog(project, shared, { emptyList() })
        try {
            PlatformTestUtil.waitWithEventsDispatching("Dialog subscribes without an initial snapshot",
                { shared.subscriptionCount.value == 2 }, 10)
            dialog.disposeIfNeeded()
            PlatformTestUtil.waitWithEventsDispatching("Disposal leaves the shared observer alive",
                { shared.subscriptionCount.value == 1 }, 10)
            val entry = candidate("/worktrees/after-close")
            shared.value = WorktreeInventorySnapshot(listOf(entry.entry), null, 1,
                listOf(WorktreeStatusSnapshot(entry.entry, entry.status)))
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(otherObserver.isActive)
            assertTrue(dialog.choices.isEmpty())
        } finally {
            dialog.disposeIfNeeded()
            scope.cancel()
        }
    }

    fun testAcceptingRemovalSelectionFreezesSnapshotChoices() {
        val first = candidate("/worktrees/one")
        val dialog = WorktreeRemovalDialog(project, listOf(first))
        try {
            assertTrue(dialog.isOKActionEnabled)
            dialog.deleteLocalBranches.doClick()
            dialog.deleteSelectedAction.actionPerformed(java.awt.event.ActionEvent(dialog, java.awt.event.ActionEvent.ACTION_PERFORMED, "preview"))
            val approved = dialog.selection
            dialog.choices.single().selected.isSelected = false
            dialog.cleanupMode.isSelected = false
            assertSame(approved, dialog.selection)
            assertEquals(listOf(first.plan(true, true).toJson()), approved.plans)
            assertEquals(listOf("/worktrees/one"), approved.deleteLocalBranches)
            assertTrue(approved.cleanup)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testClosingProjectsDefaultsOnButRequiresExplicitSelectionOfExactDirectories() {
        val closed = candidate("/worktrees/closed")
        val dialog = WorktreeRemovalDialog(project, listOf(
            closed, candidate("/worktrees/open", open = true), candidate("/worktrees/locked-open", open = true, locked = true),
        ))
        try {
            assertTrue(dialog.closeSelectedProjects.isSelected)
            assertEquals(listOf(closed.plan(true, true).toJson()), dialog.selectedPlans)
            assertTrue(dialog.selection.closeProjects.isEmpty())
            assertTrue(dialog.choices[1].selected.isEnabled)
            assertFalse("Enabling closure must not select the newly eligible project", dialog.choices[1].selected.isSelected)
            assertFalse(dialog.choices[2].selected.isEnabled)
            dialog.selectSafe.doClick()
            assertEquals(listOf(closed.plan(true, true).toJson()), dialog.selectedPlans)
            dialog.choices[1].selected.doClick()
            assertEquals(listOf("/worktrees/open"), dialog.selection.closeProjects)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            dialog.closeSelectedProjects.doClick()
            assertFalse(dialog.choices[1].selected.isEnabled)
            assertFalse(dialog.choices[1].selected.isSelected)
            assertTrue(dialog.selection.closeProjects.isEmpty())
        } finally { dialog.disposeIfNeeded() }
    }

    fun testStructuredSnapshotEvidenceIgnoresDisplayWordingAndBlocksUnknownOrProtectedRows() {
        val entry = GitWorktree("/worktrees/snapshot", "head", "branch", false, false, null, null)
        val known = WorktreeDisplayStatus("Clean", "Clean", "Merged #42", "Merged",
            localStatusKnown = true, cleanupKind = "MERGED", identity = WorktreeIdentity(entry))
        val unknown = listOf(null, known.copy(localStatusKnown = false), known.copy(error = "HEAD changed"))
        for (status in unknown) {
            val candidate = WorktreeRemovalCandidate.fromSnapshot(entry, status, open = false)
            assertFalse(candidate.eligibility(false).selectable)
            assertFalse(candidate.eligibility(true).safe)
        }
        for (protected in listOf(entry.copy(main = true), entry.copy(bare = true),
            entry.copy(locked = ""), entry.copy(prunable = ""))) {
            val candidate = WorktreeRemovalCandidate.fromSnapshot(protected, known, open = true)
            assertFalse(candidate.eligibility(false, true).selectable)
            assertFalse(candidate.eligibility(true, true).selectable)
        }
        val unproven = WorktreeRemovalCandidate(entry, known.copy(cleanupKind = "BLOCKED"), open = false)
        assertFalse(unproven.eligibility(true).selectable)
        assertTrue(unproven.eligibility(false).selectable)
        for (status in listOf(known.copy(reviewCached = true), known.copy(reviewLoading = true),
            known.copy(syncLabel = "Unknown · Dirty", reviewLabel = "Closed without merging"))) {
            val candidate = WorktreeRemovalCandidate(entry, status, open = false)
            assertTrue(candidate.eligibility(true).safe)
            assertEquals("MERGED", candidate.eligibility(true).cleanupKind)
        }
        val dirty = WorktreeRemovalCandidate(entry, known.copy(hasLocalChanges = true), open = false)
        assertTrue(dirty.eligibility(true).selectable)
        assertFalse(dirty.eligibility(true).safe)
        for (changed in listOf(entry.copy(head = "other"), entry.copy(branch = "other"), entry.copy(directory = "/other"))) {
            assertFalse(WorktreeRemovalCandidate(changed, known, open = false).eligibility(true).selectable)
        }
    }

    fun testApplicationRemovalSurvivesPanelDisposalAndCallerCancellation() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val removal = WorktreeRemovalService(applicationScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val panel = WorktreeLifecyclePanel(project)
        try {
            callerScope.launch {
                val job = removal.launch {
                    started.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                }
                job.join()
            }
            PlatformTestUtil.waitWithEventsDispatching("Application operation starts", { started.isCompleted }, 10)
            panel.dispose()
            callerScope.cancel()
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Disposing the initiating panel does not cancel removal",
                { finished.isCompleted }, 10)
        } finally {
            panel.dispose()
            callerScope.cancel()
            applicationScope.cancel()
        }
    }

    fun testApplicationShutdownCancelsPendingRemoval() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            WorktreeRemovalService(applicationScope).launch {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            PlatformTestUtil.waitWithEventsDispatching("Removal starts", { started.isCompleted }, 10)
            applicationScope.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Application cancellation stops pending removal",
                { cancelled.isCompleted }, 10)
        } finally { applicationScope.cancel() }
    }

    fun testCancellingFinalRemovalDoesNotMutateOrRefresh() = runBlocking {
        var mutations = 0
        var refreshes = 0
        val result = removeAfterConfirmation(
            confirm = { false },
            remove = { mutations++; JsonObject() },
            refresh = { refreshes++ },
        )
        assertNull(result)
        assertEquals(0, mutations)
        assertEquals(0, refreshes)
    }

    fun testConfirmedRemovalRefreshesOnceForPartialResultsAndThrownFailures() = runBlocking {
        val partial = JsonParser.parseString("""{"results":[
            {"directory":"/repo/one","status":"removed","localBranchDeleted":true},
            {"directory":"/repo/two","status":"removal_failed","reason":"Branch deletion failed"}
        ]}""").asJsonObject
        var mutations = 0
        var refreshes = 0
        assertSame(partial, removeAfterConfirmation(
            confirm = { true },
            remove = { mutations++; partial },
            refresh = { refreshes++ },
        ))
        assertEquals(1, mutations)
        assertEquals(1, refreshes)
        val failure = IllegalStateException("Deletion interrupted after one directory")
        try {
            removeAfterConfirmation(
                confirm = { true },
                remove = { mutations++; throw failure },
                refresh = { refreshes++ },
            )
            fail("Removal failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(2, mutations)
        assertEquals(2, refreshes)
    }

    fun testRemovalCancellationStillRefreshesAndPreservesFailureWhenRefreshFails() = runBlocking {
        var refreshes = 0
        val deleting = launch {
            removeAfterConfirmation(
                confirm = { true },
                remove = {
                    currentCoroutineContext().cancel()
                    yield()
                    JsonObject()
                },
                refresh = { yield(); refreshes++ },
            )
        }
        deleting.join()
        assertTrue(deleting.isCancelled)
        assertEquals(1, refreshes)
        val removalError = IllegalStateException("Deletion failed")
        val refreshError = IllegalArgumentException("Refresh failed")
        try {
            removeAfterConfirmation(
                confirm = { true },
                remove = { throw removalError },
                refresh = { throw refreshError },
            )
            fail("Removal failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(removalError, error)
            assertTrue(error.suppressed.any { it === refreshError || it.cause === refreshError })
        }
    }

    fun testCompactRemovalReviewRetainsIdentitiesLossEvidenceAndBlockersWithoutCommitIds() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val plan = JsonParser.parseString("""{
            "directory":"/repo/feature<&>","branch":"feature",
            "options":{"deleteLocalBranch":true,"deleteLocalChanges":true},
            "trackedChanges":[" M tracked"],"untrackedFiles":["untracked"],
            "unretainedCommitIds":["$sha"],"blockedReasons":["Locked by another user"]
        }""").asJsonObject
        val item = RemovalReviewItem.from(plan)
        assertEquals("/repo/feature<&>", item.directory)
        assertEquals("feature", item.branch)
        assertTrue(item.deleteBranch)
        assertTrue(item.discardLocalChanges)
        assertEquals(1, item.trackedChanges)
        assertEquals(1, item.untrackedFiles)
        assertEquals(1, item.unretainedCommits)
        assertEquals(listOf("Locked by another user"), item.blockers)
        val panel = removalReviewPanel("/repo", listOf(plan))
        assertTrue(panel.components.all { it is JLabel })
        assertFalse(panel.components.any { it is JTextArea })
        val descriptions = panel.components.map { it.accessibleContext.accessibleName }
        assertTrue(descriptions.contains(item.directory))
        assertTrue(descriptions.any { it.contains(item.branch!!) })
        assertTrue(descriptions.any { it.contains(item.blockers.single()) })
        assertFalse(descriptions.any { it.contains(sha) || it.contains("trackedChanges") })
        plan.getAsJsonObject("options").addProperty("deleteLocalChanges", false)
        assertFalse(RemovalReviewItem.from(plan).discardLocalChanges)
        plan.getAsJsonObject("options").addProperty("deleteLocalBranch", false)
        plan.getAsJsonArray("trackedChanges").remove(0)
        plan.getAsJsonArray("untrackedFiles").remove(0)
        plan.getAsJsonArray("unretainedCommitIds").remove(0)
        val clean = RemovalReviewItem.from(plan)
        assertFalse(clean.deleteBranch)
        assertEquals(0, clean.trackedChanges + clean.untrackedFiles + clean.unretainedCommits)
        assertTrue(removalReviewPanel("/repo", listOf(plan)).componentCount < panel.componentCount)
    }

    fun testRemovalResultsUsePerDirectoryOutcomesWithoutRawPayload() {
        val result = JsonParser.parseString("""{"status":"completed","results":[
            {"directory":"/repo/one","status":"removed","localBranchDeleted":true},
            {"directory":"/repo/two","status":"blocked","blockedReasons":["Project opened"]}
        ]}""").asJsonObject
        val panel = removalResultsPanel(result)
        val descriptions = panel.components.map { it.accessibleContext.accessibleName }
        assertTrue(descriptions.containsAll(listOf("/repo/one", "/repo/two", "removed", "blocked", "Project opened")))
        assertTrue(panel.components.all { it is JLabel })
        assertFalse(descriptions.any { it.contains("localBranchDeleted") || it.contains("results") })
    }

    private fun removalDialog(candidates: List<JsonObject>, cleanup: Boolean = true) =
        WorktreeRemovalDialog(project, candidates.map { plan ->
            candidate(plan["directory"].asString,
                known = plan["selectable"]?.asBoolean == true,
                dirty = plan["safe"]?.asBoolean != true,
                kind = plan["cleanupKind"]?.asString ?: "BLOCKED")
        }, cleanup)

    private fun candidate(
        directory: String, known: Boolean = true, dirty: Boolean = false, kind: String = "MERGED",
        open: Boolean = false, locked: Boolean = false,
    ): WorktreeRemovalCandidate {
        val entry = GitWorktree(directory, "aaa", "branch-not-directory", false, false, if (locked) "Keep" else null, null)
        val status = WorktreeDisplayStatus("Local", "Local status", "Review", "Review evidence",
            localStatusKnown = known, hasLocalChanges = dirty, cleanupKind = kind, identity = WorktreeIdentity(entry))
        return WorktreeRemovalCandidate(entry, status, open)
    }

    private fun preview(
        directory: String, selectable: Boolean = true, safe: Boolean = true, kind: String = "MERGED",
    ): JsonObject = candidate(directory, known = selectable, dirty = !safe, kind = kind).plan(true).toJson()
}
