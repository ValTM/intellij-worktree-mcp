package local.worktree

import com.intellij.icons.AllIcons
import com.intellij.ui.RowIcon
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
import javax.swing.Icon

internal data class WorktreeDisplayStatus(
    val syncLabel: String,
    val syncDetails: String,
    val reviewLabel: String,
    val reviewDetails: String,
    val reviewUrl: String? = null,
    val mergedHeadCovered: Boolean = false,
    val error: String? = null,
    val reviewError: String? = null,
    val syncIcon: Icon? = null,
    val reviewIcon: Icon? = null,
    val reviewLoading: Boolean = false,
    val reviewCached: Boolean = false,
    val hasLocalChanges: Boolean = false,
    val localStatusKnown: Boolean = false,
    val cleanupKind: String = "BLOCKED",
    val identity: WorktreeIdentity? = null,
)

internal data class WorktreeLocalStatus(
    val entry: GitWorktree,
    val status: WorktreeDisplayStatus,
    val remoteName: String? = null,
    val clean: Boolean = false,
    val readable: Boolean = false,
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
    val mergeProofs: List<WorktreeMergeProof> = listOfNotNull(mergeProof),
    val closedProofs: List<WorktreeClosedProof> = emptyList(),
    val icon: Icon? = null,
)

internal data class WorktreeMergeProof(
    val head: String,
    val mergeCommit: String,
    val baseRepository: String,
    val baseBranch: String,
    val remoteName: String,
    val remoteUrl: String,
)

internal data class WorktreeClosedProof(
    val head: String,
    val baseRepository: String,
    val baseBranch: String,
    val remoteName: String,
    val remoteUrl: String,
    val number: Long,
    val url: String,
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
        icon = when {
            merged -> AllIcons.Vcs.Merge
            state == "closed" -> AllIcons.Actions.Close
            draft -> AllIcons.Actions.Edit
            else -> AllIcons.Vcs.Branch
        },
    )
}


internal object WorktreeStatusReader {
    suspend fun read(project: Project, repositoryDirectory: String, entry: GitWorktree): WorktreeDisplayStatus =
        readReview(project, repositoryDirectory, readLocal(entry))

