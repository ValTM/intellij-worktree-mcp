package local.worktree

import com.intellij.icons.AllIcons
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import junit.framework.TestCase
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubJsonException
import org.jetbrains.plugins.github.exceptions.GithubRateLimitExceededException
import org.jetbrains.plugins.github.exceptions.GithubStatusCodeException
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import java.io.IOException
import java.lang.reflect.Proxy

class GithubWorktreeStatusProviderTest : TestCase() {
    fun testNativeConnectionMustMatchLocalRootRemoteAndHostedRepository() {
        fun repository(): GitRepository = Proxy.newProxyInstance(
            GitRepository::class.java.classLoader, arrayOf(GitRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected repository access: ${method.name}")
            }
        } as GitRepository
        val root = repository()
        val otherRoot = repository()
        val url = "https://github.com/owner/repo.git"
        fun remote(name: String) = GitRemote(name, listOf(url), emptyList(), emptyList(), emptyList())
        val origin = remote("origin")
        val upstream = remote("upstream")
        val coordinates = GHRepositoryCoordinates(GithubServerPath.DEFAULT_SERVER, GHRepositoryPath("owner", "repo"))
        val mapping = GHGitRepositoryMapping(coordinates, GitRemoteUrlCoordinates(url, origin, root))
        // Native mapping equality ignores local roots/remotes; it cannot establish identity for account reuse.
        assertEquals(mapping, GHGitRepositoryMapping(coordinates, GitRemoteUrlCoordinates(url, upstream, otherRoot)))
        assertTrue(mapping.matchesReviewRepository(root, origin, coordinates))
        assertFalse(mapping.matchesReviewRepository(otherRoot, origin, coordinates))
        assertFalse(mapping.matchesReviewRepository(root, upstream, coordinates))
        assertFalse(mapping.matchesReviewRepository(root, origin, GHRepositoryCoordinates(
            GithubServerPath.DEFAULT_SERVER, GHRepositoryPath("owner", "other"),
        )))
        assertFalse(mapping.matchesReviewRepository(root, origin, GHRepositoryCoordinates(
            GithubServerPath("github.example.com"), GHRepositoryPath("owner", "repo"),
        )))
    }

    fun testExactNativeConnectionAccountWinsWithoutChangingDefaultsOrGuessingAmongAccounts() {
        val default = GithubAccount("default", GithubServerPath.DEFAULT_SERVER, "default-id")
        val connected = GithubAccount("native", GithubServerPath.DEFAULT_SERVER, "native-id")
        val foreign = GithubAccount("enterprise", GithubServerPath("github.example.com"), "foreign-id")
        val matching = listOf(default, connected)
        assertSame(connected, githubReviewAccount(matching, default, connected))
        assertSame(default, githubReviewAccount(matching, default, null))
        assertSame(default, githubReviewAccount(matching, default, foreign))
        assertNull(githubReviewAccount(matching, null, null))
        assertSame(connected, githubReviewAccount(listOf(connected), foreign, null))
        assertNull(githubReviewAccount(emptyList(), default, connected))
    }

    fun testFailuresExposeCauseInsteadOfUnknownOrNoPullRequest() {
        val context = "github.com/owner/repo, branch feature, account developer"
        val failures = listOf(
            GithubAuthenticationException("Missing token") to "Login required",
            GithubRateLimitExceededException("API rate limit exceeded") to "Rate limited",
            GithubStatusCodeException("Unauthorized", 401) to "Login required",
            GithubStatusCodeException("Organization access required", 403) to "Access denied",
            GithubStatusCodeException("Not Found", 404) to "Repo unavailable",
            GithubStatusCodeException("Too Many Requests", 429) to "Rate limited",
            GithubStatusCodeException("Bad Gateway", 502) to "GitHub HTTP 502",
            GithubJsonException("Can't parse GitHub response") to "Response error",
            IOException("Connection refused") to "Request failed",
            IllegalStateException("SDK failure") to "Lookup failed",
        )
        for ((error, label) in failures) {
            val status = githubStatusFailure(error, context)
            assertEquals(label, status.label)
            assertSame(AllIcons.General.Warning, status.icon)
            assertTrue(status.details.contains(context))
            assertFalse(status.details.contains(error.message!!))
            assertEquals(status.details, status.error)
            assertTrue(status.mergeProofs.isEmpty())
            assertTrue(status.closedProofs.isEmpty())
            assertNull(status.url)
        }
    }

    fun testAccountAndTimeoutFailuresStayActionableWithoutClaimingAuthenticationFailed() {
        val status = githubStatusFailure("Choose account", "Select the project default GitHub account; multiple accounts match github.com")
        assertEquals("Choose account", status.label)
        assertTrue(status.details.contains("multiple accounts"))
        assertTrue(status.details.contains("Refresh"))
        assertEquals("Timed out", githubStatusFailure("Timed out", "GitHub request timed out").label)
        val missingMessage = githubStatusFailure(IOException(), "owner/repo")
        assertTrue(missingMessage.details.contains("IOException"))
        assertFalse(missingMessage.details.contains("login"))
        val secret = "https://secret-token@github.com/private?access_token=secret-token"
        assertFalse(githubStatusFailure(IOException(secret), "owner/repo").details.contains("secret-token"))
    }
}
