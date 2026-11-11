package local.worktree

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class WorktreeDisplayStatus(
    val syncLabel: String,
    val syncDetails: String,
    val reviewLabel: String,
    val reviewDetails: String,
    val reviewUrl: String? = null,
    val mergedHeadCovered: Boolean = false,
    val error: String? = null,
)

// The optional GitHub service is the only class that links to GitHub plugin APIs.
internal interface WorktreeReviewStatusProvider {
    suspend fun read(repository: GitRepository, remoteName: String?, branch: String): WorktreeReviewStatus
    fun invalidate()
}

internal data class WorktreeReviewStatus(
    val label: String,
    val details: String,
    val url: String? = null,
    val mergeProof: WorktreeMergeProof? = null,
    val error: String? = null,
)

internal data class WorktreeMergeProof(
    val head: String,
    val mergeCommit: String,
    val baseRepository: String,
    val baseBranch: String,
    val remoteName: String,
    val remoteUrl: String,
)

internal fun reviewSummary(
    number: Long, state: String, draft: Boolean, merged: Boolean, title: String, url: String,
    mergeProof: WorktreeMergeProof?,
): WorktreeReviewStatus {
    require(state == "open" || state == "closed") { "Unknown GitHub review state: $state" }
    require(!merged || state == "closed") { "Inconsistent GitHub merge state" }
    val label = when {
        merged -> "Merged"
        state == "closed" -> "Closed"
        draft -> "Draft"
        else -> "Open"
    }
    val proof = if (merged) {
        if (mergeProof != null) " Source HEAD ${mergeProof.head} must still be verified against ${mergeProof.baseRepository}:${mergeProof.baseBranch}."
        else " GitHub did not return a complete merge target proof; cleanup is unavailable. Check the GitHub remote and Refresh."
    } else if (state == "closed") " Closed without merging; no merge coverage." else ""
    return WorktreeReviewStatus(
        "$label #$number", "$label pull request #$number: $title.$proof GitHub snapshot; click Refresh to update.",
        url, mergeProof.takeIf { merged },
    )
}


