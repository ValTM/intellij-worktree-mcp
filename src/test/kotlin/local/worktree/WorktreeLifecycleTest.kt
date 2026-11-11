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

    fun testCleanupRequiresExactMergedHeadProofAndEverySafetyObservation() {
        val proven = WorktreeDisplayStatus("Synced", "Local refs", "Merged", "Exact current HEAD is covered", mergedHeadCovered = true)
        val safe = preview()
        assertTrue(lifecycleCleanupEligible(proven, safe))
        assertFalse(lifecycleCleanupEligible(proven.copy(mergedHeadCovered = false), safe))
        assertFalse(lifecycleCleanupEligible(proven.copy(error = "Provider unavailable"), safe))
        for ((property, replacement) in listOf(
            "status" to "\"blocked\"",
            "open" to "true",
            "historyKnown" to "false",
            "unsafeCommits" to "null",
            "unsafeCommits" to "1",
            "unpushedCommits" to "null",
            "unpushedCommits" to "1",
            "trackedChanges" to "[\" M file\"]",
            "untrackedFiles" to "[\"ignored-build-output\"]",
            "blockedReasons" to "[\"Locked\"]",
            "blockedCodes" to "[\"LOCKED\"]",
        )) {
            val changed = preview().apply { add(property, JsonParser.parseString(replacement)) }
            assertFalse("Must retain when $property=$replacement", lifecycleCleanupEligible(proven, changed))
        }
        for (property in listOf("status", "open", "historyKnown", "unsafeCommits", "unpushedCommits",
            "trackedChanges", "untrackedFiles", "blockedReasons", "blockedCodes")) {
            val unknown = preview().apply { remove(property) }
            assertFalse("Missing $property must not authorize cleanup", lifecycleCleanupEligible(proven, unknown))
        }
    }

    fun testCleanupOnlyApprovesCheckedCandidatesAndRequiresOneSelection() {
        val first = preview().apply { addProperty("directory", "/worktrees/one"); addProperty("branch", "one"); addProperty("head", "aaa") }
        val second = preview().apply { addProperty("directory", "/worktrees/two"); addProperty("branch", "two"); addProperty("head", "bbb") }
        val dialog = WorktreeLifecycleCleanupDialog(project, "Blocked worktrees remain explained in this preview.", listOf(first, second))
        try {
            assertEquals(listOf(first, second), dialog.selectedPlans)
            assertTrue(dialog.isOKActionEnabled)
            dialog.choices[0].second.doClick()
            assertEquals(listOf(second), dialog.selectedPlans)
            dialog.choices[1].second.doClick()
            assertTrue(dialog.selectedPlans.isEmpty())
            assertFalse(dialog.isOKActionEnabled)
            dialog.choices[0].second.doClick()
            assertEquals(listOf(first), dialog.selectedPlans)
            assertTrue(dialog.isOKActionEnabled)
        } finally { dialog.disposeIfNeeded() }
    }

    private fun preview(): JsonObject = JsonParser.parseString("""
        {"status":"preview","open":false,"historyKnown":true,"unsafeCommits":0,"unpushedCommits":0,
         "trackedChanges":[],"untrackedFiles":[],"blockedReasons":[],"blockedCodes":[]}
    """).asJsonObject
}
