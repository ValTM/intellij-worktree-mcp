package local.worktree

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest

internal data class CleanupEvidence(val kind: String, val details: String, val fingerprint: String)

internal data class WorktreeCleanupTarget(val remoteName: String, val remoteUrl: String, val branch: String, val head: String)

internal object WorktreeCleanup {
    suspend fun preview(project: Project, repositoryDirectory: String, entry: GitWorktree): CleanupEvidence = withContext(Dispatchers.IO) {
        try {
            require(!entry.main && !entry.bare && entry.locked == null && entry.prunable == null) {
                "Main, bare, locked, or prunable worktrees cannot be cleaned up."
            }
            val directory = Path.of(entry.directory).toRealPath()
            val repositoryDirectoryPath = Path.of(repositoryDirectory).toRealPath()
            require(commonDirectory(directory) == commonDirectory(repositoryDirectoryPath)) { "Worktree repository identity changed." }
            val head = GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim()
            val branch = GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim()
            require(head == entry.head && branch == (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")) { "Worktree source identity changed; Refresh." }
            require(GitWorktrees.git(directory, "rev-parse", "--is-shallow-repository").trim() == "false") { "Shallow history cannot prove cleanup safety." }
            val remoteName = remoteName(directory, entry.branch)
            val remoteUrl = remoteUrl(directory, remoteName)
            // Native Git owns SSH/HTTP credentials and cancellation, just as Refresh does.
            val handler = GitLineHandler(project, directory, GitCommand.LS_REMOTE)
            handler.setUrls(listOf(remoteUrl))
            handler.addParameters("--symref", "--", remoteUrl, "HEAD")
            val advertisement = coroutineToIndicator { Git.getInstance().runCommand(handler).getOutputOrThrow() }
            val target = advertisedTarget(remoteName, remoteUrl, advertisement)
            val localTarget = GitWorktrees.git(directory, "rev-parse", "--verify", "--end-of-options", "refs/remotes/$remoteName/${target.branch}^{commit}").trim()
            require(localTarget == target.head) { "Remote default target changed or has not been fetched; Refresh before cleanup." }
            val ancestry = classify(directory, head, target, null)
            val evidence = if (ancestry.kind == "MERGED" || entry.branch == null || entry.detached) ancestry else {
                val review = WorktreeStatusReader.repository(project, repositoryDirectory)?.let { repository ->
                    project.getService(WorktreeReviewStatusProvider::class.java)?.read(repository, remoteName, entry.branch)
                }
                proofEvidence(project, directory, head, target, review)
            }
            require(remoteUrl(directory, remoteName) == remoteUrl && remoteName(directory, entry.branch) == remoteName &&
                GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim() == head &&
                GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim() == branch) { "Source or remote identity changed during cleanup inspection; Refresh." }
            evidence.copy(fingerprint = fingerprint(entry.directory, branch, evidence.fingerprint))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            CleanupEvidence("BLOCKED", error.message ?: "Cleanup proof is unavailable.", "")
        }
    }

    internal fun advertisedTarget(remoteName: String, remoteUrl: String, advertisement: String): WorktreeCleanupTarget {
        val records = advertisement.lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()
        val branch = records.singleOrNull { it.size == 2 && it[0].startsWith("ref: refs/heads/") && it[1] == "HEAD" }
            ?.get(0)?.removePrefix("ref: refs/heads/") ?: throw IOException("Remote default branch identity is unavailable.")
        val head = records.singleOrNull { it.size == 2 && validHash(it[0]) && it[1] == "HEAD" }?.get(0)
            ?: throw IOException("Remote default target commit is unavailable.")
        return WorktreeCleanupTarget(remoteName, remoteUrl, branch, head)
    }

    internal suspend fun classify(directory: Path, head: String, target: WorktreeCleanupTarget, review: WorktreeReviewStatus?): CleanupEvidence {
        require(validHash(head) && validHash(target.head)) { "Invalid cleanup source or target commit." }
        require(GitWorktrees.git(directory, "rev-parse", "--is-shallow-repository").trim() == "false") { "Shallow history cannot prove cleanup safety." }
        GitWorktrees.git(directory, "check-ref-format", "refs/heads/${target.branch}")
        require(remoteUrl(directory, target.remoteName) == target.remoteUrl) { "Cleanup target remote changed; Refresh." }
        fun evidence(kind: String, details: String, proof: String) = CleanupEvidence(
            kind, details, fingerprint(head, target.remoteName, target.remoteUrl, target.branch, target.head, kind, proof),
        )
        if (ancestor(directory, head, target.head)) return evidence("MERGED", "Current HEAD is retained in the remote default target ${target.remoteName}/${target.branch}.", "ancestry")
        for (proof in review?.mergeProofs.orEmpty()) {
            if (matchesTarget(target, proof.remoteName, proof.remoteUrl, proof.baseRepository, proof.baseBranch) &&
                validHash(proof.head) && validHash(proof.mergeCommit) && knownAncestor(directory, head, proof.head) && knownAncestor(directory, proof.mergeCommit, target.head)) {
                return evidence("MERGED", "Current HEAD is the merged PR source or its ancestor; merge commit ${proof.mergeCommit} is retained in ${target.remoteName}/${target.branch}.", proof.toString())
            }
        }
        for (proof in review?.closedProofs.orEmpty()) {
            if (matchesTarget(target, proof.remoteName, proof.remoteUrl, proof.baseRepository, proof.baseBranch) &&
                validHash(proof.head) && knownAncestor(directory, head, proof.head)) {
                return evidence("CLOSED_UNMERGED", "PR #${proof.number} was closed without merging. Current HEAD is its source or an ancestor; removal discards unmerged work and requires explicit opt-in.", proof.toString())
            }
        }
        return evidence("BLOCKED", listOfNotNull(
            "Current HEAD is not proven merged into the remote default target or covered by a verified closed-unmerged PR. Later local commits are never automatically discarded.",
            review?.error ?: review?.details,
        ).joinToString(" "), "unproven")
    }

    internal suspend fun proofEvidence(
        project: Project, directory: Path, head: String, target: WorktreeCleanupTarget, review: WorktreeReviewStatus?,
    ): CleanupEvidence {
        val mergedSources = review?.mergeProofs.orEmpty().filter {
            matchesTarget(target, it.remoteName, it.remoteUrl, it.baseRepository, it.baseBranch) &&
                validHash(it.head) && validHash(it.mergeCommit) && knownAncestor(directory, it.mergeCommit, target.head)
        }.map { it.head }
        val closedSources = review?.closedProofs.orEmpty().filter {
            matchesTarget(target, it.remoteName, it.remoteUrl, it.baseRepository, it.baseBranch) && validHash(it.head)
        }.map { it.head }
        val failures = mutableListOf<String>()
        var verifiedReview = review
        for ((sources, wanted) in listOf(mergedSources to "MERGED", closedSources to "CLOSED_UNMERGED")) {
            var evidence = classify(directory, head, target, verifiedReview)
            if (evidence.kind == "MERGED" || evidence.kind == wanted) return evidence
            for (source in sources.distinct()) {
                if (hasCommit(directory, source)) continue
                try {
                    require(remoteUrl(directory, target.remoteName) == target.remoteUrl) { "Cleanup target remote changed; Refresh." }
                    // A verified same-repository PR SHA can outlive its deleted branch. Fetch objects only;
                    // an explicit SHA with a URL writes FETCH_HEAD, never a local branch or remote-tracking ref.
                    val handler = GitLineHandler(project, directory, GitCommand.FETCH)
                    handler.setUrls(listOf(target.remoteUrl))
                    handler.addParameters("--no-tags", "--no-recurse-submodules", "--", target.remoteUrl, source)
                    coroutineToIndicator { Git.getInstance().runCommand(handler).getOutputOrThrow() }
                    require(GitWorktrees.git(directory, "rev-parse", "--verify", "FETCH_HEAD^{commit}").trim() == source &&
                        GitWorktrees.git(directory, "rev-parse", "--verify", "--end-of-options", "$source^{commit}").trim() == source) {
                        "Fetched PR source does not match the verified SHA $source."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures.add("Cannot fetch verified PR source $source: ${error.message ?: error.javaClass.simpleName}")
                    verifiedReview = verifiedReview?.let { snapshot ->
                        snapshot.copy(
                            mergeProofs = snapshot.mergeProofs.filter { it.head != source },
                            closedProofs = snapshot.closedProofs.filter { it.head != source },
                        )
                    }
                    continue
                }
                evidence = classify(directory, head, target, verifiedReview)
                if (evidence.kind == "MERGED" || evidence.kind == wanted) return evidence
            }
        }
        val evidence = classify(directory, head, target, verifiedReview)
        return if (evidence.kind == "BLOCKED" && failures.isNotEmpty()) {
            evidence.copy(details = evidence.details + " " + failures.joinToString("; "))
        } else evidence
    }

    private suspend fun hasCommit(directory: Path, head: String): Boolean = try {
        GitWorktrees.git(directory, "cat-file", "-e", "$head^{commit}")
        true
    } catch (_: IOException) {
        false
    }

    internal suspend fun ancestor(directory: Path, ancestor: String, descendant: String): Boolean {
        require(validHash(ancestor) && validHash(descendant)) { "Invalid proof commit ID." }
        return GitWorktrees.git(directory, "rev-list", "--count", "$descendant..$ancestor", "--").trim() == "0"
    }

    internal suspend fun localEvidence(directory: Path, head: String, branch: String?, review: WorktreeReviewStatus): CleanupEvidence {
        try {
            val remote = remoteName(directory, branch)
            val defaultRef = GitWorktrees.git(directory, "symbolic-ref", "refs/remotes/$remote/HEAD").trim()
            require(defaultRef.startsWith("refs/remotes/$remote/")) { "Default target identity is unknown; Refresh before cleanup." }
            val target = WorktreeCleanupTarget(
                remote, remoteUrl(directory, remote), defaultRef.removePrefix("refs/remotes/$remote/"),
                GitWorktrees.git(directory, "rev-parse", "--verify", "--end-of-options", "$defaultRef^{commit}").trim(),
            )
            return classify(directory, head, target, review)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            return CleanupEvidence("BLOCKED", "Local merge proof unavailable: ${error.message}. Refresh before cleanup.", "")
        }
    }

    private suspend fun knownAncestor(directory: Path, source: String, descendant: String): Boolean =
        try { ancestor(directory, source, descendant) } catch (_: IOException) { false }

    internal suspend fun remoteName(directory: Path, branch: String?): String {
        val remotes = GitWorktrees.git(directory, "remote").lineSequence().filter { it.isNotBlank() }.toList()
        val upstreamRemote = branch?.let {
            GitWorktrees.git(directory, "for-each-ref", "--format=%(upstream:remotename)", "refs/heads/$it").trim().takeIf { name -> name.isNotEmpty() && name != "." }
        }
        if (upstreamRemote != null) return upstreamRemote.takeIf { it in remotes }
            ?: throw IOException("Configured upstream remote is unavailable; Refresh before cleanup.")
        return remotes.singleOrNull { it == "origin" } ?: remotes.singleOrNull()
            ?: throw IOException("Choose an unambiguous remote before cleanup.")
    }

    internal suspend fun remoteUrl(directory: Path, remoteName: String): String =
        GitWorktrees.git(directory, "config", "--null", "--get-all", "remote.$remoteName.url").split('\u0000').filter { it.isNotEmpty() }.singleOrNull()
            ?: throw IOException("Cleanup remote must have exactly one URL.")

    private fun matchesTarget(target: WorktreeCleanupTarget, remoteName: String, remoteUrl: String, repository: String, branch: String): Boolean {
        val path = (if ("://" in remoteUrl) remoteUrl.substringAfter("://").substringAfter('/', "")
            else remoteUrl.substringAfter(':', "")).removeSuffix("/").removeSuffix(".git")
        return target.remoteName == remoteName && target.remoteUrl == remoteUrl && target.branch == branch &&
            repository.isNotBlank() && path.equals(repository, ignoreCase = true)
    }

    private suspend fun commonDirectory(directory: Path): Path = Path.of(
        GitWorktrees.git(directory, "rev-parse", "--path-format=absolute", "--git-common-dir").trim(),
    ).toRealPath()

    private fun validHash(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))

    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
