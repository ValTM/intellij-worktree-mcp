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

    fun testSquashCoverageUsesHistoricalSameRepositoryProofAndOlderSourceButNeverLaterCommits() = runBlocking {
        val repository = initialize()
        git(repository, "checkout", "-b", "feature")
        commit(repository, "older feature")
        val older = git(repository, "rev-parse", "HEAD").trim()
        commit(repository, "source feature")
        val source = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", "main")
        commit(repository, "squashed feature")
        val merged = git(repository, "rev-parse", "HEAD").trim()
        assertFalse(WorktreeCleanup.ancestor(repository, source, merged))
        git(repository, "checkout", "feature")
        val remoteUrl = "git@github.com:owner/repo.git"
        git(repository, "remote", "add", "origin", remoteUrl)
        val proof = WorktreeMergeProof(source, merged, "owner/repo", "main", "origin", remoteUrl)
        val target = WorktreeCleanupTarget("origin", remoteUrl, "main", merged)
        suspend fun classify(head: String = source, proofs: List<WorktreeMergeProof> = listOf(proof), destination: WorktreeCleanupTarget = target) =
            WorktreeCleanup.classify(repository, head, destination, WorktreeReviewStatus("Open #99", "Newest PR is unrelated.", mergeProofs = proofs))
        val evidence = classify()
        assertEquals("MERGED", evidence.kind)
        assertEquals("MERGED", classify(older).kind)
        assertFalse(evidence.fingerprint == classify(older).fingerprint)
        assertEquals("MERGED", classify(proofs = listOf(proof.copy(head = "f".repeat(40)), proof)).kind)
        assertEquals("BLOCKED", classify(proofs = listOf(proof.copy(baseRepository = "another/repo"))).kind)
        assertEquals("BLOCKED", classify(proofs = listOf(proof.copy(baseBranch = "release"))).kind)
        assertEquals("BLOCKED", classify(proofs = listOf(proof.copy(remoteUrl = "https://github.com/another/repo.git"))).kind)
        assertEquals("BLOCKED", classify(proofs = listOf(proof.copy(mergeCommit = "f".repeat(40)))).kind)
        assertEquals("BLOCKED", classify(destination = target.copy(head = git(repository, "rev-parse", "main^").trim())).kind)
        commit(repository, "later local commit")
        assertEquals("BLOCKED", classify(git(repository, "rev-parse", "HEAD").trim()).kind)
        withContext(Dispatchers.IO) { Files.writeString(repository.resolve(".git/shallow"), source + "\n") }
        try {
            classify()
            fail("Shallow history must not prove merge coverage")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Shallow"))
        }
    }

    fun testOlderSquashedSourceFetchesMissingVerifiedPrHeadWithoutChangingBranches() = runBlocking {
        val repository = initialize()
        git(repository, "checkout", "-b", "feature")
        commit(repository, "older local source")
        val older = git(repository, "rev-parse", "HEAD").trim()
        val clone = temporary.resolve("old-checkout")
        git(repository, "clone", "--no-local", repository.toString(), clone.toString())
        commit(repository, "final PR source")
        val source = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", "main")
        commit(repository, "squashed result")
        val merged = git(repository, "rev-parse", "HEAD").trim()
        val remoteDirectory = withContext(Dispatchers.IO) { Files.createDirectories(temporary.resolve("host/owner")) }
        val remote = remoteDirectory.resolve("repo.git")
        git(repository, "clone", "--bare", repository.toString(), remote.toString())
        git(remote, "update-ref", "-d", "refs/heads/feature")
        git(remote, "update-ref", "refs/pull/42/head", source)
        git(clone, "fetch", remote.toString(), "main:refs/remotes/origin/main")
        val remoteUrl = "https://github.com/owner/repo.git"
        git(clone, "remote", "set-url", "origin", remoteUrl)
        val localHost = temporary.resolve("host").toUri().toString().trimEnd('/') + "/"
        git(clone, "config", "url.$localHost.insteadOf", "https://github.com/")
        val target = WorktreeCleanupTarget("origin", remoteUrl, "main", merged)
        val proof = WorktreeMergeProof(source, merged, "owner/repo", "main", "origin", remoteUrl)
        val review = WorktreeReviewStatus("Merged #42", "Verified same repository.", mergeProof = proof)
        assertEquals("BLOCKED", WorktreeCleanup.classify(clone, older, target, review).kind)
        try {
            git(clone, "cat-file", "-e", "$source^{commit}")
            fail("Fixture must start without the final PR source object")
        } catch (_: java.io.IOException) {
            // The deleted remote source branch was never fetched into the older checkout.
        }
        val beforeRefs = git(clone, "for-each-ref", "--format=%(refname) %(objectname)")
        val fetched = WorktreeCleanup.proofEvidence(project, clone, older, target, review)
        assertEquals(fetched.details, "MERGED", fetched.kind)
        assertEquals(source, git(clone, "rev-parse", "FETCH_HEAD^{commit}").trim())
        assertEquals(older, git(clone, "rev-parse", "HEAD").trim())
        assertEquals(beforeRefs, git(clone, "for-each-ref", "--format=%(refname) %(objectname)"))
        val unavailable = WorktreeCleanup.proofEvidence(
            project, clone, older, target, review.copy(mergeProofs = listOf(proof.copy(head = "f".repeat(40)))),
        )
        assertEquals("BLOCKED", unavailable.kind)
        assertTrue(unavailable.details.contains("Cannot fetch verified PR source"))
    }

    fun testClosedUnmergedProofIsDistinctAndDoesNotCoverLaterLocalCommits() = runBlocking {
        val repository = initialize()
        val base = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", "-b", "feature")
        commit(repository, "older feature")
        val older = git(repository, "rev-parse", "HEAD").trim()
        commit(repository, "closed source")
        val source = git(repository, "rev-parse", "HEAD").trim()
        val remoteUrl = "https://github.com/owner/repo.git"
        git(repository, "remote", "add", "origin", remoteUrl)
        val proof = WorktreeClosedProof(source, "owner/repo", "main", "origin", remoteUrl, 42, "https://github.com/owner/repo/pull/42")
        val target = WorktreeCleanupTarget("origin", remoteUrl, "main", base)
        suspend fun classify(head: String = source, candidate: WorktreeClosedProof = proof) = WorktreeCleanup.classify(
            repository, head, target, WorktreeReviewStatus("Closed #42", "Not merged.", closedProofs = listOf(candidate)),
        )
        val closed = classify()
        assertEquals("CLOSED_UNMERGED", closed.kind)
        assertTrue(closed.details.contains("closed without merging"))
        assertEquals("CLOSED_UNMERGED", classify(older).kind)
        assertEquals("BLOCKED", classify(candidate = proof.copy(baseRepository = "other/repo")).kind)
        commit(repository, "later local commit")
        assertEquals("BLOCKED", classify(git(repository, "rev-parse", "HEAD").trim()).kind)
    }

    fun testGitOnlyCleanupChecksFreshDefaultTargetAndFingerprintWithoutGithub() = runBlocking {
        val repository = initialize()
        git(repository, "checkout", "-b", "feature")
        commit(repository, "feature")
        val source = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", "main")
        git(repository, "merge", "--ff-only", "feature")
        val linked = temporary.resolve("linked")
        git(repository, "worktree", "add", linked.toString(), "feature")
        val remote = temporary.resolve("remote.git")
        git(repository, "clone", "--bare", repository.toString(), remote.toString())
        git(repository, "remote", "add", "origin", remote.toString())
        git(repository, "update-ref", "refs/remotes/origin/main", source)
        val entry = GitWorktrees.discover(repository.toString()).single { it.directory == linked.toString() }
        suspend fun preview(candidate: GitWorktree = entry) = WorktreeCleanup.preview(project, repository.toString(), candidate)
        val first = preview()
        assertEquals(first.details, "MERGED", first.kind)
        assertTrue(first.fingerprint.isNotBlank())
        commit(repository, "target advances")
        val advanced = git(repository, "rev-parse", "HEAD").trim()
        git(remote, "fetch", repository.toString(), "main:main")
        assertEquals("BLOCKED", preview().kind)
        git(repository, "update-ref", "refs/remotes/origin/main", advanced)
        val refreshed = preview()
        assertEquals("MERGED", refreshed.kind)
        assertFalse(first.fingerprint == refreshed.fingerprint)
        git(remote, "symbolic-ref", "HEAD", "refs/heads/feature")
        assertEquals("BLOCKED", preview().kind)
        git(remote, "symbolic-ref", "HEAD", "refs/heads/main")
        assertEquals("BLOCKED", preview(entry.copy(head = "f".repeat(40))).kind)
        assertEquals("BLOCKED", preview(entry.copy(locked = "Keep")).kind)
        assertEquals("BLOCKED", preview(entry.copy(main = true)).kind)
        git(linked, "checkout", "--detach")
        val detached = GitWorktrees.discover(repository.toString()).single { it.directory == linked.toString() }
        assertTrue(detached.detached)
        assertEquals("MERGED", preview(detached).kind)
        commit(linked, "unmerged detached commit")
        val laterDetached = GitWorktrees.discover(repository.toString()).single { it.directory == linked.toString() }
        assertEquals("BLOCKED", preview(laterDetached).kind)
    }

    fun testDefaultTargetAdvertisementRequiresSymbolicHeadAndExactCommitIdentity() {
        val head = "a".repeat(40)
        assertEquals("main", WorktreeCleanup.advertisedTarget("origin", "remote", "ref: refs/heads/main\tHEAD\n$head\tHEAD\n").branch)
        for (advertisement in listOf("$head\tHEAD", "ref: refs/heads/main\tHEAD", "ref: refs/heads/main\tHEAD\ninvalid\tHEAD")) {
            try {
                WorktreeCleanup.advertisedTarget("origin", "remote", advertisement)
                fail("Unknown remote default target must be blocked")
            } catch (_: java.io.IOException) {
                // Expected: neither cached remote HEAD nor a missing object proves a live default target.
            }
        }
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
