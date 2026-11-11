package local.worktree

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import git4idea.config.GitExecutableManager
import git4idea.config.GitVcsApplicationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class GitWorktreesTest : HeavyPlatformTestCase() {
    private lateinit var temporary: Path

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        temporary = Files.createTempDirectory("git-worktrees-").toRealPath()
    }

    override fun tearDown() {
        try {
            FileUtil.delete(temporary.toFile())
        } finally {
            super.tearDown()
        }
    }

    fun testDiscoversLinkedDetachedLockedAndMissingWorktreesFromExplicitRepository() = runBlocking {
        val repository = initializeRepository("main repository\nwith spaces")
        val linked = temporary.resolve("linked worktree\nwith spaces")
        val detached = temporary.resolve("detached worktree")
        val missing = temporary.resolve("missing worktree\nwith spaces")
        val reason = "held for\nmanual \"review\"\rand spaces"
        git(repository, "worktree", "add", "-b", "feature/topic", linked.toString())
        git(repository, "worktree", "add", "--detach", detached.toString())
        git(repository, "worktree", "add", "-b", "abandoned", missing.toString())
        git(repository, "worktree", "lock", "--reason", reason, linked.toString())
        withContext(Dispatchers.IO) { check(FileUtil.delete(missing.toFile())) }

        val inventory = GitWorktrees.discover(linked.toString())
        assertEquals(4, inventory.size)
        val byDirectory = inventory.associateBy { it.directory }
        val head = git(repository, "rev-parse", "HEAD").trim()
        val main = byDirectory.getValue(repository.toString())
        assertEquals(head, main.head)
        assertEquals("main", main.branch)
        assertTrue(main.main)
        assertFalse(main.bare)
        assertFalse(main.detached)
        assertNull(main.locked)
        assertNull(main.prunable)
        assertEquals(main, inventory.first())

        val feature = byDirectory.getValue(linked.toRealPath().toString())
        assertEquals(head, feature.head)
        assertEquals("feature/topic", feature.branch)
        assertEquals(reason, feature.locked)
        assertFalse(feature.main)
        assertFalse(feature.detached)
        assertNull(feature.prunable)

        val detachedWorktree = byDirectory.getValue(detached.toRealPath().toString())
        assertEquals(head, detachedWorktree.head)
        assertNull(detachedWorktree.branch)
        assertTrue(detachedWorktree.detached)
        assertFalse(detachedWorktree.main)
        assertNull(detachedWorktree.locked)

        val prunable = byDirectory.getValue(missing.toAbsolutePath().normalize().toString())
        assertEquals("abandoned", prunable.branch)
        assertEquals(head, prunable.head)
        assertFalse(prunable.main)
        assertNull(prunable.locked)
        assertFalse(prunable.prunable.isNullOrBlank())

        git(repository, "worktree", "unlock", linked.toString())
        git(repository, "worktree", "lock", linked.toString())
        val reasonlessLock = GitWorktrees.discover(repository.toString()).single { it.directory == feature.directory }
        assertEquals("", reasonlessLock.locked)
    }

    fun testCanonicalizesExplicitRepositoryAlias() = runBlocking {
        val repository = initializeRepository("repository")
        val alias = withContext(Dispatchers.IO) {
            Files.createSymbolicLink(temporary.resolve("repository alias"), repository)
        }
        val inventory = GitWorktrees.discover(alias.resolve(".").toString())
        assertEquals(1, inventory.size)
        assertEquals(repository.toRealPath().toString(), inventory.single().directory)
    }

    fun testBareRepositoryRemainsIdentifiableAndProtectedAsMain() = runBlocking {
        val repository = withContext(Dispatchers.IO) { Files.createDirectory(temporary.resolve("bare.git")) }
        git(repository, "init", "--bare")
        val inventory = GitWorktrees.discover(repository.toString())
        assertEquals(1, inventory.size)
        val bare = inventory.single()
        assertEquals(repository.toRealPath().toString(), bare.directory)
        assertTrue(bare.main)
        assertTrue(bare.bare)
        assertFalse(bare.detached)
        assertNull(bare.head)
        assertNull(bare.branch)
        assertNull(bare.locked)
        assertNull(bare.prunable)
    }

    fun testRejectsInvalidPathsAndReportsNonGitFailure() = runBlocking {
        val ordinary = withContext(Dispatchers.IO) { Files.createDirectory(temporary.resolve("not a repository")) }
        val file = withContext(Dispatchers.IO) { Files.createFile(temporary.resolve("not a directory")) }
        for (directory in listOf("", ".", temporary.resolve("missing").toString(), file.toString())) {
            assertFailsWith<IllegalArgumentException>(directory) { GitWorktrees.discover(directory) }
        }
        assertFailsWith<IOException> { GitWorktrees.discover(ordinary.toString()) }
    }

    fun testUnavailableConfiguredGitIsNotAnEmptyInventory() = runBlocking {
        val repository = initializeRepository("repository")
        val settings = GitVcsApplicationSettings.getInstance()
        val previous = settings.savedPathToGit
        val manager = GitExecutableManager.getInstance()
        try {
            settings.setPathToGit(temporary.resolve("missing-git-executable").toString())
            manager.dropExecutableCache()
            assertFailsWith<IOException> { GitWorktrees.discover(repository.toString()) }
        } finally {
            settings.setPathToGit(previous)
            manager.dropExecutableCache()
        }
    }

    private suspend fun initializeRepository(name: String): Path {
        val repository = withContext(Dispatchers.IO) { Files.createDirectory(temporary.resolve(name)) }
        git(repository, "init", "--initial-branch=main")
        git(repository, "-c", "user.name=Worktree Test", "-c", "user.email=worktree@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "initial")
        return repository.toRealPath()
    }

    private suspend fun git(repository: Path, vararg arguments: String): String = withContext(Dispatchers.IO) {
        val command = GeneralCommandLine(GitExecutableManager.getInstance().pathToGit)
            .withWorkingDirectory(repository)
            .withCharset(Charsets.UTF_8)
            .withParameters("-c", "core.hooksPath=${temporary.resolve("no-hooks")}")
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