internal object WorktreeStatusReader {
    suspend fun read(project: Project, repositoryDirectory: String, entry: GitWorktree): WorktreeDisplayStatus =
        withContext(Dispatchers.IO) {
            if (entry.bare) return@withContext WorktreeDisplayStatus("Bare", "Bare repository has no working tree.", "—", "No checked-out branch.")
            if (!Files.isDirectory(Path.of(entry.directory))) return@withContext WorktreeDisplayStatus(
                "Missing", "Worktree directory is missing or inaccessible. ${entry.prunable.orEmpty()}", "Unknown", "Restore the directory and Refresh before checking its review.",
            )
            try {
                val directory = Path.of(entry.directory)
                val head = GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim()
                val branch = GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim()
                if (head != entry.head || branch != (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")) {
                    throw IOException("Worktree HEAD changed during inspection; click Refresh.")
                }
                val dirty = dirtySummary(GitWorktrees.git(directory, "status", "--porcelain=v1", "-z", "--untracked-files=normal"))
                val refs = GitWorktrees.git(directory, "for-each-ref", "--format=%(refname)%00%(upstream)%00%(upstream:remotename)")
                    .lineSequence().filter { it.isNotEmpty() }.map { it.split('\u0000') }.toList()
                val local = refs.singleOrNull { it[0] == "refs/heads/${entry.branch}" }
                val upstream = local?.getOrNull(1)?.takeIf { it.isNotEmpty() }
                val remoteName = local?.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "." }
                val published = entry.branch != null && refs.any {
                    it[0].startsWith("refs/remotes/") && it[0].substringAfter("refs/remotes/").substringAfter('/') == entry.branch
                }
                val upstreamExists = upstream != null && refs.any { it[0] == upstream }
                val counts = if (upstreamExists) {
                    GitWorktrees.git(directory, "rev-list", "--left-right", "--count", "$head...$upstream", "--")
                        .trim().split(Regex("\\s+")).map { it.toInt() }.also { require(it.size == 2) }
                } else null
                val sync = syncSummary(entry.detached, upstream, upstreamExists, published, counts?.get(0), counts?.get(1))
                val repository = repository(project, repositoryDirectory)
                val review = when {
                    entry.detached || entry.branch == null -> WorktreeReviewStatus("—", "Detached HEAD has no branch review.")
                    repository == null -> WorktreeReviewStatus("Unavailable", "Open the repository as an IDE Git root, then Refresh.")
                    else -> project.getService(WorktreeReviewStatusProvider::class.java)?.read(repository, remoteName, entry.branch)
                        ?: WorktreeReviewStatus("Unavailable", "Enable the GitHub plugin for pull request status. Git operations remain available.")
                }
                val coverage = review.mergeProof?.let {
                    mergeCoverage(directory, head, it, dirty == "Clean" && entry.locked == null && entry.prunable == null)
                } ?: (false to "")
                // A second snapshot prevents a slow provider request from blessing a newer local HEAD.
                val unchanged = GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim() == head &&
                    GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim() == branch
                val locked = entry.locked?.let { "Locked${if (it.isEmpty()) "" else ": $it"}. " }.orEmpty()
                val label = listOfNotNull(if (entry.locked != null) "Locked" else null, sync.first, dirty.takeIf { it != "Clean" }).joinToString(" · ")
                WorktreeDisplayStatus(
                    label, locked + sync.second + " Working tree: $dirty. Remote counts use local refs; click Refresh to fetch.",
                    review.label, listOf(review.details, coverage.second).filter { it.isNotEmpty() }.joinToString(" "), review.url,
                    coverage.first && unchanged,
                    if (!unchanged) "Worktree HEAD changed during inspection; click Refresh." else review.error,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "Cannot inspect worktree: ${error.message}. Click Refresh after fixing Git or directory access."
                WorktreeDisplayStatus("Unknown", message, "Unknown", "Review coverage cannot be established.", error = message)
            }
        }

    suspend fun refresh(project: Project, repositoryDirectory: String) = withContext(Dispatchers.IO) {
        project.getService(WorktreeReviewStatusProvider::class.java)?.invalidate()
        val repository = repository(project, repositoryDirectory)
            ?: throw IOException("Open $repositoryDirectory as an IDE Git root before fetching.")
        // Native fetch owns credentials, SSH prompts, and cancellation; never invoke shell gh or git fetch.
        coroutineToIndicator {
            GitFetchSupport.fetchSupport(project).fetchAllRemotes(listOf(repository)).throwExceptionIfFailed()
        }
        repository.update()
    }

    private fun repository(project: Project, directory: String): GitRepository? {
        val canonical = Path.of(directory).toRealPath()
        return GitRepositoryManager.getInstance(project).repositories.singleOrNull {
            Path.of(it.root.path).toRealPath() == canonical
        }
    }

    internal suspend fun mergeCoverage(
        directory: Path, head: String, proof: WorktreeMergeProof, safeSnapshot: Boolean,
    ): Pair<Boolean, String> {
        if (!safeSnapshot) return false to "Cleanup is unavailable for a dirty, locked, or prunable worktree."
        if (head != proof.head) return false to "Current HEAD is not the merged PR source HEAD; later or different local commits are not covered."
        val target = "refs/remotes/${proof.remoteName}/${proof.baseBranch}"
        try {
            require(proof.baseRepository.isNotBlank() && proof.remoteUrl.isNotBlank()) { "Missing merge target repository identity" }
            require(proof.head.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) &&
                proof.mergeCommit.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) { "Invalid merge proof commit ID" }
            GitWorktrees.git(directory, "check-ref-format", target)
            val urls = GitWorktrees.git(directory, "config", "--null", "--get-all", "remote.${proof.remoteName}.url")
                .split('\u0000').filter { it.isNotEmpty() }
            if (urls != listOf(proof.remoteUrl)) return false to "Merge target remote changed; check the GitHub remote and Refresh."
            val targetHead = GitWorktrees.git(directory, "rev-parse", "--verify", "--end-of-options", "$target^{commit}").trim()
            val ancestor = GitWorktrees.git(directory, "merge-base", proof.mergeCommit, targetHead).trim()
            if (ancestor != proof.mergeCommit) return false to "Merged commit is not retained in $target; Refresh and inspect the target branch before cleanup."
            return true to "Exact source HEAD is merged, and merge commit ${proof.mergeCommit} is retained in $target."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            return false to "Merge target history is unavailable: ${error.message}. Refresh to fetch $target; cleanup remains unavailable."
        }
    }

    internal fun syncSummary(
        detached: Boolean, upstream: String?, upstreamExists: Boolean, published: Boolean, ahead: Int?, behind: Int?,
    ): Pair<String, String> = when {
        detached -> "Detached" to "HEAD is detached; no upstream comparison is available."
        upstream == null && published -> "No upstream" to "A remote branch with this name exists, but this local branch has no configured upstream."
        upstream == null -> "Unpublished" to "No upstream or same-name remote-tracking branch is known locally. Refresh to check remote publication."
        !upstreamExists -> "Upstream gone" to "Configured upstream $upstream is absent locally. Refresh or choose a new upstream."
        ahead == null || behind == null -> "Unknown" to "Upstream commit counts could not be read."
        ahead > 0 && behind > 0 -> "Diverged ↑$ahead ↓$behind" to "$ahead local and $behind upstream-only commits relative to $upstream."
        ahead > 0 -> "Ahead ↑$ahead" to "$ahead local commits are not in $upstream."
        behind > 0 -> "Behind ↓$behind" to "$behind commits from $upstream are not in this worktree."
        else -> "In sync" to "HEAD matches $upstream."
    }

    internal fun dirtySummary(output: String): String {
        val records = output.split('\u0000')
        var tracked = 0
        var untracked = 0
        var index = 0
        while (index < records.size) {
            val record = records[index++]
            if (record.isEmpty()) continue
            require(record.length >= 3 && record[2] == ' ') { "Invalid Git status record" }
            if (record.startsWith("?? ")) untracked++ else {
                tracked++
                if (record[0] == 'R' || record[1] == 'R' || record[0] == 'C' || record[1] == 'C') index++
            }
        }
        return listOfNotNull(if (tracked > 0) "Dirty ($tracked)" else null, if (untracked > 0) "Untracked ($untracked)" else null)
            .joinToString(", ").ifEmpty { "Clean" }
    }
}
