package local.worktree

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import git4idea.config.GitExecutableManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WorktreeInventoryServiceTest : HeavyPlatformTestCase() {
    private lateinit var temporary: Path
    private lateinit var scope: CoroutineScope

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("worktree-inventory-").toRealPath()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testSharesCanonicalCommonDirectoryAndObservesExternalAddRemoveWithInitiallyMissingMetadata() = runBlocking {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        val common = temporary.resolve("external-metadata")
        git(repository, "init", "--initial-branch=main", "--separate-git-dir=$common")
        git(repository, "-c", "user.name=Inventory Test", "-c", "user.email=inventory@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "initial")
        assertFalse(Files.exists(common.resolve("worktrees")))
        // --separate-git-dir inventories use Git's own primary entry, not an inferred checkout path.
        val baseline = GitWorktrees.discover(repository.toString())
        val service = WorktreeInventoryService(scope)
        val inventory = service.inventory(repository.toString())
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), repository)
        assertSame(inventory, service.inventory(alias.toString()))
        val observed = scope.launch { inventory.snapshots.collect() }
        try {
            // Version 2 includes the post-registration reconciliation, not merely the initial read.
            val initial = withTimeout(10_000) { inventory.snapshots.filterNotNull().first { it.version >= 2 } }
            assertEquals(baseline, initial.entries)
            val linked = temporary.resolve("linked")
            git(repository, "worktree", "add", "-b", "external", linked.toString())
            refreshMetadata(common)
            val added = withTimeout(10_000) {
                inventory.snapshots.filterNotNull().first { snapshot -> snapshot.entries.any { it.directory == linked.toString() } }
            }
            assertNull(added.error)
            assertSame(inventory, service.inventory(linked.toString()))
            assertSame(inventory, service.inventory(common.toString()))
            git(repository, "worktree", "remove", linked.toString())
            refreshMetadata(common)
            val removed = withTimeout(10_000) {
                inventory.snapshots.filterNotNull().first { it.version > added.version && it.entries.none { entry -> entry.directory == linked.toString() } }
            }
            assertNull(removed.error)
            assertEquals(baseline, removed.entries)
        } finally {
            observed.cancelAndJoin()
        }
    }

    fun testInventorySurvivesRemovalOfLinkedCheckoutThatOpenedIt() = runBlocking {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        git(repository, "init", "--initial-branch=main")
        git(repository, "-c", "user.name=Inventory Test", "-c", "user.email=inventory@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "initial")
        val baseline = GitWorktrees.discover(repository.toString())
        val linked = temporary.resolve("linked")
        git(repository, "worktree", "add", "-b", "first", linked.toString())
        val service = WorktreeInventoryService(scope)
        val inventory = service.inventory(linked.toString())
        val initial = withTimeout(10_000) { inventory.refresh() }
        assertNull(initial.error)
        assertEquals(GitWorktrees.discover(repository.toString()), initial.entries)

        git(repository, "worktree", "remove", linked.toString())
        assertFalse(Files.exists(linked))
        val removed = withTimeout(10_000) { inventory.refresh() }
        assertNull(removed.error)
        assertEquals(baseline, removed.entries)
        assertSame(inventory, service.inventory(repository.toString()))

        val next = temporary.resolve("next")
        git(repository, "worktree", "add", "-b", "next", next.toString())
        val added = withTimeout(10_000) { inventory.refresh() }
        assertNull(added.error)
        assertEquals(GitWorktrees.discover(repository.toString()), added.entries)
        assertTrue(added.entries.any { it.directory == next.toString() })
    }

    fun testCoalescesRefreshesDuringReadAndFansOutOneSnapshotWithoutLosingInvalidation() = runBlocking {
        val reads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val watching = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val entry = GitWorktree("/shared", "head", "main", true, false, null, null)
        val inventory = WorktreeInventory(scope, {
            if (reads.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
                emptyList()
            } else listOf(entry)
        }) {
            watching.complete(Unit)
            AutoCloseable { closed.complete(Unit) }
        }
        val firstPanel = scope.launch { inventory.snapshots.collect() }
        val secondPanel = scope.launch { inventory.snapshots.collect() }
        try {
            withTimeout(10_000) { watching.await(); started.await() }
            val refreshes = List(30) { async(start = CoroutineStart.UNDISPATCHED) { inventory.refresh() } }
            release.complete(Unit)
            val results = withTimeout(10_000) { refreshes.awaitAll() }
            assertEquals(2, reads.get())
            assertEquals(listOf(entry), results.first().entries)
            results.forEach { assertSame(results.first(), it) }
            assertSame(results.first(), inventory.snapshots.value)
            firstPanel.cancelAndJoin()
            assertFalse(closed.isCompleted)
            secondPanel.cancelAndJoin()
            withTimeout(10_000) { closed.await() }
        } finally {
            release.complete(Unit)
            firstPanel.cancelAndJoin()
            secondPanel.cancelAndJoin()
        }
    }

    fun testRetainsLastGoodInventoryOnFailureAndPublishesEqualRecovery() = runBlocking {
        val failure = AtomicReference<IOException?>()
        val entry = GitWorktree("/shared", "head", "main", true, false, null, null)
        val inventory = WorktreeInventory(scope, {
            failure.get()?.let { throw it }
            listOf(entry)
        }) { AutoCloseable {} }
        val initial = withTimeout(10_000) { inventory.refresh() }
        failure.set(IOException("Git unavailable"))
        val failed = withTimeout(10_000) { inventory.refresh() }
        assertEquals(initial.entries, failed.entries)
        assertEquals("Git unavailable", failed.error)
        assertTrue(failed.version > initial.version)
        failure.set(null)
        val recovered = withTimeout(10_000) { inventory.refresh() }
        assertEquals(initial.entries, recovered.entries)
        assertNull(recovered.error)
        assertTrue(recovered.version > failed.version)
        val unchanged = withTimeout(10_000) { inventory.refresh() }
        assertTrue(unchanged.version > recovered.version)
    }

    fun testSharedProgressiveStatusesReuseEvidenceOnlyForExactCheckoutIdentity() = runBlocking {
        val repository = Files.createDirectory(temporary.resolve("repository"))
        git(repository, "init", "--initial-branch=main")
        git(repository, "-c", "user.name=Inventory Test", "-c", "user.email=inventory@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "initial")
        val head = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "remote", "add", "origin", "git@github.com:owner/repo.git")
        git(repository, "update-ref", "refs/remotes/origin/main", head)
        git(repository, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        val linked = temporary.resolve("linked")
        git(repository, "worktree", "add", "-b", "feature", linked.toString())
        val reads = AtomicInteger()
        val inventory = WorktreeInventory(scope, {
            reads.incrementAndGet()
            GitWorktrees.discover(repository.toString())
        }) { AutoCloseable {} }
        val initial = withTimeout(10_000) { inventory.refreshStatus(project, repository.toString()) }
        val row = initial.rows.single { it.entry.directory == linked.toString() }
        assertEquals("MERGED", row.status!!.cleanupKind)
        assertEquals(WorktreeIdentity(row.entry), row.status.identity)
        val baselineReads = reads.get()
        assertSame(initial, inventory.current(project, repository.toString()))
        assertSame(initial, inventory.current())
        assertEquals("A second consumer reuses discovery and status", baselineReads, reads.get())

        val refresh = async(start = CoroutineStart.UNDISPATCHED) { inventory.refreshStatus(project, repository.toString()) }
        val pending = withTimeout(10_000) {
            inventory.snapshots.filterNotNull().first { it.version > initial.version && it.refreshing }
        }
        val retained = pending.rows.single { it.entry.directory == linked.toString() }
        assertEquals("MERGED", retained.status!!.cleanupKind)
        assertTrue(pending.removalCandidates(emptyList()).single { it.entry.directory == linked.toString() }.eligibility(true).safe)
        val complete = withTimeout(10_000) { refresh.await() }
        assertFalse(complete.refreshing)
        assertSame(complete, inventory.current())

        git(linked, "-c", "user.name=Inventory Test", "-c", "user.email=inventory@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "new head")
        val changed = withTimeout(10_000) { inventory.refreshStatus(project, repository.toString()) }
        val changedRow = changed.rows.single { it.entry.directory == linked.toString() }
        assertFalse(row.entry.head == changedRow.entry.head)
        assertEquals(WorktreeIdentity(changedRow.entry), changedRow.status!!.identity)
        assertEquals("BLOCKED", changedRow.status.cleanupKind)
        assertFalse(changed.removalCandidates(emptyList()).single { it.entry.directory == linked.toString() }.eligibility(true).selectable)
    }

    private suspend fun refreshMetadata(common: Path) = withContext(Dispatchers.IO) {
        // External Git writes the disk; let the real VFS publish events deterministically, without
        // depending on fsnotifier/focus-refresh scheduling in a headless platform test.
        val fileSystem = LocalFileSystem.getInstance()
        val root = requireNotNull(fileSystem.refreshAndFindFileByNioFile(common))
        VfsUtil.markDirtyAndRefresh(false, false, true, root)
        for (directory in listOf("worktrees", "refs")) {
            fileSystem.refreshAndFindFileByNioFile(common.resolve(directory))?.let {
                VfsUtil.markDirtyAndRefresh(false, true, true, it)
            }
        }
    }

    private suspend fun git(repository: Path, vararg arguments: String): String = withContext(Dispatchers.IO) {
        val command = GeneralCommandLine(GitExecutableManager.getInstance().pathToGit)
            .withWorkingDirectory(repository)
            .withCharset(Charsets.UTF_8)
            .withParameters("-c", "core.hooksPath=${temporary.resolve("no-hooks")}")
            .withParameters(*arguments)
        val environment = command.effectiveEnvironment.filterKeys { !it.startsWith("GIT_") }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(environment)
        val result = CapturingProcessHandler(command).runProcess(30_000)
        assertFalse("Git fixture timed out: ${arguments.toList()}", result.isTimeout)
        assertFalse("Git fixture was cancelled: ${arguments.toList()}", result.isCancelled)
        assertEquals("Git fixture failed: ${result.stderr}", 0, result.exitCode)
        result.stdout
    }
}
