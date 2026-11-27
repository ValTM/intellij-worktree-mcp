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
import local.worktree.GitWorktrees.RemovalOptions
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
        assertTrue(plan.ignoredFiles.isEmpty())
        assertEquals(RemovalOptions(), plan.options)
        assertFalse(plan.remoteBranchAbsent)
        assertEquals(listOf(head), plan.unretainedCommitIds)
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
        val forced = GitWorktrees.previewRemove(repository.toString(), linked.toString(), RemovalOptions(deleteLocalChanges = true))
        assertTrue(RemovalBlockReason.LOCKED in forced.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(forced, forced.confirmation) }
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
        assertFalse(plan.untrackedFiles.any { it.startsWith("ignored/") })
        assertTrue("ignored/valuable.txt" in plan.ignoredFiles)
        assertTrue(RemovalBlockReason.TRACKED_CHANGES in plan.blockedCodes)
        assertTrue(RemovalBlockReason.UNTRACKED_FILES in plan.blockedCodes)
        assertTrue(RemovalBlockReason.IGNORED_FILES in plan.blockedCodes)
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
        val forced = GitWorktrees.previewRemove(repository.toString(), linked.toString(),
            RemovalOptions(deleteLocalBranch = true, deleteLocalChanges = true))
        assertTrue(RemovalBlockReason.UNRETAINED_COMMITS in forced.blockedCodes)
        assertTrue(RemovalBlockReason.BRANCH_REQUIRED in forced.blockedCodes)
        assertEquals(listOf(head), forced.unretainedCommitIds)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(forced, forced.confirmation) }
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
        git(repository, "tag", "new-persistent-ref", advanced.head!!)
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

    fun testBranchDeletionUsesLiveConfiguredUpstreamAndDisclosesLostCommits() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val remote = addRemote(repository, "origin")
        git(repository, "push", "--set-upstream", "origin", "feature:remote-feature")
        git(remote, "update-ref", "refs/heads/feature", "refs/heads/remote-feature")
        val options = RemovalOptions(deleteLocalBranch = true)
        val present = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(present.remoteBranchAbsent)
        assertTrue("refs/heads/remote-feature" in present.remoteBranchDetails)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_PRESENT in present.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(present, present.confirmation) }
        // Leave the cached tracking ref behind: only a live query proves absence.
        git(remote, "update-ref", "-d", "refs/heads/remote-feature")
        commit(linked, "unpublished local commit")
        val head = git(linked, "rev-parse", "HEAD").trim()
        val retained = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(retained.remoteBranchAbsent)
        assertEquals(0, retained.unsafeCommits)
        assertEquals(listOf(head), retained.unretainedCommitIds)
        val selected = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertEquals(options, selected.options)
        assertEquals(1, selected.unsafeCommits)
        assertEquals(listOf(head), selected.unretainedCommitIds)
        assertTrue(selected.blockedReasons.toString(), selected.blockedCodes.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.remove(retained.copy(options = options), retained.confirmation)
        }
        assertEquals(linked.toString(), GitWorktrees.remove(selected, selected.confirmation))
        assertEquals("", git(repository, "for-each-ref", "--format=%(refname)", "refs/heads/feature"))
        assertEquals("refs/heads/feature\n", git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/feature"))
        assertTrue(Files.isDirectory(repository))
    }

    fun testSameNameBranchesAcrossAllRemotesAndOfflineDefaultRemoval() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val options = RemovalOptions(deleteLocalBranch = true)
        val noRemotes = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(noRemotes.remoteBranchAbsent)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_UNKNOWN in noRemotes.blockedCodes)
        val origin = addRemote(repository, "origin")
        val backup = addRemote(repository, "backup")
        git(repository, "push", "backup", "feature")
        // No cached tracking ref is needed to detect the actual remote branch.
        git(repository, "update-ref", "-d", "refs/remotes/backup/feature")
        val present = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(present.remoteBranchAbsent)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_PRESENT in present.blockedCodes)
        git(backup, "update-ref", "-d", "refs/heads/feature")
        val absent = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(absent.remoteBranchAbsent)
        assertTrue("origin: refs/heads/feature is absent" in absent.remoteBranchDetails)
        assertTrue("backup: refs/heads/feature is absent" in absent.remoteBranchDetails)
        git(repository, "remote", "set-url", "backup", temporary.resolve("offline").toString())
        val unavailable = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(unavailable.remoteBranchAbsent)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_UNKNOWN in unavailable.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(unavailable, unavailable.confirmation) }
        val safe = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(safe.blockedReasons.toString(), safe.blockedCodes.isEmpty())
        GitWorktrees.remove(safe, safe.confirmation)
        assertEquals(safe.head, git(repository, "rev-parse", "refs/heads/feature").trim())
        assertEquals("", git(origin, "for-each-ref", "--format=%(refname)", "refs/heads/feature"))
    }

    fun testBranchDeletionRevalidatesRemoteStateIdentityAndApprovedTip() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val remote = addRemote(repository, "origin")
        val options = RemovalOptions(deleteLocalBranch = true)
        val absent = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        git(repository, "push", "origin", "feature")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(absent, absent.confirmation) }
        git(remote, "update-ref", "-d", "refs/heads/feature")
        val beforeRemoteChange = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        val replacement = Files.createDirectory(temporary.resolve("replacement.git"))
        git(replacement, "init", "--bare")
        git(repository, "remote", "set-url", "origin", replacement.toString())
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.remove(beforeRemoteChange, beforeRemoteChange.confirmation)
        }
        val beforeCommit = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        commit(linked, "later local commit")
        val head = git(linked, "rev-parse", "HEAD").trim()
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(beforeCommit, beforeCommit.confirmation) }
        assertEquals(head, git(repository, "rev-parse", "refs/heads/feature").trim())
        assertTrue(Files.isDirectory(linked))
    }

    fun testSelectedBranchDeletionAllowsMissingCachedUpstreamButNotAnotherCheckout() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        addRemote(repository, "origin")
        git(repository, "config", "branch.feature.remote", "origin")
        git(repository, "config", "branch.feature.merge", "refs/heads/not-fetched")
        val options = RemovalOptions(deleteLocalBranch = true)
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertTrue(plan.remoteBranchAbsent)
        assertTrue(plan.historyKnown)
        assertNull(plan.unpushedCommits)
        assertTrue(plan.blockedReasons.toString(), plan.blockedCodes.isEmpty())
        val other = temporary.resolve("other")
        git(repository, "worktree", "add", "--force", other.toString(), "feature")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        val checkedOut = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertTrue(RemovalBlockReason.BRANCH_CHECKED_OUT in checkedOut.blockedCodes)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(checkedOut, checkedOut.confirmation) }
        assertTrue(Files.isDirectory(linked))
        assertTrue(Files.isDirectory(other))
    }

    fun testVerifiedDeletedRemoteAllowsRemovalWhileRetainingLocalBranch() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        addRemote(repository, "origin")
        git(repository, "config", "branch.feature.remote", "origin")
        git(repository, "config", "branch.feature.merge", "refs/heads/deleted")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString())
        assertTrue(plan.remoteBranchAbsent)
        assertTrue(plan.historyKnown)
        assertNull(plan.unpushedCommits)
        assertTrue(plan.blockedReasons.toString(), plan.blockedCodes.isEmpty())
        GitWorktrees.remove(plan, plan.confirmation)
        assertFalse(Files.exists(linked))
        assertEquals(plan.head, git(repository, "rev-parse", "refs/heads/feature").trim())
    }

    fun testDirtyDeletionRequiresExactTrackedUntrackedIgnoredSymlinkAndIndexContents() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val external = Files.writeString(temporary.resolve("outside"), "valuable external content")
        val tracked = Files.writeString(linked.resolve("tracked.txt"), "first\n")
        val untracked = Files.writeString(linked.resolve("untracked"), "first\n")
        Files.createDirectories(linked.resolve("ignored/nested"))
        val ignored = Files.writeString(linked.resolve("ignored/nested/valuable"), "first\n")
        val link = Files.createSymbolicLink(linked.resolve("ignored/link"), external)
        val options = RemovalOptions(deleteLocalChanges = true)
        var plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertTrue(plan.blockedReasons.toString(), plan.blockedCodes.isEmpty())
        assertEquals(listOf("untracked"), plan.untrackedFiles)
        assertTrue("ignored/nested/valuable" in plan.ignoredFiles)
        assertTrue("ignored/link" in plan.ignoredFiles)
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.remove(plan.copy(options = RemovalOptions()), plan.confirmation)
        }
        for (path in listOf(tracked, untracked, ignored)) {
            Files.writeString(path, "other\n")
            assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
            assertTrue(Files.isDirectory(linked))
            plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        }
        Files.delete(link)
        Files.createSymbolicLink(link, temporary.resolve("another"))
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        Files.writeString(linked.resolve("ignored/nested/new"), "new ignored content")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        Files.writeString(tracked, "index-one\n")
        git(linked, "add", "tracked.txt")
        Files.writeString(tracked, "worktree\n")
        plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        Files.writeString(tracked, "index-two\n")
        git(linked, "add", "tracked.txt")
        Files.writeString(tracked, "worktree\n")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(plan, plan.confirmation) }
        plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        val head = plan.head
        GitWorktrees.remove(plan, plan.confirmation)
        assertFalse(Files.exists(linked))
        assertEquals("valuable external content", Files.readString(external))
        assertEquals(head, git(repository, "rev-parse", "refs/heads/feature").trim())
    }

    fun testIndependentBranchDeletionBatchKeepsOriginalConfirmationsValid() = runBlocking {
        val repository = initializeRepository("repository")
        val first = addWorktree(repository, "first", "first")
        val second = addWorktree(repository, "second", "second")
        addRemote(repository, "origin")
        commit(first, "first local commit")
        commit(second, "second local commit")
        val options = RemovalOptions(deleteLocalBranch = true)
        val firstPlan = GitWorktrees.previewRemove(repository.toString(), first.toString(), options)
        val secondPlan = GitWorktrees.previewRemove(repository.toString(), second.toString(), options)
        assertEquals(1, firstPlan.unretainedCommitIds.size)
        assertEquals(1, secondPlan.unretainedCommitIds.size)
        GitWorktrees.remove(firstPlan, firstPlan.confirmation)
        GitWorktrees.remove(secondPlan, secondPlan.confirmation)
        assertEquals("", git(repository, "for-each-ref", "--format=%(refname)", "refs/heads/first", "refs/heads/second"))
    }

    fun testDeletingRetentionBranchInvalidatesOtherApprovedLosingCommits() = runBlocking {
        val repository = initializeRepository("repository")
        val first = addWorktree(repository, "first", "first")
        commit(first, "shared local commit")
        val second = temporary.resolve("second")
        git(repository, "worktree", "add", "-b", "second", second.toString(), "first")
        addRemote(repository, "origin")
        val options = RemovalOptions(deleteLocalBranch = true)
        val firstPlan = GitWorktrees.previewRemove(repository.toString(), first.toString(), options)
        val secondPlan = GitWorktrees.previewRemove(repository.toString(), second.toString(), options)
        assertTrue(firstPlan.unretainedCommitIds.isEmpty())
        assertTrue(secondPlan.unretainedCommitIds.isEmpty())
        GitWorktrees.remove(firstPlan, firstPlan.confirmation)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.remove(secondPlan, secondPlan.confirmation) }
        val changed = GitWorktrees.previewRemove(repository.toString(), second.toString(), options)
        assertEquals(listOf(secondPlan.head), changed.unretainedCommitIds)
        assertTrue(Files.isDirectory(second))
    }

    fun testBranchDeletionFailureExplicitlyReportsAlreadyRemovedWorktree() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        addRemote(repository, "origin")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), RemovalOptions(deleteLocalBranch = true))
        val lock = Path.of(git(repository, "rev-parse", "--path-format=absolute", "--git-path", "refs/heads/feature.lock").trim())
        Files.writeString(lock, "")
        val failure = assertFailsWith<GitWorktrees.RemovalException> { GitWorktrees.remove(plan, plan.confirmation) }
        assertTrue(failure.message.orEmpty().contains("Worktree was removed at $linked"))
        assertTrue(failure.message.orEmpty().contains("local branch 'feature' did not complete"))
        assertFalse(Files.exists(linked))
        assertEquals(plan.head, git(repository, "rev-parse", "refs/heads/feature").trim())
    }

    fun testLocalUpstreamIsNotRemoteProofAndSimilarBranchMetadataIsPreserved() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        git(repository, "config", "branch.feature.remote", ".")
        git(repository, "config", "branch.feature.merge", "refs/heads/main")
        git(repository, "config", "branch.feature.extra.description", "keep")
        val options = RemovalOptions(deleteLocalBranch = true)
        val localOnly = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(localOnly.remoteBranchAbsent)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_UNKNOWN in localOnly.blockedCodes)
        val remote = addRemote(repository, "origin")
        git(repository, "push", "origin", "feature")
        val present = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertFalse(present.remoteBranchAbsent)
        assertTrue(RemovalBlockReason.REMOTE_BRANCH_PRESENT in present.blockedCodes)
        git(remote, "update-ref", "-d", "refs/heads/feature")
        val absent = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertTrue(absent.remoteBranchAbsent)
        assertTrue(absent.blockedReasons.toString(), absent.blockedCodes.isEmpty())
        git(repository, "config", "--remove-section", "branch.feature")
        val noOwnMetadata = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        GitWorktrees.remove(noOwnMetadata, noOwnMetadata.confirmation)
        assertEquals("keep", git(repository, "config", "--get", "branch.feature.extra.description").trim())
    }

    fun testIgnoredSymlinkDoesNotTraverseOrDiscardExternalDirectoryContents() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        val external = Files.createDirectory(temporary.resolve("outside"))
        val valuable = Files.writeString(external.resolve("valuable"), "before")
        Files.createDirectory(linked.resolve("ignored"))
        Files.createSymbolicLink(linked.resolve("ignored/external"), external)
        val options = RemovalOptions(deleteLocalChanges = true)
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), options)
        assertTrue("ignored/external" in plan.ignoredFiles)
        assertFalse(plan.ignoredFiles.any { "valuable" in it })
        Files.writeString(valuable, "after")
        assertEquals(plan, GitWorktrees.previewRemove(repository.toString(), linked.toString(), options))
        GitWorktrees.remove(plan, plan.confirmation)
        assertEquals("after", Files.readString(valuable))
    }

    fun testLosingCommitsExcludeSymbolicAliasesAndUseOriginalCommitGraph() = runBlocking {
        val repository = initializeRepository("repository")
        val linked = addWorktree(repository, "feature", "linked")
        addRemote(repository, "origin")
        commit(linked, "first local commit")
        val first = git(linked, "rev-parse", "HEAD").trim()
        commit(linked, "second local commit")
        val second = git(linked, "rev-parse", "HEAD").trim()
        git(repository, "symbolic-ref", "refs/archive/feature", "refs/heads/feature")
        git(repository, "replace", second, "main")
        val plan = GitWorktrees.previewRemove(repository.toString(), linked.toString(), RemovalOptions(deleteLocalBranch = true))
        assertEquals(listOf(second, first), plan.unretainedCommitIds)
        assertEquals(2, plan.unsafeCommits)
        assertTrue(plan.blockedReasons.toString(), plan.blockedCodes.isEmpty())
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

    private suspend fun addRemote(repository: Path, name: String): Path {
        val remote = Files.createDirectory(temporary.resolve("$name.git"))
        git(remote, "init", "--bare")
        git(repository, "remote", "add", name, remote.toString())
        return remote
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
