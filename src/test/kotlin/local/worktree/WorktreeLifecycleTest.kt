package local.worktree

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.ui.content.ContentFactory
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
        } finally { Disposer.dispose(content) }
    }

    fun testCleanupUsesAdapterSelectionAndSafetyWithoutReconstructingPolicy() {
        val safe = preview("/worktrees/safe")
        val dirty = preview("/worktrees/dirty", safe = false)
        val closed = preview("/worktrees/closed", safe = false, kind = "CLOSED_UNMERGED")
        val blocked = preview("/worktrees/blocked", selectable = false, safe = false, kind = "BLOCKED")
        val dialog = WorktreeLifecycleCleanupDialog(project, "Complete tool preview", listOf(safe, dirty, closed, blocked))
        try {
            assertEquals("Cleanup Worktrees", dialog.title)
            assertEquals(listOf(safe), dialog.selectedPlans)
            assertEquals("Delete selected worktrees (1)…", dialog.deleteSelectedAction.getValue(javax.swing.Action.NAME))
            assertTrue(dialog.isOKActionEnabled)
            assertFalse(dialog.choices[3].selected.isEnabled)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertTrue(dialog.selection.includeClosedUnmerged.isEmpty())
            assertTrue(dialog.choices.none { it.deleteLocalBranch.isSelected || it.deleteLocalChanges.isSelected })
            assertSame(dialog.selectSafe, dialog.getPreferredFocusedComponent())
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
        val dialog = WorktreeLifecycleCleanupDialog(project, "Complete tool preview", candidates)
        try {
            dialog.selectAll.doClick()
            assertEquals(candidates.take(3), dialog.selectedPlans)
            assertEquals(listOf("/worktrees/closed"), dialog.selection.includeClosedUnmerged)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertTrue(dialog.choices.none { it.deleteLocalBranch.isSelected || it.deleteLocalChanges.isSelected })
            assertEquals("Delete selected worktrees (3)…", dialog.deleteSelectedAction.getValue(javax.swing.Action.NAME))
            dialog.selectSafe.doClick()
            assertEquals(listOf(candidates.first()), dialog.selectedPlans)
            assertTrue(dialog.selection.includeClosedUnmerged.isEmpty())
        } finally { dialog.disposeIfNeeded() }
    }

    fun testDestructiveOptionsRequireSeparateChecksAndUseSelectedAbsoluteDirectories() {
        val safe = preview("/worktrees/safe")
        val dirty = preview("/worktrees/dirty", safe = false)
        val dialog = WorktreeLifecycleCleanupDialog(project, "Complete tool preview", listOf(safe, dirty))
        try {
            dialog.choices[0].deleteLocalBranch.doClick()
            dialog.choices[1].selected.doClick()
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            dialog.choices[1].deleteLocalChanges.doClick()
            assertEquals(listOf("/worktrees/safe"), dialog.selection.deleteLocalBranches)
            assertEquals(listOf("/worktrees/dirty"), dialog.selection.deleteLocalChanges)
            dialog.clearSelection.doClick()
            assertTrue(dialog.selectedPlans.isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            assertEquals("Delete selected worktrees (0)…", dialog.deleteSelectedAction.getValue(javax.swing.Action.NAME))
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            assertTrue(dialog.choices[0].deleteLocalBranch.isSelected)
            assertTrue(dialog.choices[1].deleteLocalChanges.isSelected)
            dialog.selectSafe.doClick()
            assertEquals(listOf("/worktrees/safe"), dialog.selection.deleteLocalBranches)
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
            dialog.selectAll.doClick()
            assertEquals(listOf("/worktrees/dirty"), dialog.selection.deleteLocalChanges)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testUnknownSelectionFlagsDoNotAuthorizeOrAutomaticallySelectRows() {
        val unknownSelection = preview("/worktrees/unknown").apply { remove("selectable") }
        val unknownSafety = preview("/worktrees/manual").apply { remove("safe") }
        val dialog = WorktreeLifecycleCleanupDialog(project, "Unknown flags", listOf(unknownSelection, unknownSafety), cleanup = false)
        try {
            assertEquals("Remove Worktrees", dialog.title)
            assertTrue(dialog.selectedPlans.isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            dialog.selectAll.doClick()
            assertEquals(listOf(unknownSafety), dialog.selectedPlans)
            assertTrue(dialog.selection.deleteLocalBranches.isEmpty())
            assertTrue(dialog.selection.deleteLocalChanges.isEmpty())
        } finally { dialog.disposeIfNeeded() }
    }

    fun testBranchDeletionRequiresVerifiedRemoteAbsence() {
        val present = preview("/worktrees/published").apply { addProperty("remoteBranchAbsent", false) }
        val unknown = preview("/worktrees/unknown-remote").apply { remove("remoteBranchAbsent") }
        val absent = preview("/worktrees/deleted-remote")
        val dialog = WorktreeLifecycleCleanupDialog(project, "Remote evidence", listOf(present, unknown, absent))
        try {
            assertFalse(dialog.choices[0].deleteLocalBranch.isEnabled)
            assertFalse(dialog.choices[1].deleteLocalBranch.isEnabled)
            assertTrue(dialog.choices[2].deleteLocalBranch.isEnabled)
            dialog.choices[2].deleteLocalBranch.doClick()
            assertEquals(listOf("/worktrees/deleted-remote"), dialog.selection.deleteLocalBranches)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testRemovalDetailsDiscloseExactFilesCommitIdsAndToolEvidence() {
        val plan = preview("/worktrees/dirty", safe = false).apply {
            add("trackedChanges", JsonParser.parseString("""[" M tracked.txt"]"""))
            add("untrackedFiles", JsonParser.parseString("""["new.txt"]"""))
            add("ignoredFiles", JsonParser.parseString("""["build/cache.bin"]"""))
            add("unretainedCommitIds", JsonParser.parseString("""["0123456789abcdef"]"""))
            addProperty("remoteBranchAbsent", true)
            addProperty("remoteBranchDetails", "Remote branch checked after fetch")
            addProperty("cleanupDetails", "Exact HEAD proof")
            add("options", JsonParser.parseString("""{"deleteLocalBranch":true,"deleteLocalChanges":true}"""))
        }
        val text = lifecycleRemovalText(plan)
        for (detail in listOf(
            "tracked.txt", "new.txt", "build/cache.bin", "0123456789abcdef",
            "Remote branch checked after fetch", "Exact HEAD proof", "\"deleteLocalBranch\":true",
            "\"deleteLocalChanges\":true", "Open: false", "Blockers:",
        )) assertTrue("Missing disclosure: $detail", text.contains(detail))
    }

    private fun preview(
        directory: String, selectable: Boolean = true, safe: Boolean = true, kind: String = "MERGED",
    ): JsonObject = JsonParser.parseString("""
        {"directory":"$directory","branch":"branch-not-directory","head":"aaa",
         "status":"preview","open":false,"historyKnown":true,"unsafeCommits":0,"unpushedCommits":0,
         "trackedChanges":[],"untrackedFiles":[],"ignoredFiles":[],"blockedReasons":[],"blockedCodes":[],
         "selectable":$selectable,"safe":$safe,"cleanupKind":"$kind","remoteBranchAbsent":true,
         "options":{"deleteLocalBranch":false,"deleteLocalChanges":false}}
    """).asJsonObject
}
