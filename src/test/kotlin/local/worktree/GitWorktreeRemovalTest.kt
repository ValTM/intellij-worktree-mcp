package local.worktree

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import git4idea.config.GitExecutableManager
import git4idea.config.GitVcsApplicationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.worktree.GitWorktrees.RemovalBlockReason
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class GitWorktreeRemovalTest : HeavyPlatformTestCase() {
    private lateinit var temporary: Path

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("git-worktree-removal-").toRealPath()
    }

    override fun tearDown() {
        try {
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testCleanLinkedRemovalPreservesBranchesAndUnrelatedWorktreesWithoutUpstream() = runBlocking {
        val repository = initializeRepository("repository with spaces\nand newline")
        val linked = addWorktree(repository, "feature", "linked with spaces\nand newline")
        val unrelated = addWorktree(repository, "unrelated", "unrelated")
        commit(linked, "local commit that has never been pushed")
        val head = git(linked, "rev-parse", "HEAD").trim()
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), linked)
        val before = GitWorktrees.discover(repository.toString())
        val plan = GitWorktrees.previewRemove(repository.toString(), alias.toString())
        assertEquals(linked.toString(), plan.directory)
        assertEquals(head, plan.head)
        assertEquals("feature", plan.branch)
        assertTrue(plan.trackedChanges.isEmpty())
        assertTrue(plan.untrackedFiles.isEmpty())
        assertNull(plan.unpushedCommits)
        assertTrue(plan.historyKnown)
        assertEquals(0, plan.unsafeCommits)
        assertTrue(plan.blockedCodes.isEmpty())
        assertEquals(before, GitWorktrees.discover(repository.toString()))
        assertTrue(Files.isDirectory(linked))

        assertEquals(linked.toString(), GitWorktrees.remove(plan, plan.confirmation))
        assertFalse(Files.exists(linked))
        assertTrue(Files.isDirectory(repository))
        assertTrue(Files.isDirectory(unrelated))
        assertEquals(head, git(repository, "rev-parse", "refs/heads/feature").trim())
        assertEquals(setOf(repository.toString(), unrelated.toString()),
            GitWorktrees.discover(repository.toString()).map { it.directory }.toSet())
    }

    fun testBatchRemovalKeepsOtherTargetsOriginalConfirmationsValid() = runBlocking {
        val repository = initializeRepository("repository")
        val first = addWorktree(repository, "first", "first")
        val second = addWorktree(repository, "second", "second")
        val firstPlan = GitWorktrees.previewRemove(repository.toString(), first.toString())
        val secondPlan = GitWorktrees.previewRemove(repository.toString(), second.toString())
        assertTrue(firstPlan.blockedCodes.isEmpty())
        assertTrue(secondPlan.blockedCodes.isEmpty())
        assertEquals(first.toString(), GitWorktrees.remove(firstPlan, firstPlan.confirmation))
        assertEquals(second.toString(), GitWorktrees.remove(secondPlan, secondPlan.confirmation))
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(second))
        assertEquals(firstPlan.head, git(repository, "rev-parse", "refs/heads/first").trim())
        assertEquals(secondPlan.head, git(repository, "rev-parse", "refs/heads/second").trim())
        assertEquals(listOf(repository.toString()), GitWorktrees.discover(repository.toString()).map { it.directory })
    }

    fun testAheadOfUpstreamIsReportedButRetainedLocalBranchCanBeRemoved() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        git(repository, "remote", "add", "origin", temporary.resolve("offline-remote").toString())
        git(repository, "update-ref", "refs/remotes/origin/feature", "HEAD")
        git(repository, "config", "branch.feature.remote", "origin")
        git(repository, "config", "branch.feature.merge", "refs/heads/feature")
        val synchronized = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertEquals(0, synchronized.unpushedCommits)
        commit(linked, "unpushed commit")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertEquals(1, plan.unpushedCommits)
        assertEquals(0, plan.unsafeCommits)
        assertTrue(plan.historyKnown)
        assertTrue(plan.blockedCodes.isEmpty())
        GitWorktrees.remove(plan, plan.confirmation)
        assertEquals(plan.head, git(repository, "rev-parse", "refs/heads/feature").trim())
    }

    fun testMainBareLockedPrunableAndForeignTargetsAreBlocked() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "locked", "locked")
        git(repository, "worktree", "lock", linked.toString())
        assertBlocked(repository, repository, RemovalBlockReason.MAIN_WORKTREE)
        assertBlocked(repository, linked, RemovalBlockReason.LOCKED)
        val foreign = initializeRepository("foreign")
        assertBlocked(repository, foreign, RemovalBlockReason.FOREIGN_WORKTREE)
        val ordinary = Files.createDirectory(temporary.resolve("ordinary"))
        assertBlocked(repository, ordinary, RemovalBlockReason.FOREIGN_WORKTREE)
        assertTrue(Files.isDirectory(foreign))
        assertTrue(Files.isDirectory(ordinary))
        val prunable = addWorktree(repository, "missing", "missing")
        assertTrue(FileUtil.delete(prunable.toFile()))
        assertBlocked(repository, prunable, RemovalBlockReason.PRUNABLE)
        assertTrue(GitWorktrees.discover(repository.toString()).any { it.directory == prunable.toString() })
        val bare = Files.createDirectory(temporary.resolve("bare.git"))
        git(bare, "init", "--bare")
        assertBlocked(bare, bare, RemovalBlockReason.BARE_REPOSITORY)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.previewRemove(repository.toString(), "relative") }
    }

    fun testTrackedUntrackedAndIgnoredFilesAreReportedAndNeverRemoved() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        Files.writeString(linked.resolve("tracked.txt"), "changed\n")
        Files.writeString(linked.resolve("untracked\nfile"), "untracked\n")
        Files.createDirectories(linked.resolve("ignored"))
        Files.writeString(linked.resolve("ignored/valuable.txt"), "ignored but valuable\n")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(plan.trackedChanges.any { "tracked.txt" in it })
        assertTrue("untracked\nfile" in plan.untrackedFiles)
        assertTrue(plan.untrackedFiles.any { it.startsWith("ignored/") })
        assertTrue(RemovalBlockReason.TRACKED_CHANGES in plan.blockedCodes)
        assertTrue(RemovalBlockReason.UNTRACKED_FILES in plan.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        assertEquals("ignored but valuable\n", Files.readString(linked.resolve("ignored/valuable.txt")))
        // Even a caller-modified preview cannot bypass the freshly recomputed safety decision.
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.remove(plan.copy(blockedReasons = emptyList(), blockedCodes = emptyList()), plan.confirmation)
        }
    }

    fun testDetachedCommitsRequirePersistentRefNotAnotherWorktreeOrReflog() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = temporary.resolve("detached")
        git(repository, "worktree", "add", "--detach", linked.toString())
        commit(linked, "detached only")
        val head = git(linked, "rev-parse", "HEAD").trim()
        val other = temporary.resolve("other detached")
        git(repository, "worktree", "add", "--detach", other.toString(), head)
        git(linked, "update-ref", "refs/worktree/temporary-retention", head)
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertNull(plan.branch)
        assertNull(plan.unpushedCommits)
        assertTrue(plan.historyKnown)
        assertEquals(1, plan.unsafeCommits)
        assertTrue(RemovalBlockReason.UNRETAINED_COMMITS in plan.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        git(repository, "tag", "retained", head)
        val retained = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertEquals(0, retained.unsafeCommits)
        assertTrue(retained.blockedCodes.isEmpty())
        git(repository, "update-ref", "refs/archive/retained", head)
        git(repository, "tag", "-d", "retained")
        val customRef = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertEquals(0, customRef.unsafeCommits)
        assertTrue(customRef.blockedCodes.isEmpty())
        GitWorktrees.remove(customRef, customRef.confirmation)
        assertTrue(Files.isDirectory(other))
        assertEquals(head, git(repository, "rev-parse", "refs/archive/retained").trim())
    }

    fun testRegisteredTargetRepointedToForeignRepositoryIsBlocked() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        val foreign = initializeRepository("foreign")
        Files.delete(linked.resolve(".git"))
        Files.createSymbolicLink(linked.resolve(".git"), foreign.resolve(".git"))
        val changed = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(RemovalBlockReason.REPOSITORY_IDENTITY_MISMATCH in changed.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(changed, changed.confirmation) }
        assertTrue(Files.isDirectory(linked))
        assertTrue(Files.isDirectory(foreign))
    }

    fun testMissingConfiguredUpstreamIsUnknownHistoryNotZeroUnpushed() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        git(repository, "remote", "add", "origin", temporary.resolve("offline-remote").toString())
        git(repository, "config", "branch.feature.remote", "origin")
        git(repository, "config", "branch.feature.merge", "refs/heads/not-fetched")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertNull(plan.unpushedCommits)
        assertNull(plan.unsafeCommits)
        assertFalse(plan.historyKnown)
        assertTrue(RemovalBlockReason.HISTORY_UNKNOWN in plan.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        assertTrue(Files.isDirectory(linked))
    }

    fun testUnreadableStatusAndUnavailableInventoryFailClosed() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val index = Path.of(git(linked, "rev-parse", "--path-format=absolute", "--git-path", "index").trim())
        Files.writeString(index, "not a Git index")
        val corrupt = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(RemovalBlockReason.STATUS_UNKNOWN in corrupt.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(corrupt, corrupt.confirmation) }
        val settings = GitVcsApplicationSettings.getInstance()
        val previous = settings.savedPathToGit
        val manager = GitExecutableManager.getInstance()
        try {
            settings.setPathToGit(temporary.resolve("missing-git").toString())
            manager.dropExecutableCache()
            val unavailable = GitWorktrees.previewRemove(repository.toString(), linked.toString())
            assertFalse(unavailable.historyKnown)
            assertNull(unavailable.unpushedCommits)
            assertTrue(RemovalBlockReason.REPOSITORY_IDENTITY_UNKNOWN in unavailable.blockedCodes)
            assertTrue(RemovalBlockReason.INVENTORY_UNKNOWN in unavailable.blockedCodes)
            assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(unavailable, unavailable.confirmation) }
        } finally {
            settings.setPathToGit(previous)
            manager.dropExecutableCache()
        }
        assertTrue(Files.isDirectory(linked))
    }

    fun testRemovalRevalidatesConfirmationHeadStatusRefsAndInventory() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val unrelated = addWorktree(repository, "unrelated", "unrelated")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, "wrong") }
        val other = GitWorktrees.previewRemove(repository.toString(), unrelated.toString())
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(other, plan.confirmation) }
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.remove(plan.copy(directory = unrelated.toString()), plan.confirmation)
        }
        val file = Files.writeString(linked.resolve("new-file"), "new content")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        Files.delete(file)
        commit(linked, "HEAD changed")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        val advanced = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        git(repository, "tag", "new-persistent-ref", "HEAD")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(advanced, advanced.confirmation) }
        val tagged = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        git(repository, "worktree", "lock", linked.toString())
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(tagged, tagged.confirmation) }
        assertTrue(Files.isDirectory(linked))
        assertTrue(Files.isDirectory(unrelated))
    }

    fun testRemovalRevalidatesCommonRepositoryIdentity() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        val originalGit = repository.resolve("original.git")
        Files.move(repository.resolve(".git"), originalGit)
        git(repository, "init", "--initial-branch=main")
        commit(repository, "different repository")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        assertTrue(Files.isDirectory(linked))
        assertTrue(Files.isDirectory(originalGit))
    }

    private suspend fun assertBlocked(repository: Path, directory: Path, reason: RemovalBlockReason) {
        val plan = GitWorktrees.previewRemove(repository.toString(), directory.toString())
        assertTrue(plan.blockedCodes.toString(), reason in plan.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
    }

    private suspend fun initializeRepository(name: String): Path {
        val repository = Files.createDirectory(temporary.resolve(name))
        git(repository, "init", "--initial-branch=main")
        Files.writeString(repository.resolve("tracked.txt"), "original\n")
        Files.writeString(repository.resolve(".gitignore"), "ignored/\n")
        git(repository, "add", "tracked.txt", ".gitignore")
        commit(repository, "initial")
        return repository.toRealPath()
    }

    private suspend fun addWorktree(repository: Path, branch: String, name: String): Path {
        val directory = temporary.resolve(name)
        git(repository, "worktree", "add", "-b", branch, directory.toString())
        return directory.toRealPath()
    }

    private suspend fun commit(repository: Path, message: String) {
        git(repository, "-c", "user.name=Worktree Test", "-c", "user.email=worktree@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", message)
    }

    private suspend fun git(repository: Path, vararg arguments: String): String = withContext(Dispatchers.IO) {
        val command = GeneralCommandLine(GitExecutableManager.getInstance().pathToGit)
            .withWorkingDirectory(repository)
            .withCharset(Charsets.UTF_8)
            .withParameters("-c", "core.hooksPath=/dev/null")
            .withParameters(*arguments)
        val environment = command.effectiveEnvironment.filterKeys { !it.startsWith("GIT_") }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(environment)
        val result = CapturingProcessHandler(command).runProcess(30_000)
        assertFalse("Git fixture command timed out: ${arguments.toList()}", result.isTimeout)
        assertFalse("Git fixture command was cancelled: ${arguments.toList()}", result.isCancelled)
        assertEquals("Git fixture command failed: ${result.stderr}", 0, result.exitCode)
        result.stdout
    }
}
