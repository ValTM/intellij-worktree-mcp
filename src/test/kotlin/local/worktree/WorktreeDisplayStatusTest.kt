package local.worktree

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class WorktreeDisplayStatusTest : HeavyPlatformTestCase() {
    private lateinit var temporary: Path
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("worktree-status-").toRealPath()
    }

    override fun tearDown() {
        try {
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testRealGitDistinguishesUnpublishedNoUpstreamAndSyncDirections() = runBlocking {
        val repository = initialize()
        suspend fun status(): WorktreeDisplayStatus = WorktreeStatusReader.read(
            project, repository.toString(), GitWorktrees.discover(repository.toString()).single(),
        )
        assertEquals("Unpublished", status().syncLabel)
        git(repository, "remote", "add", "origin", temporary.resolve("unused-remote").toString())
        val initial = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "update-ref", "refs/remotes/origin/main", initial)
        assertEquals("No upstream", status().syncLabel)
        git(repository, "branch", "--set-upstream-to=origin/main", "main")
        assertEquals("In sync", status().syncLabel)
        commit(repository, "local")
        assertEquals("Ahead ↑1", status().syncLabel)
        val local = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "reset", "--hard", initial)
        git(repository, "update-ref", "refs/remotes/origin/main", local)
        assertEquals("Behind ↓1", status().syncLabel)
        commit(repository, "different remote history")
        assertEquals("Diverged ↑1 ↓1", status().syncLabel)
        git(repository, "update-ref", "-d", "refs/remotes/origin/main")
        assertEquals("Upstream gone", status().syncLabel)
    }

    fun testRealGitReportsDirtyUntrackedDetachedLockedMissingAndChangedHead() = runBlocking {
        val repository = initialize()
        val tracked = repository.resolve("tracked.txt")
        withContext(Dispatchers.IO) { Files.writeString(tracked, "original") }
        git(repository, "add", "tracked.txt")
        commit(repository, "tracked")
        withContext(Dispatchers.IO) {
            Files.writeString(tracked, "modified")
            Files.writeString(repository.resolve("untracked.txt"), "untracked")
        }
        var entry = GitWorktrees.discover(repository.toString()).single()
        var status = WorktreeStatusReader.read(project, repository.toString(), entry)
        assertTrue(status.syncLabel.contains("Dirty (1)"))
        assertTrue(status.syncLabel.contains("Untracked (1)"))
        assertFalse(status.mergedHeadCovered)
        commit(repository, "later commit")
        status = WorktreeStatusReader.read(project, repository.toString(), entry)
        assertEquals("Unknown", status.syncLabel)
        assertNotNull(status.error)
        assertFalse(status.mergedHeadCovered)
        git(repository, "checkout", "--detach")
        entry = GitWorktrees.discover(repository.toString()).single()
        status = WorktreeStatusReader.read(project, repository.toString(), entry)
        assertTrue(status.syncLabel.startsWith("Detached"))
        assertEquals("—", status.reviewLabel)
        val linked = temporary.resolve("linked")
        git(repository, "worktree", "add", "-b", "linked", linked.toString())
        git(repository, "worktree", "lock", "--reason", "Keep this checkout", linked.toString())
        entry = GitWorktrees.discover(repository.toString()).single { it.directory == linked.toString() }
        status = WorktreeStatusReader.read(project, repository.toString(), entry)
        assertTrue(status.syncLabel.startsWith("Locked"))
        assertTrue(status.syncDetails.contains("Keep this checkout"))
        assertFalse(status.mergedHeadCovered)
        withContext(Dispatchers.IO) { FileUtil.delete(linked.toFile()) }
        status = WorktreeStatusReader.read(project, repository.toString(), entry)
        assertEquals("Missing", status.syncLabel)
        assertFalse(status.mergedHeadCovered)
    }

    fun testReviewStatesRemainVisibleWithoutCleanupProof() {
        val proof = WorktreeMergeProof("a".repeat(40), "b".repeat(40), "owner/repo", "main", "origin", "remote-url")
        fun review(state: String, draft: Boolean = false, merged: Boolean = false, target: WorktreeMergeProof? = proof) =
            reviewSummary(42, state, draft, merged, "A review", "https://github.com/owner/repo/pull/42", target)
        assertEquals("Draft #42", review("open", draft = true).label)
        assertEquals("Open #42", review("open").label)
        assertEquals("Closed #42", review("closed").label)
        assertNull(review("closed").mergeProof)
        assertNull(review("open").mergeProof)
        assertEquals(proof, review("closed", merged = true).mergeProof)
        val incomplete = review("closed", merged = true, target = null)
        assertEquals("Merged #42", incomplete.label)
        assertNull(incomplete.mergeProof)
        assertTrue(incomplete.details.contains("cleanup is unavailable"))
        assertTrue(incomplete.details.contains("Refresh"))
    }

    fun testMergeCoverageRequiresExactSourceAndMergeRetainedInUnchangedTargetRemote() = runBlocking {
        val repository = initialize()
        git(repository, "checkout", "-b", "feature")
        commit(repository, "source feature")
        val source = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", "main")
        commit(repository, "squashed feature")
        val merged = git(repository, "rev-parse", "HEAD").trim()
        // A squash target has no source commit ancestry; GitHub's exact source proof bridges it.
        assertFalse(git(repository, "merge-base", source, merged).trim() == source)
        git(repository, "checkout", "feature")
        val remoteUrl = temporary.resolve("remote").toString()
        git(repository, "remote", "add", "origin", remoteUrl)
        val proof = WorktreeMergeProof(source, merged, "owner/repo", "main", "origin", remoteUrl)
        suspend fun coverage(target: WorktreeMergeProof = proof, head: String = source, safe: Boolean = true) =
            WorktreeStatusReader.mergeCoverage(repository, head, target, safe)
        val missingTarget = coverage()
        assertFalse(missingTarget.first)
        assertTrue(missingTarget.second.contains("Refresh"))
        git(repository, "update-ref", "refs/remotes/origin/main", source)
        val replacedTarget = coverage()
        assertFalse(replacedTarget.first)
        assertTrue(replacedTarget.second.contains("not retained"))
        git(repository, "update-ref", "refs/remotes/origin/main", merged)
        assertTrue(coverage().first)
        assertFalse(coverage(proof.copy(mergeCommit = "f".repeat(40))).first)
        assertFalse(coverage(proof.copy(baseRepository = "")).first)
        assertFalse(coverage(proof.copy(baseBranch = "missing")).first)
        assertFalse(coverage(proof.copy(mergeCommit = "")).first)
        assertFalse(coverage(safe = false).first)
        commit(repository, "later local commit")
        val later = git(repository, "rev-parse", "HEAD").trim()
        assertFalse(coverage(head = later).first)
        git(repository, "remote", "set-url", "origin", temporary.resolve("different-remote").toString())
        val changedRemote = coverage()
        assertFalse(changedRemote.first)
        assertTrue(changedRemote.second.contains("remote changed"))
    }

    fun testDirtyParserDoesNotMistakeRenameSourceOrNewlinePathsForFiles() {
        assertEquals("Clean", WorktreeStatusReader.dirtySummary(""))
        assertEquals("Dirty (2), Untracked (1)", WorktreeStatusReader.dirtySummary(
            "R  renamed\u0000?? source name\u0000 M path\nwith newline\u0000?? new directory/\u0000",
        ))
    }

    private suspend fun initialize(): Path = withContext(Dispatchers.IO) {
        val repository = Files.createDirectory(temporary.resolve("repo"))
        git(repository, "init", "--initial-branch=main")
        commit(repository, "initial")
        repository
    }

    private suspend fun commit(repository: Path, message: String) = git(
        repository, "-c", "user.name=Worktree Test", "-c", "user.email=worktree@example.invalid",
        "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", message,
    )

    private suspend fun git(repository: Path, vararg arguments: String): String =
        withContext(Dispatchers.IO) { GitWorktrees.git(repository, *arguments) }
}
