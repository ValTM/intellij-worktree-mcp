package local.worktree

import com.intellij.icons.AllIcons
import junit.framework.TestCase
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubJsonException
import org.jetbrains.plugins.github.exceptions.GithubRateLimitExceededException
import org.jetbrains.plugins.github.exceptions.GithubStatusCodeException
import java.io.IOException

class GithubWorktreeStatusProviderTest : TestCase() {
    fun testProjectDefaultWinsWithoutGuessingAmongMatchingAccounts() {
        val default = GithubAccount("default", GithubServerPath.DEFAULT_SERVER, "default-id")
        val other = GithubAccount("other", GithubServerPath.DEFAULT_SERVER, "other-id")
        val foreign = GithubAccount("enterprise", GithubServerPath("github.example.com"), "foreign-id")
        val matching = listOf(default, other)
        assertSame(default, githubReviewAccount(matching, default))
        assertNull(githubReviewAccount(matching, null))
        assertNull(githubReviewAccount(matching, foreign))
        assertSame(other, githubReviewAccount(listOf(other), foreign))
        assertNull(githubReviewAccount(emptyList(), default))
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
