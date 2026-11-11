package local.worktree

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.api.data.GithubIssueState
import org.jetbrains.plugins.github.api.data.request.GithubRequestPagination
import org.jetbrains.plugins.github.authentication.GithubAuthenticationManager
import org.jetbrains.plugins.github.authentication.accounts.GithubProjectDefaultAccountHolder
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import org.jetbrains.plugins.github.util.GithubUrlUtil
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Loaded only by worktree-github.xml, so disabling GitHub never disables the Git tab.
internal class GithubWorktreeStatusProvider(private val project: Project) : WorktreeReviewStatusProvider {
    private val cache = ConcurrentHashMap<String, WorktreeReviewStatus>()
    private val generation = AtomicLong()

    override fun invalidate() {
        generation.incrementAndGet()
        cache.clear()
    }

    override suspend fun read(repository: GitRepository, remoteName: String?, branch: String): WorktreeReviewStatus {
        try {
            val remotes = repository.remotes
            val remote = if (remoteName != null) remotes.singleOrNull { it.name == remoteName }
            else remotes.singleOrNull { it.name == "origin" } ?: remotes.singleOrNull()
            if (remote == null) return WorktreeReviewStatus("Unavailable", "Choose an upstream remote (or an unambiguous origin) and Refresh to locate the review.")
            val accounts = GithubAuthenticationManager.getInstance().getAccounts()
            val mapping = project.service<GHHostedRepositoriesManager>().knownRepositoriesState.value.singleOrNull {
                it.gitRepository == repository && it.gitRemote == remote
            }
            val coordinates = mapping?.repository ?: run {
                val url = remote.urls.singleOrNull()
                    ?: return WorktreeReviewStatus("Unavailable", "Remote ${remote.name} has multiple URLs; select an unambiguous GitHub remote.")
                val host = GithubUrlUtil.getHostFromUrl(url)
                val server = accounts.map { it.server }.distinct().singleOrNull { it.host.equals(host, true) }
                    ?: GithubServerPath.DEFAULT_SERVER.takeIf { it.host.equals(host, true) }
                    ?: return WorktreeReviewStatus("Unavailable", "Remote ${remote.name} is not a recognized GitHub server. Git-only operations remain available.")
                val path = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(url)
                    ?: throw IOException("Cannot resolve the repository URL for ${remote.name}")
                GHRepositoryCoordinates(server, path)
            }
            val matching = accounts.filter { it.server == coordinates.serverPath }
            val account = project.service<GithubProjectDefaultAccountHolder>().account?.takeIf { it in matching }
                ?: matching.singleOrNull()
                ?: throw IOException(if (matching.isEmpty()) "Log in to ${coordinates.serverPath.host} in Settings > Version Control > GitHub, then Refresh"
                    else "Select the project default GitHub account in Settings > Version Control > GitHub, then Refresh; multiple accounts match ${coordinates.serverPath.host}")
            val key = "${account.id}\u0000$coordinates\u0000${remote.name}\u0000${remote.urls}\u0000$branch"
            cache[key]?.let { return it }
            val loadedGeneration = generation.get()
            val result = withTimeout(30_000) {
                val executor = GithubApiRequestExecutor.Factory.getInstance().create(account)
                val headFilter = "${coordinates.repositoryPath.owner}:$branch"
                // Native REST pagination, not GitHub search: exact head filtering avoids search indexing lag.
                suspend fun numbers(state: GithubIssueState): List<Long> {
                    val numbers = mutableListOf<Long>()
                    var page = 1
                    do {
                        val response = executor.execute(GithubApiRequests.Repos.PullRequests.find(
                            coordinates, state, null, headFilter, GithubRequestPagination(page++, 100),
                        ))
                        numbers.addAll(response.items.map { it.number })
                    } while (response.hasNext)
                    return numbers
                }
                val open = numbers(GithubIssueState.open)
                val closed = numbers(GithubIssueState.closed)
                val candidates = (open + closed).distinct().sortedDescending()
                if (candidates.isEmpty()) WorktreeReviewStatus("No PR", "No pull request for $headFilter in $coordinates. Click Refresh to query GitHub again.")
                else {
                    val path = coordinates.repositoryPath
                    val reviews = candidates.map { number ->
                        val url = "${coordinates.serverPath.toApiUrl().trimEnd('/')}/repos/${path.owner}/${path.repository}/pulls/$number"
                        val data = executor.execute(GithubApiRequest.Get.JsonMap<String, Any?>(url))
                        val head = data["head"] as? Map<*, *> ?: throw IOException("GitHub response has no PR head")
                        val headRepository = head["repo"] as? Map<*, *>
                        if (head["ref"] != branch || (headRepository != null &&
                            (headRepository["full_name"] as? String)?.equals("${path.owner}/${path.repository}", true) != true)) {
                            throw IOException("GitHub returned a review for a different source branch or repository")
                        }
                        val state = data["state"] as? String ?: throw IOException("GitHub response has no PR state")
                        val merged = data["merged"] as? Boolean ?: throw IOException("GitHub response has no merge state")
                        val draft = data["draft"] as? Boolean ?: throw IOException("GitHub response has no draft state")
                        val base = data["base"] as? Map<*, *>
                        val baseRepository = (base?.get("repo") as? Map<*, *>)?.get("full_name") as? String
                        val baseBranch = (base?.get("ref") as? String)?.takeIf { it.isNotBlank() }
                        val sourceHead = (head["sha"] as? String)?.takeIf { headRepository != null }
                        val mergeCommit = (data["merge_commit_sha"] as? String)?.takeIf { data["merged_at"] is String }
                        val remoteUrl = remote.urls.singleOrNull()
                        val sameRepository = baseRepository != null && baseRepository.equals("${path.owner}/${path.repository}", true) &&
                            baseBranch != null && sourceHead != null && remoteUrl != null
                        val proof = if (sameRepository && merged && mergeCommit != null) {
                            WorktreeMergeProof(sourceHead!!, mergeCommit, baseRepository!!, baseBranch!!, remote.name, remoteUrl!!)
                        } else null
                        val reviewUrl = data["html_url"] as? String ?: throw IOException("GitHub response has no PR URL")
                        reviewSummary(
                            number, state, draft, merged,
                            data["title"] as? String ?: throw IOException("GitHub response has no PR title"), reviewUrl, proof,
                        ).copy(closedProofs = if (sameRepository && state == "closed" && !merged && data["closed_at"] is String) {
                            listOf(WorktreeClosedProof(sourceHead!!, baseRepository!!, baseBranch!!, remote.name, remoteUrl!!, number, reviewUrl))
                        } else emptyList())
                    }
                    val selected = (open.maxOrNull() ?: closed.maxOrNull())!!
                    reviews[candidates.indexOf(selected)].copy(
                        mergeProofs = reviews.flatMap { it.mergeProofs },
                        closedProofs = reviews.flatMap { it.closedProofs },
                    )
                }
            }
            if (generation.get() == loadedGeneration) cache[key] = result
            return result
        } catch (timeout: TimeoutCancellationException) {
            return failure("GitHub request timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(reason: String): WorktreeReviewStatus {
        val message = "GitHub status unavailable: $reason. Check network and Settings > Version Control > GitHub login, then click Refresh."
        return WorktreeReviewStatus("Unknown", message, error = message)
    }
}
