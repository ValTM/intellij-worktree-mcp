package local.worktree

import com.intellij.icons.AllIcons
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
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.authentication.accounts.GithubProjectDefaultAccountHolder
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubJsonException
import org.jetbrains.plugins.github.exceptions.GithubRateLimitExceededException
import org.jetbrains.plugins.github.exceptions.GithubStatusCodeException
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
        var context = "branch $branch"
        try {
            val remotes = repository.remotes
            val remote = if (remoteName != null) remotes.singleOrNull { it.name == remoteName }
            else remotes.singleOrNull { it.name == "origin" } ?: remotes.singleOrNull()
            if (remote == null) return WorktreeReviewStatus("Unavailable", "Choose an upstream remote (or an unambiguous origin) and Refresh to locate the review.", icon = AllIcons.General.Warning)
            val accounts = GithubAuthenticationManager.getInstance().getAccounts()
            val mapping = project.service<GHHostedRepositoriesManager>().knownRepositoriesState.value.singleOrNull {
                it.gitRepository == repository && it.gitRemote == remote
            }
            val coordinates = mapping?.repository ?: run {
                val url = remote.urls.singleOrNull()
                    ?: return WorktreeReviewStatus("Unavailable", "Remote ${remote.name} has multiple URLs; select an unambiguous GitHub remote.", icon = AllIcons.General.Warning)
                val host = GithubUrlUtil.getHostFromUrl(url)
                val server = accounts.map { it.server }.distinct().singleOrNull { it.host.equals(host, true) }
                    ?: GithubServerPath.DEFAULT_SERVER.takeIf { it.host.equals(host, true) }
                    ?: return WorktreeReviewStatus("Unavailable", "Remote ${remote.name} is not a recognized GitHub server. Git-only operations remain available.", icon = AllIcons.General.Warning)
                val path = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(url)
                    ?: throw IOException("Cannot resolve the repository URL for ${remote.name}")
                GHRepositoryCoordinates(server, path)
            }
            val matching = accounts.filter { it.server == coordinates.serverPath }
            val account = githubReviewAccount(matching, project.service<GithubProjectDefaultAccountHolder>().account)
                ?: return githubStatusFailure(
                    if (matching.isEmpty()) "Login required" else "Choose account",
                    if (matching.isEmpty()) "Log in to ${coordinates.serverPath.host} in Settings > Version Control > GitHub"
                    else "Set the project default GitHub account; multiple accounts match ${coordinates.serverPath.host}",
                )
            context = "$coordinates, branch $branch, account ${account.name}"
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
                if (candidates.isEmpty()) WorktreeReviewStatus("No PR", "No pull request for $headFilter in $coordinates. Click Refresh to query GitHub again.", icon = AllIcons.General.Remove)
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
            return githubStatusFailure("Timed out", "GitHub request timed out for $context")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            return githubStatusFailure(error, context)
        }
    }
}

internal fun githubReviewAccount(
    matching: List<GithubAccount>, projectDefault: GithubAccount?,
): GithubAccount? = projectDefault?.takeIf { it in matching }
    ?: matching.singleOrNull()

internal fun githubStatusFailure(label: String, reason: String): WorktreeReviewStatus {
    val message = "GitHub status unavailable: ${reason.trimEnd('.')}. Click Refresh to retry."
    return WorktreeReviewStatus(label, message, error = message, icon = AllIcons.General.Warning)
}

internal fun githubStatusFailure(error: Exception, context: String): WorktreeReviewStatus {
    val label = when (error) {
        is GithubAuthenticationException -> "Login required"
        is GithubRateLimitExceededException -> "Rate limited"
        is GithubStatusCodeException -> when (error.statusCode) {
            401 -> "Login required"
            403 -> "Access denied"
            404 -> "Repo unavailable"
            429 -> "Rate limited"
            else -> "GitHub HTTP ${error.statusCode}"
        }
        is GithubJsonException -> "Response error"
        is IOException -> "Request failed"
        else -> "Lookup failed"
    }
    // SDK exception text may include request URLs or credentials; expose only the failure category and type.
    val reason = if (error is GithubStatusCodeException) "GitHub returned HTTP ${error.statusCode}" else error.javaClass.simpleName
    val action = when (label) {
        "Login required" -> " Check Settings > Version Control > GitHub login."
        "Access denied", "Repo unavailable" -> " Check the repository URL and selected account's repository/organization access."
        else -> ""
    }
    return githubStatusFailure(label, "$context: $reason.$action")
}