    suspend fun readLocal(entry: GitWorktree): WorktreeLocalStatus = withContext(Dispatchers.IO) {
        fun snapshot(status: WorktreeDisplayStatus) = WorktreeLocalStatus(entry, status.copy(identity = WorktreeIdentity(entry)))
        if (entry.bare) return@withContext snapshot(WorktreeDisplayStatus("Bare", "Bare repository has no working tree.", "—", "No checked-out branch.", syncIcon = AllIcons.Nodes.Folder, reviewIcon = AllIcons.General.Remove))
        if (!Files.isDirectory(Path.of(entry.directory))) return@withContext snapshot(WorktreeDisplayStatus(
            "Missing", "Worktree directory is missing or inaccessible. ${entry.prunable.orEmpty()}", "Unknown", "Restore the directory and Refresh before checking its review.",
            syncIcon = AllIcons.General.Warning, reviewIcon = AllIcons.General.Warning,
        ))
        try {
            val directory = Path.of(entry.directory)
            val head = GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim()
            val branch = GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim()
            if (head != entry.head || branch != (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")) {
                throw IOException("Worktree HEAD changed during inspection; click Refresh.")
            }
            val porcelain = GitWorktrees.git(directory, "status", "--porcelain=v1", "-z", "--untracked-files=normal")
            val dirty = dirtySummary(porcelain)
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
            val detached = entry.detached || entry.branch == null
            val locked = entry.locked?.let { "Locked${if (it.isEmpty()) "" else ": $it"}. " }.orEmpty()
            val label = listOfNotNull(if (entry.locked != null) "Locked" else null, sync.first, dirty.takeIf { it != "Clean" }).joinToString(" · ")
            WorktreeLocalStatus(entry, WorktreeDisplayStatus(
                label, locked + sync.second + " Working tree: $dirty. Remote counts use local refs; click Refresh to fetch.",
                if (detached) "—" else "Loading…",
                if (detached) "Detached HEAD has no branch review." else "Loading pull request status.",
                syncIcon = sync.third,
                reviewIcon = if (detached) AllIcons.General.Remove else AllIcons.Actions.Refresh,
                reviewLoading = !detached,
                hasLocalChanges = porcelain.isNotEmpty(),
                localStatusKnown = true,
                identity = WorktreeIdentity(entry),
            ), remoteName, dirty == "Clean", readable = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "Cannot inspect worktree: ${error.message}. Click Refresh after fixing Git or directory access."
            snapshot(WorktreeDisplayStatus("Unknown", message, "Unknown", "Review coverage cannot be established.", error = message,
                syncIcon = AllIcons.General.Warning, reviewIcon = AllIcons.General.Warning))
        }
    }

    suspend fun readReview(project: Project, repositoryDirectory: String, local: WorktreeLocalStatus): WorktreeDisplayStatus =
        withContext(Dispatchers.IO) {
            if (!local.readable) return@withContext local.status
            val entry = local.entry
            val directory = Path.of(entry.directory)
            try {
                val repository = repository(project, repositoryDirectory)
                val review = when {
                    entry.detached || entry.branch == null -> WorktreeReviewStatus("—", "Detached HEAD has no branch review.", icon = AllIcons.General.Remove)
                    repository == null -> WorktreeReviewStatus("Unavailable", "Open the repository as an IDE Git root, then Refresh.", icon = AllIcons.General.Warning)
                    else -> project.getService(WorktreeReviewStatusProvider::class.java)?.read(repository, local.remoteName, entry.branch!!)
                        ?: WorktreeReviewStatus("Unavailable", "Enable the GitHub plugin for pull request status. Git operations remain available.", icon = AllIcons.General.Warning)
                }
                // Advisory local proof only; destructive cleanup still checks the live default target.
                val coverage = if (entry.locked == null && entry.prunable == null) {
                    WorktreeCleanup.localEvidence(directory, entry.head!!, entry.branch, review)
                } else CleanupEvidence("BLOCKED", "", "")
                // Revalidate after the slow provider and coverage work; never bless a newer HEAD.
                val unchanged = GitWorktrees.git(directory, "rev-parse", "--verify", "HEAD^{commit}").trim() == entry.head &&
                    GitWorktrees.git(directory, "rev-parse", "--symbolic-full-name", "HEAD").trim() == (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")
                local.status.copy(
                    reviewLabel = review.label,
                    reviewDetails = listOf(review.details, coverage.details).filter { it.isNotEmpty() }.joinToString(" "),
                    reviewUrl = review.url,
                    mergedHeadCovered = coverage.kind == "MERGED" && unchanged && local.clean,
                    cleanupKind = if (unchanged) coverage.kind else "BLOCKED",
                    error = if (!unchanged) "Worktree HEAD changed during inspection; click Refresh." else null,
                    reviewError = review.error,
                    reviewIcon = review.icon,
                    reviewLoading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (error: Exception) {
                local.status.copy(
                    reviewLabel = "Unavailable", reviewDetails = "Pull request status could not be loaded.",
                    reviewError = error.message ?: "Review inspection failed",
                    reviewIcon = AllIcons.General.Warning, reviewLoading = false,
                )
            }
        }

    fun retainReview(status: WorktreeDisplayStatus, previous: WorktreeDisplayStatus?): WorktreeDisplayStatus {
        if (previous == null || status.identity == null || status.identity != previous.identity || status.error != null ||
            (previous.reviewLoading && !previous.reviewCached) ||
            (previous.reviewError != null && !previous.reviewCached && previous.cleanupKind == "BLOCKED")) return status
        return status.copy(
            reviewLabel = previous.reviewLabel, reviewDetails = previous.reviewDetails, reviewUrl = previous.reviewUrl,
            reviewCached = true, mergedHeadCovered = previous.cleanupKind == "MERGED" && !status.hasLocalChanges,
            cleanupKind = previous.cleanupKind,
        )
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

    internal fun repository(project: Project, directory: String): GitRepository? {
        val canonical = Path.of(directory).toRealPath()
        return GitRepositoryManager.getInstance(project).repositories.singleOrNull {
            Path.of(it.root.path).toRealPath() == canonical
        }
    }


    internal fun syncSummary(
        detached: Boolean, upstream: String?, upstreamExists: Boolean, published: Boolean, ahead: Int?, behind: Int?,
    ): Triple<String, String, Icon> = when {
        detached -> Triple("Detached", "HEAD is detached; no upstream comparison is available.", AllIcons.Vcs.CommitNode)
        upstream == null && published -> Triple("No upstream", "A remote branch with this name exists, but this local branch has no configured upstream.", AllIcons.Vcs.Branch)
        upstream == null -> Triple("Unpublished", "No upstream or same-name remote-tracking branch is known locally. Refresh to check remote publication.", AllIcons.Actions.Upload)
        !upstreamExists -> Triple("Upstream gone", "Configured upstream $upstream is absent locally. Refresh or choose a new upstream.", AllIcons.General.Warning)
        ahead == null || behind == null -> Triple("Unknown", "Upstream commit counts could not be read.", AllIcons.General.Warning)
        ahead > 0 && behind > 0 -> Triple("Diverged ↑$ahead ↓$behind", "$ahead local and $behind upstream-only commits relative to $upstream.", RowIcon(AllIcons.General.ArrowUp, AllIcons.General.ArrowDown))
        ahead > 0 -> Triple("Ahead ↑$ahead", "$ahead local commits are not in $upstream.", AllIcons.General.ArrowUp)
        behind > 0 -> Triple("Behind ↓$behind", "$behind commits from $upstream are not in this worktree.", AllIcons.General.ArrowDown)
        else -> Triple("In sync", "HEAD matches $upstream.", AllIcons.General.GreenCheckmark)
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
