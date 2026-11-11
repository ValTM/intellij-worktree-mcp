package local.worktree

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import git4idea.config.GitExecutableManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class GitWorktreeCreationTest : HeavyPlatformTestCase() {
    private lateinit var temporary: Path

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("git-worktree-creation-").toRealPath()
    }

    override fun tearDown() {
        try {
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testNewBranchPreviewIsReadOnlyAndCreationSkipsHooksAndInstallation() = runBlocking {
        val repository = initializeRepository("repository with spaces")
        val alias = Files.createSymbolicLink(temporary.resolve("parent alias"), temporary)
        val destination = alias.resolve("new parent/worktree with spaces\nand newline")
        val canonicalDestination = temporary.resolve("new parent/worktree with spaces\nand newline")
        val hookMarker = temporary.resolve("hook-ran")
        val installMarker = temporary.resolve("install-ran")
        val hooks = Files.createDirectory(temporary.resolve("configured hooks"))
        val hook = Files.writeString(hooks.resolve("post-checkout"), "#!/bin/sh\ntouch '${hookMarker}'\nexit 1\n")
        assertTrue(hook.toFile().setExecutable(true))
        git(repository, "config", "core.hooksPath", hooks.toString())
        Files.writeString(repository.resolve("package.json"), """{"scripts":{"postinstall":"touch '${installMarker}'"}}""")
        git(repository, "add", "package.json")
        commit(repository, "package with installation hook")
        val head = git(repository, "rev-parse", "HEAD").trim()
        commit(repository, "main advances beyond the requested start")
        val mainHead = git(repository, "rev-parse", "HEAD").trim()

        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "feature/new", head, true)
        assertEquals(repository.toString(), plan.repositoryDirectory)
        assertEquals(canonicalDestination.toString(), plan.directory)
        assertEquals(head, plan.head)
        assertFalse(Files.exists(canonicalDestination.parent))
        assertEquals("", git(repository, "for-each-ref", "refs/heads/feature/new"))
        assertEquals(canonicalDestination.toString(), GitWorktrees.create(plan, plan.confirmation))
        assertEquals(head, git(canonicalDestination, "rev-parse", "HEAD").trim())
        assertEquals("refs/heads/feature/new", git(canonicalDestination, "symbolic-ref", "HEAD").trim())
        assertEquals("refs/heads/main", git(repository, "symbolic-ref", "HEAD").trim())
        assertEquals(mainHead, git(repository, "rev-parse", "HEAD").trim())
        assertTrue(Files.exists(canonicalDestination.resolve("package.json")))
        assertFalse(Files.exists(hookMarker))
        assertFalse(Files.exists(installMarker))
        assertFalse(Files.exists(canonicalDestination.resolve("node_modules")))
    }

    fun testExistingBranchRequiresItsTipAndRemainsAttachedWithoutResetting() = runBlocking {
        val repository = initializeRepository("repository")
        val original = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "branch", "existing")
        commit(repository, "new main commit")
        val destination = temporary.resolve("existing checkout")
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.previewCreate(repository.toString(), destination.toString(), "existing", "HEAD")
        }
        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "existing", "refs/heads/existing")
        assertEquals(original, plan.head)
        GitWorktrees.create(plan, plan.confirmation)
        assertEquals(original, git(repository, "rev-parse", "refs/heads/existing").trim())
        assertEquals("refs/heads/existing", git(destination, "symbolic-ref", "HEAD").trim())
        assertEquals(original, git(destination, "rev-parse", "HEAD").trim())
    }

    fun testPreviewRejectsExistingPathsInvalidRefsAndUnavailableBranches() = runBlocking {
        val repository = initializeRepository("repository")
        val empty = Files.createDirectory(temporary.resolve("empty"))
        val file = Files.createFile(temporary.resolve("file"))
        val dangling = Files.createSymbolicLink(temporary.resolve("dangling"), temporary.resolve("missing"))
        for (destination in listOf(empty.toString(), file.toString(), dangling.toString(), "relative")) {
            assertFailsWith<IllegalArgumentException> {
                GitWorktrees.previewCreate(repository.toString(), destination, "new", "HEAD", true)
            }
        }
        val destination = temporary.resolve("destination").toString()
        assertFailsWith<IOException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "invalid branch", "HEAD", true)
        }
        assertFailsWith<IOException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "new", "missing-ref", true)
        }
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "main", "HEAD", true)
        }
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "main", "HEAD")
        }
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "missing", "HEAD")
        }
        git(repository, "branch", "linked")
        git(repository, "worktree", "add", temporary.resolve("linked").toString(), "linked")
        assertFailsWith<IllegalArgumentException> {
            GitWorktrees.previewCreate(repository.toString(), destination, "linked", "refs/heads/linked")
        }
        assertFalse(Files.exists(Path.of(destination)))
        assertTrue(Files.isDirectory(empty))
        assertTrue(Files.isSymbolicLink(dangling))
    }

    fun testApplyRejectsMismatchedPlansAndChangedStartingCommit() = runBlocking {
        val repository = initializeRepository("repository")
        val destination = temporary.resolve("destination")
        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "new", "HEAD", true)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(plan, "wrong confirmation") }
        val other = GitWorktrees.previewCreate(repository.toString(), temporary.resolve("other").toString(), "new", "HEAD", true)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(other, plan.confirmation) }
        commit(repository, "starting ref moved")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(plan, plan.confirmation) }
        val refreshed = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "new", "HEAD", true)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(refreshed, plan.confirmation) }
        assertFalse(Files.exists(destination))
        assertEquals("", git(repository, "for-each-ref", "refs/heads/new"))
    }

    fun testApplyRevalidatesNewBranchExistingBranchAndDestination() = runBlocking {
        val repository = initializeRepository("repository")
        val newDestination = temporary.resolve("new destination")
        val newPlan = GitWorktrees.previewCreate(repository.toString(), newDestination.toString(), "new", "HEAD", true)
        git(repository, "branch", "new")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(newPlan, newPlan.confirmation) }
        assertFalse(Files.exists(newDestination))

        val destination = temporary.resolve("existing destination")
        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "new", "refs/heads/new")
        Files.createDirectory(destination)
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(plan, plan.confirmation) }
        assertEquals(0L, Files.list(destination).use { it.count() })

        val otherDestination = temporary.resolve("other destination")
        val otherPlan = GitWorktrees.previewCreate(repository.toString(), otherDestination.toString(), "new", "refs/heads/new")
        git(repository, "worktree", "add", temporary.resolve("competing worktree").toString(), "new")
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(otherPlan, otherPlan.confirmation) }
        assertFalse(Files.exists(otherDestination))
    }

    fun testApplyRevalidatesRepositoryCommonDirectoryIdentity() = runBlocking {
        val repository = initializeRepository("repository")
        val clone = temporary.resolve("clone")
        git(temporary, "clone", "--no-hardlinks", repository.toString(), clone.toString())
        val destination = temporary.resolve("destination")
        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "new", "HEAD", true)
        Files.move(repository.resolve(".git"), repository.resolve("original.git"))
        Files.createSymbolicLink(repository.resolve(".git"), clone.resolve(".git"))
        assertFailsWith<IllegalArgumentException> { GitWorktrees.create(plan, plan.confirmation) }
        assertFalse(Files.exists(destination))
    }

    fun testCheckoutFailureReportsRemainingStateWithoutPluginRollback() = runBlocking {
        val repository = initializeRepository("repository")
        Files.writeString(repository.resolve(".gitattributes"), "blocked filter=reject\n")
        Files.writeString(repository.resolve("blocked"), "tracked content\n")
        git(repository, "add", ".gitattributes", "blocked")
        commit(repository, "tracked checkout failure")
        git(repository, "config", "filter.reject.smudge", "false")
        git(repository, "config", "filter.reject.required", "true")
        val destination = temporary.resolve("partial destination")
        val plan = GitWorktrees.previewCreate(repository.toString(), destination.toString(), "partial", "HEAD", true)
        val error = assertFailsWith<GitWorktrees.CreationException> { GitWorktrees.create(plan, plan.confirmation) }
        assertEquals(destination.toString(), error.directory)
        assertEquals("partial", error.branch)
        assertEquals(Files.exists(destination), error.destinationExists)
        assertEquals(Files.exists(destination.resolve(".git")), error.worktreeMetadataExists)
        assertEquals(plan.head, git(repository, "rev-parse", "refs/heads/partial").trim())
    }

    private suspend fun initializeRepository(name: String): Path {
        val repository = Files.createDirectory(temporary.resolve(name))
        git(repository, "init", "--initial-branch=main")
        commit(repository, "initial")
        return repository.toRealPath()
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
