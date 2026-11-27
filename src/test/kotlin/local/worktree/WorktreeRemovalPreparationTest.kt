package local.worktree

import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.VetoableProjectManagerListener
import com.intellij.testFramework.HeavyPlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class WorktreeRemovalPreparationTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testPreparationOpensGenuineMainAndClosesOnlySelectedProject() = runBlocking {
        withRepository { repository, linked, temporary ->
            val tools = WorktreeOperations()
            val selected = open(tools, linked)
            val other = temporary.resolve("other")
            GitWorktrees.git(repository, "worktree", "add", "-b", "other", other.toString())
            val preserved = open(tools, other)
            val main = tools.prepareRemovalProjects(linked.toString(), listOf(linked.toString()))
            assertEquals(repository.toString(), main.basePath)
            assertTrue(main.isOpen)
            assertFalse(main.isDisposed)
            assertTrue(selected.isDisposed)
            assertTrue(preserved.isOpen)
            assertTrue(project.isOpen)
            assertTrue(Files.isDirectory(linked))
            assertEquals("refs/heads/trunk", GitWorktrees.git(repository, "symbolic-ref", "HEAD").trim())
            assertSame(main, tools.prepareRemovalProjects(repository.toString(), listOf(linked.toString())))
        }
    }

    fun testMainAndForeignTargetsAreRejectedBeforeAnyProjectCloses() = runBlocking {
        withRepository { repository, linked, temporary ->
            val tools = WorktreeOperations()
            val selected = open(tools, linked)
            val foreign = Files.createDirectory(temporary.resolve("foreign"))
            GitWorktrees.git(foreign, "init", "-b", "main")
            for (invalid in listOf(repository, foreign)) {
                expectBlocked { tools.prepareRemovalProjects(repository.toString(), listOf(linked.toString(), invalid.toString())) }
                assertTrue(selected.isOpen)
                assertFalse(selected.isDisposed)
                assertFalse(ProjectManager.getInstance().openProjects.any { it.basePath == repository.toString() })
                assertTrue(Files.isDirectory(linked))
            }
        }
    }

    fun testOverlappingProjectIsNeverClosedAsAnExactWorktree() = runBlocking {
        withRepository { repository, linked, _ ->
            val tools = WorktreeOperations()
            val nested = open(tools, Files.createDirectory(linked.resolve("nested")))
            expectBlocked { tools.prepareRemovalProjects(repository.toString(), listOf(linked.toString())) }
            assertTrue(nested.isOpen)
            assertFalse(nested.isDisposed)
            assertFalse(ProjectManager.getInstance().openProjects.any { it.basePath == repository.toString() })
            assertTrue(Files.isDirectory(linked))
        }
    }

    fun testVetoStopsRemainingClosuresAndRetainsLiveMainForErrorDialog() = runBlocking {
        withRepository { repository, linked, temporary ->
            val tools = WorktreeOperations()
            val selected = open(tools, linked)
            val other = temporary.resolve("other")
            GitWorktrees.git(repository, "worktree", "add", "-b", "other", other.toString())
            val preserved = open(tools, other)
            val last = temporary.resolve("last")
            GitWorktrees.git(repository, "worktree", "add", "-b", "last", last.toString())
            val untouched = open(tools, last)
            val manager = ProjectManager.getInstance()
            var mainWasOpenAtClose = false
            val veto = object : VetoableProjectManagerListener {
                override fun canClose(candidate: Project): Boolean {
                    if (candidate !== preserved) return true
                    mainWasOpenAtClose = manager.openProjects.any { it.basePath == repository.toString() && !it.isDisposed }
                    return false
                }
            }
            withContext(Dispatchers.EDT) { manager.addProjectManagerListener(veto) }
            try {
                try {
                    tools.prepareRemovalProjects(repository.toString(), listOf(linked.toString(), other.toString(), last.toString()))
                    fail("A close veto must stop removal preparation")
                } catch (error: RemovalPreparationException) {
                    assertEquals(repository.toString(), error.project.basePath)
                    assertTrue(error.project.isOpen)
                    assertFalse(error.project.isDisposed)
                }
                assertTrue(mainWasOpenAtClose)
                assertTrue(selected.isDisposed)
                assertTrue(preserved.isOpen)
                assertTrue(untouched.isOpen)
                assertFalse(WorktreeOperations.lifecycleBusy)
                assertTrue(Files.isDirectory(linked))
                assertTrue(Files.isDirectory(other))
                assertTrue(Files.isDirectory(last))
            } finally {
                withContext(Dispatchers.EDT) { manager.removeProjectManagerListener(veto) }
            }
        }
    }

    fun testCandidateClosureProjectionNeverAuthorizesPublicRemovalOfOpenProject() = runBlocking {
        withRepository { repository, linked, _ ->
            val tools = WorktreeOperations()
            val selected = open(tools, linked)
            Files.writeString(linked.resolve("local.txt"), "keep me")
            val entry = GitWorktrees.discover(repository.toString()).single { it.directory == linked.toString() }
            val row = WorktreeRemovalCandidate.fromSnapshot(entry, WorktreeStatusReader.readLocal(entry).status, open = true)
            val individual = row.plan(cleanup = false).toJson()
            val afterClose = row.plan(cleanup = false, closeProjects = true).toJson()
            assertFalse(individual["selectable"].asBoolean)
            assertTrue(individual.getAsJsonArray("blockedCodes").any { it.asString == "OPEN_PROJECT" })
            assertTrue(afterClose["selectable"].asBoolean)
            assertTrue(afterClose["open"].asBoolean)
            assertFalse(afterClose["safe"].asBoolean)
            assertFalse("Advisory choices must not carry deletion approval", afterClose.has("confirmation"))
            assertFalse(afterClose.getAsJsonArray("blockedCodes").any { it.asString == "OPEN_PROJECT" })
            val directories = listOf(linked.toString())
            val preview = result(tools.worktree_remove_preview(repository.toString(), directories, deleteLocalChanges = directories))
            val removal = result(tools.worktree_remove(repository.toString(), directories,
                listOf("snapshot-is-not-approval"), deleteLocalChanges = directories))
            for (blocked in listOf(preview, removal)) {
                assertEquals("blocked", blocked["status"].asString)
                assertFalse(blocked["selectable"].asBoolean)
                assertTrue(blocked.getAsJsonArray("blockedCodes").any { it.asString == "OPEN_PROJECT" })
                assertFalse(blocked.has("confirmation"))
            }
            assertTrue(selected.isOpen)
            assertEquals("keep me", Files.readString(linked.resolve("local.txt")))
        }
    }

    private fun result(json: JsonObject): JsonObject =
        json.getAsJsonArray("results").single().asJsonObject

    private suspend fun open(tools: WorktreeOperations, directory: Path): Project {
        val result = tools.worktree_open(directory.toString())
        assertTrue(result.toString(), result["status"].asString in setOf("opened", "reused"))
        return ProjectManager.getInstance().openProjects.single { it.basePath == directory.toString() }
    }

    private suspend fun expectBlocked(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected invalid project closure to be rejected")
        } catch (_: IllegalArgumentException) {
            // Exact-project validation must run before any lifecycle effect.
        }
    }

    private suspend fun withRepository(action: suspend (Path, Path, Path) -> Unit) {
        val temporary = Files.createTempDirectory("worktree-removal-preparation-").toRealPath()
        try {
            val repository = Files.createDirectory(temporary.resolve("repository"))
            val linked = temporary.resolve("linked")
            GitWorktrees.git(repository, "init", "-b", "trunk")
            GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--allow-empty", "-m", "initial")
            GitWorktrees.git(repository, "worktree", "add", "-b", "feature", linked.toString())
            action(repository, linked, temporary)
        } finally {
            withContext(Dispatchers.EDT) {
                val manager = ProjectManager.getInstance()
                manager.openProjects.filter { it.basePath?.let { base -> Path.of(base).startsWith(temporary) } == true }.forEach {
                    assertTrue(writeIntentReadAction { manager.closeAndDispose(it) })
                }
            }
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }
}
