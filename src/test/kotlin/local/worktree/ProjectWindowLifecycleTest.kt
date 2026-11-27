package local.worktree

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectWindowLifecycleTest {
    private class Window(val directory: String) {
        var open = true
    }

    private class FakeIde : ProjectWindowAdapter<Window> {
        override var supportsMerge = true
        val projects = mutableListOf<Window>()
        var last: Window? = null
        var focused: Window? = null
        var modal = false
        var opens = 0
        var dispatches = 0
        var observations = 0
        var mergeAfter = 1
        var merged = false
        var dispatchResult = LifecycleOutcome("dispatched", "/target")
        var dispatchedTarget: Window? = null
        var disappear = false
        var loseOtherWindow = false
        var cancelOpen = false
        val enteredOpen = CompletableDeferred<Unit>()
        val enteredMerge = CompletableDeferred<Unit>()
        var openingGate: CompletableDeferred<Unit>? = null

        fun project(directory: String) = Window(directory).also { projects.add(it) }
        override suspend fun canonical(directory: String): String? = when (directory) {
            "/alias" -> "/worktree"
            "/invalid" -> null
            else -> directory.takeIf { it.startsWith("/") }
        }
        override suspend fun lastTarget() = last
        override suspend fun findProject(directory: String) = projects.singleOrNull { it.open && it.directory == directory }
        override suspend fun available(project: Window) = project.open
        override suspend fun targetAvailable(project: Window) = project.open
        override suspend fun modal() = modal
        override suspend fun open(directory: String): Window? {
            opens++
            enteredOpen.complete(Unit)
            openingGate?.await()
            if (cancelOpen) return null
            return project(directory).also { last = it }
        }
        override suspend fun focus(project: Window): Boolean {
            if (!project.open) return false
            focused = project
            return true
        }
        override suspend fun windows() = projects.filter { it.open }
        override suspend fun mergeState(target: Window, windows: List<Window>, select: Boolean): MergeState {
            if (!target.open) return MergeState.TARGET_UNAVAILABLE
            if (windows.any { !it.open }) return MergeState.WINDOW_UNAVAILABLE
            if (select) {
                observations++
                if (observations >= mergeAfter) merged = true
            }
            if (select && merged) focused = target
            return if (merged) MergeState.MERGED else MergeState.PENDING
        }
        override suspend fun dispatchMerge(target: Window): LifecycleOutcome {
            dispatches++
            dispatchedTarget = target
            if (disappear) target.open = false
            if (loseOtherWindow) projects.first { it !== target }.open = false
            enteredMerge.complete(Unit)
            return dispatchResult
        }
    }

    private fun lifecycle(ide: FakeIde, timeout: Long = 500) = ProjectWindowLifecycle(ide, timeout, 1)

    @Test fun `canonical reuse focuses exact project without opening or merging`() = runBlocking {
        val ide = FakeIde()
        val existing = ide.project("/worktree")
        ide.last = ide.project("/target")
        val result = lifecycle(ide).open("/alias", targetDirectory = "/invalid")
        assertEquals("reused", result.status)
        assertEquals("/worktree", result.directory)
        assertNull(result.merge)
        assertSame(existing, ide.focused)
        assertEquals(0, ide.opens)
        assertEquals(0, ide.dispatches)
    }

    @Test fun `opening captures target before SDK changes last active project`() = runBlocking {
        val ide = FakeIde()
        val target = ide.project("/target")
        ide.last = target
        val result = lifecycle(ide).open("/worktree", merge = true)
        assertEquals("opened", result.status)
        assertEquals("merged", result.merge?.status)
        assertSame(target, ide.dispatchedTarget)
        assertEquals("/worktree", ide.focused?.directory)
    }

    @Test fun `explicit merge on reuse waits through delayed native endstate`() = runBlocking {
        val ide = FakeIde()
        val existing = ide.project("/worktree")
        val target = ide.project("/target")
        ide.last = existing
        ide.mergeAfter = 4
        val result = lifecycle(ide).open("/alias", merge = true, targetDirectory = "/target")
        assertEquals("reused", result.status)
        assertEquals("merged", result.merge?.status)
        assertEquals(4, ide.observations)
        assertEquals(1, ide.dispatches)
        assertEquals(0, ide.opens)
        assertSame(target, ide.dispatchedTarget)
        assertSame(existing, ide.focused)
    }

    @Test fun `standalone merge selects captured target and never redispatches completed merge`() = runBlocking {
        val ide = FakeIde()
        val target = ide.project("/target")
        ide.last = target
        ide.project("/worktree")
        val core = lifecycle(ide)
        assertEquals("merged", core.merge().status)
        assertSame(target, ide.focused)
        assertEquals("not_needed", core.merge().status)
        assertEquals(1, ide.dispatches)
    }

    @Test fun `refusal is returned without claiming merge or retrying dispatch`() = runBlocking {
        val ide = FakeIde()
        ide.last = ide.project("/target")
        ide.project("/worktree")
        ide.dispatchResult = LifecycleOutcome("unavailable", actionId = "MergeAllWindowsAction", reason = "disabled")
        val result = lifecycle(ide).merge()
        assertEquals("unavailable", result.status)
        assertEquals("disabled", result.reason)
        assertEquals(1, ide.dispatches)
        assertEquals(0, ide.observations)
    }

    @Test fun `disappearing target never chooses a replacement or closes opened project`() = runBlocking {
        val ide = FakeIde()
        ide.last = ide.project("/target")
        ide.disappear = true
        val result = lifecycle(ide).open("/worktree", merge = true)
        assertEquals("opened", result.status)
        assertEquals("target_unavailable", result.merge?.status)
        assertTrue(ide.projects.single { it.directory == "/worktree" }.open)
        assertEquals(1, ide.dispatches)
    }

    @Test fun `window disappearance and timeout report incomplete merge honestly`() = runBlocking {
        val ide = FakeIde()
        ide.last = ide.project("/target")
        ide.project("/worktree")
        ide.loseOtherWindow = true
        val disappeared = lifecycle(ide).merge()
        assertEquals("merge_pending", disappeared.status)
        assertEquals("window_unavailable", disappeared.reason)
        ide.projects.forEach { it.open = true }
        ide.loseOtherWindow = false
        ide.mergeAfter = Int.MAX_VALUE
        val timedOut = lifecycle(ide, timeout = 25).merge()
        assertEquals("merge_pending", timedOut.status)
        assertEquals("merge_timeout", timedOut.reason)
        assertEquals(2, ide.dispatches)
        assertFalse(ProjectWindowLifecycle.busy)
    }

    @Test fun `different instances reject repeated opens and merges instead of queueing`() = runBlocking {
        val ide = FakeIde()
        ide.last = ide.project("/target")
        ide.openingGate = CompletableDeferred()
        val first = async { lifecycle(ide).open("/worktree") }
        ide.enteredOpen.await()
        assertTrue(ProjectWindowLifecycle.busy)
        val repeated = lifecycle(ide).open("/alias")
        assertEquals("operation_in_progress", repeated.status)
        assertEquals("/worktree", repeated.directory)
        assertEquals("operation_in_progress", lifecycle(ide).merge("/target").status)
        ide.openingGate!!.complete(Unit)
        assertEquals("opened", first.await().status)
        assertEquals(1, ide.opens)
        assertEquals(0, ide.dispatches)
        assertFalse(ProjectWindowLifecycle.busy)
    }

    @Test fun `cancelling open and merge always releases shared guard`() = runBlocking {
        val ide = FakeIde()
        ide.last = ide.project("/target")
        ide.openingGate = CompletableDeferred()
        val opening = launch { lifecycle(ide).open("/worktree") }
        ide.enteredOpen.await()
        opening.cancelAndJoin()
        assertFalse(ProjectWindowLifecycle.busy)
        ide.openingGate = null
        ide.project("/worktree")
        ide.mergeAfter = Int.MAX_VALUE
        val merging = launch { lifecycle(ide).merge() }
        ide.enteredMerge.await()
        merging.cancelAndJoin()
        assertFalse(ProjectWindowLifecycle.busy)
        assertEquals("reused", lifecycle(ide).open("/alias").status)
    }

    @Test fun `modal trust cancellation invalid target and no target have no extra effects`() = runBlocking {
        val ide = FakeIde()
        ide.modal = true
        assertEquals("unavailable_modal", lifecycle(ide).open("/worktree").status)
        assertEquals(0, ide.opens)
        ide.modal = false
        assertEquals("target_not_open", lifecycle(ide).open("/worktree", true, "/target").status)
        assertEquals("invalid_target_directory", lifecycle(ide).merge("/invalid").status)
        ide.cancelOpen = true
        assertEquals("open_cancelled", lifecycle(ide).open("/worktree").status)
        ide.cancelOpen = false
        val noTarget = lifecycle(ide).open("/worktree", merge = true)
        assertEquals("not_needed", noTarget.merge?.status)
        assertEquals(0, ide.dispatches)
        ide.merged = true
        ide.project("/target")
        assertEquals("not_needed", lifecycle(ide).merge().status)
        assertEquals(0, ide.dispatches)
    }
}
