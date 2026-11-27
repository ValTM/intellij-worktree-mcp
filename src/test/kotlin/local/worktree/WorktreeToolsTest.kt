package local.worktree

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.VetoableProjectManagerListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.openapi.util.SystemInfoRt
import git4idea.GitWorkingTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class WorktreeToolsTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testOpenInitializesContentRootForFreshAndModulelessIdeaDirectories() = runBlocking {
        val temporary = Files.createTempDirectory("worktree-tools-open-").toRealPath()
        val manager = ProjectManager.getInstance()
        val tools = WorktreeTools()
        try {
            for (existingIdea in listOf(false, true)) {
                val directory = Files.createDirectory(temporary.resolve(if (existingIdea) "existing-idea" else "fresh"))
                if (existingIdea) Files.createDirectory(directory.resolve(".idea"))
                try {
                    val result = JsonParser.parseString(tools.worktree_open(directory.toString())).asJsonObject
                    assertEquals("opened", result["status"].asString)
                    val opened = manager.openProjects.single { it.basePath == directory.toString() }
                    readAction {
                        val modules = ModuleManager.getInstance(opened).modules
                        assertEquals(1, modules.size)
                        assertEquals(listOf(directory.toString()),
                            ModuleRootManager.getInstance(modules.single()).contentRoots.map { it.path })
                    }
                    val alias = Files.createSymbolicLink(temporary.resolve("${directory.fileName}-alias"), directory)
                    val reused = JsonParser.parseString(WorktreeTools().worktree_open(alias.toString())).asJsonObject
                    assertEquals("reused", reused["status"].asString)
                    assertEquals(directory.toString(), reused["directory"].asString)
                    assertFalse(reused.has("merge"))
                    assertSame(opened, manager.openProjects.single { it.basePath == directory.toString() })
                    assertTrue(project.isOpen)
                    val states = WorktreeOperations().windowStates(project)
                    assertEquals("current", WorktreeOperations().windowStates(opened)[directory.toString()])
                    assertEquals("other_window", states[directory.toString()])
                    assertFalse(WorktreeOperations.lifecycleBusy)
                } finally {
                    withContext(Dispatchers.EDT) {
                        manager.openProjects.filter { it.basePath == directory.toString() }.forEach {
                            assertTrue(writeIntentReadAction { manager.closeAndDispose(it) })
                        }
                    }
                }
            }
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }

    fun testMcpAndGuiOperationsShareInventoryCreationAndRemoval() = runBlocking {
        val temporary = Files.createTempDirectory("worktree-shared-operations-").toRealPath()
        try {
            val repository = Files.createDirectory(temporary.resolve("repository"))
            val linked = temporary.resolve("linked")
            GitWorktrees.git(repository, "init", "-b", "main")
            GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--allow-empty", "-m", "initial")
            val operations = WorktreeOperations()
            val tools = WorktreeTools()
            val preview = operations.worktree_create_preview(
                repository.toString(), linked.toString(), "feature", "main", newBranch = true,
            )
            assertEquals(preview, JsonParser.parseString(tools.worktree_create_preview(
                repository.toString(), linked.toString(), "feature", "main", newBranch = true,
            )).asJsonObject)
            val created = JsonParser.parseString(tools.worktree_create(
                repository.toString(), linked.toString(), "feature", "main",
                preview["confirmation"].asString, newBranch = true,
            )).asJsonObject
            assertEquals("created", created["status"].asString)
            assertEquals(operations.worktree_discover(repository.toString()),
                JsonParser.parseString(tools.worktree_discover(repository.toString())).asJsonObject)
            val inventory = service<WorktreeInventoryService>().inventory(repository.toString())
            assertTrue(inventory.current().entries.any { it.directory == linked.toString() })
            val directories = listOf(linked.toString())
            fun result(value: JsonObject): JsonObject = value.getAsJsonArray("results").single().asJsonObject
            val removal = result(operations.worktree_remove_preview(repository.toString(), directories))
            assertEquals("preview", removal["status"].asString)
            assertEquals("locked", operations.setLocked(repository.toString(), linked.toString(), true)["status"].asString)
            assertNotNull(inventory.current().entries.single { it.directory == linked.toString() }.locked)
            val blocked = result(JsonParser.parseString(tools.worktree_remove(
                repository.toString(), directories, listOf(removal["confirmation"].asString),
            )).asJsonObject)
            assertEquals("blocked", blocked["status"].asString)
            assertTrue(blocked.getAsJsonArray("blockedCodes").any { it.asString == "LOCKED" })
            assertTrue(Files.isDirectory(linked))
            assertEquals("unlocked", operations.setLocked(repository.toString(), linked.toString(), false)["status"].asString)
            assertNull(inventory.current().entries.single { it.directory == linked.toString() }.locked)
            val fresh = result(operations.worktree_remove_preview(repository.toString(), directories))
            val removed = result(JsonParser.parseString(tools.worktree_remove(
                repository.toString(), directories, listOf(fresh["confirmation"].asString),
            )).asJsonObject)
            assertEquals("removed", removed["status"].asString)
            assertFalse(Files.exists(linked))
            assertEquals(operations.worktree_discover(repository.toString()),
                JsonParser.parseString(tools.worktree_discover(repository.toString())).asJsonObject)
            assertEquals(listOf(repository.toString()), inventory.current().entries.map { it.directory })
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }

    fun testRemovalPreviewRefreshesSharedInventoryOncePerBatch() = runBlocking {
        val temporary = Files.createTempDirectory("worktree-preview-batch-").toRealPath()
        try {
            val repository = Files.createDirectory(temporary.resolve("repository"))
            GitWorktrees.git(repository, "init", "-b", "main")
            GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--allow-empty", "-m", "initial")
            val directories = listOf("first", "second", "third").map { name ->
                temporary.resolve(name).also {
                    GitWorktrees.git(repository, "worktree", "add", "-b", name, it.toString())
                }.toString()
            }
            val inventory = service<WorktreeInventoryService>().inventory(repository.toString())
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { inventory.snapshots.collect() }
            try {
                // Keep the watcher registered so preview requests, not subscription reconciliation, advance the version.
                withTimeout(10_000) { inventory.snapshots.filterNotNull().first { it.version >= 2 } }
                val operations = WorktreeOperations()
                val beforeEmpty = inventory.current().version
                assertTrue(operations.worktree_remove_preview(repository.toString(), emptyList(), cleanup = true)
                    .getAsJsonArray("results").isEmpty)
                assertEquals(beforeEmpty, inventory.current().version)
                repeat(2) {
                    val before = inventory.current().version
                    val preview = operations.worktree_remove_preview(repository.toString(), directories)
                    assertEquals(before + 1, inventory.current().version)
                    assertEquals(directories, preview.getAsJsonArray("results").map { it.asJsonObject["directory"].asString })
                    assertTrue(preview.getAsJsonArray("results").all { it.asJsonObject["status"].asString == "preview" })
                }
                val beforeCleanup = inventory.current().version
                val cleanup = operations.worktree_remove_preview(
                    repository.toString(), listOf(repository.toString()) + directories, cleanup = true,
                ).getAsJsonArray("results")
                assertEquals(beforeCleanup + 1, inventory.current().version)
                assertEquals("blocked", cleanup.first().asJsonObject["status"].asString)
                // Missing IDE cleanup context must fail eligible targets, not erase authoritative blockers.
                assertTrue(cleanup.drop(1).all { it.asJsonObject["status"].asString == "removal_failed" })
                assertTrue(directories.all { Files.isDirectory(Path.of(it)) })
            } finally {
                observer.cancelAndJoin()
            }
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }

    fun testRemovalApprovalBindsOptionsAndDirtyContents() = runBlocking {
        val temporary = Files.createTempDirectory("worktree-tools-removal-").toRealPath()
        try {
            val repository = Files.createDirectory(temporary.resolve("repository"))
            val linked = temporary.resolve("linked")
            GitWorktrees.git(repository, "init", "-b", "main")
            GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--allow-empty", "-m", "initial")
            GitWorktrees.git(repository, "worktree", "add", "-b", "feature", linked.toString())
            val file = linked.resolve("local.txt")
            Files.writeString(file, "first content")
            val tools = WorktreeTools()
            val directories = listOf(linked.toString())
            fun result(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").single().asJsonObject
            val safe = result(tools.worktree_remove_preview(repository.toString(), directories))
            assertEquals("blocked", safe["status"].asString)
            assertTrue(safe["selectable"].asBoolean)
            assertFalse(safe["safe"].asBoolean)
            val destructive = result(tools.worktree_remove_preview(repository.toString(), directories,
                deleteLocalChanges = directories))
            assertEquals("preview", destructive["status"].asString)
            val token = listOf(destructive["confirmation"].asString)
            val changedOptions = result(tools.worktree_remove(repository.toString(), directories, token))
            assertEquals("blocked", changedOptions["status"].asString)
            assertEquals("first content", Files.readString(file))
            Files.writeString(file, "second content")
            val stale = result(tools.worktree_remove(repository.toString(), directories, token,
                deleteLocalChanges = directories))
            assertEquals("blocked", stale["status"].asString)
            assertEquals("second content", Files.readString(file))
            val current = result(tools.worktree_remove_preview(repository.toString(), directories,
                deleteLocalChanges = directories))
            val removed = result(tools.worktree_remove(repository.toString(), directories,
                listOf(current["confirmation"].asString), deleteLocalChanges = directories))
            assertEquals("removed", removed["status"].asString)
            assertFalse(Files.exists(linked))
            assertEquals(GitWorktrees.git(repository, "rev-parse", "main").trim(),
                GitWorktrees.git(repository, "rev-parse", "feature").trim())
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }

    fun testRemovalCannotTargetAnOpenProjectWithDestructiveOptions() = runBlocking {
        val repository = Files.createDirectories(Path.of(project.basePath!!)).toRealPath()
        GitWorktrees.git(repository, "init", "-b", "main")
        GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "--allow-empty", "-m", "initial")
        val response = JsonParser.parseString(WorktreeTools().worktree_remove_preview(
            repository.toString(), listOf(repository.toString()), cleanup = true,
            deleteLocalChanges = listOf(repository.toString()),
        )).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("blocked", response["status"].asString)
        assertFalse(response["selectable"].asBoolean)
        assertTrue(response.getAsJsonArray("blockedCodes").any { it.asString == "OPEN_PROJECT" })
        assertFalse(response.has("confirmation"))
        assertFalse(response.has("trackedChanges"))
        assertTrue(Files.isDirectory(repository))
    }

    fun testUnconditionallyBlockedTargetsSkipRemovalAndCleanupEvidence() = runBlocking {
        val temporary = Files.createTempDirectory("worktree-tools-prefilter-").toRealPath()
        try {
            val repository = Files.createDirectory(temporary.resolve("repository"))
            GitWorktrees.git(repository, "init", "-b", "main")
            GitWorktrees.git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "--allow-empty", "-m", "initial")
            val locked = temporary.resolve("locked")
            val prunable = temporary.resolve("prunable")
            GitWorktrees.git(repository, "worktree", "add", "-b", "locked", locked.toString())
            GitWorktrees.git(repository, "worktree", "lock", "--reason", "protected", locked.toString())
            GitWorktrees.git(repository, "worktree", "add", "-b", "prunable", prunable.toString())
            com.intellij.openapi.util.io.FileUtil.delete(prunable.toFile())
            // A full removal preview would observe corrupt status; preflight needs only the inventory.
            val index = Path.of(GitWorktrees.git(locked, "rev-parse", "--path-format=absolute", "--git-path", "index").trim())
            Files.writeString(index, "not an index")
            val tools = WorktreeTools()
            val directories = listOf(repository.toString(), locked.toString(), prunable.toString())
            fun results(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonArray("results")
            val preview = results(tools.worktree_remove_preview(repository.toString(), directories, cleanup = true,
                deleteLocalBranches = directories, deleteLocalChanges = directories, includeClosedUnmerged = directories))
            val applied = results(tools.worktree_remove(repository.toString(), directories,
                List(directories.size) { "unusable-token" }, cleanup = true,
                deleteLocalBranches = directories, deleteLocalChanges = directories, includeClosedUnmerged = directories))
            for (response in listOf(preview, applied)) {
                assertEquals(3, response.size())
                for ((index, code) in listOf("MAIN_WORKTREE", "LOCKED", "PRUNABLE").withIndex()) {
                    val result = response[index].asJsonObject
                    assertEquals("blocked", result["status"].asString)
                    assertFalse(result["selectable"].asBoolean)
                    assertFalse(result["safe"].asBoolean)
                    assertTrue(result.getAsJsonArray("blockedCodes").any { it.asString == code })
                    assertEquals("NOT_CHECKED", result["cleanupKind"].asString)
                    assertFalse(result.has("trackedChanges"))
                    assertFalse(result.has("confirmation"))
                }
            }
            val bare = Files.createDirectory(temporary.resolve("bare"))
            GitWorktrees.git(bare, "init", "--bare")
            val result = results(tools.worktree_remove_preview(bare.toString(), listOf(bare.toString()),
                cleanup = true)).single().asJsonObject
            assertEquals("blocked", result["status"].asString)
            assertTrue(result.getAsJsonArray("blockedCodes").any { it.asString == "BARE_REPOSITORY" })
            assertFalse(result.has("confirmation"))
            assertTrue(Files.isDirectory(locked))
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile())
        }
    }

    fun testClosingProjectsRemainProtectedUntilAfterDisposal() = runBlocking {
        val directory = Files.createTempDirectory("worktree-tools-closing-").toRealPath()
        val manager = ProjectManager.getInstance()
        val closingProject = withContext(Dispatchers.EDT) {
            requireNotNull(manager.createProject("closing", directory.toString()))
        }
        val listener = WorktreeRemovalProjectListener()
        val tools = WorktreeOperations()
        try {
            assertFalse(manager.openProjects.contains(closingProject))
            withContext(Dispatchers.EDT) {
                listener.projectClosingBeforeSave(closingProject)
                assertFalse(tools.overlapsOpenProject(directory))
                listener.projectClosing(closingProject)
                assertTrue(tools.overlapsOpenProject(directory))
                assertTrue(tools.overlapsOpenProject(directory.resolve("child")))
                listener.projectClosed(closingProject)
                assertTrue(tools.overlapsOpenProject(directory))
                listener.afterProjectClosed(closingProject)
                assertFalse(tools.overlapsOpenProject(directory))
            }
        } finally {
            withContext(Dispatchers.EDT) {
                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                    com.intellij.openapi.util.Disposer.dispose(closingProject)
                }
            }
            WorktreeRemovalProjectListener.closingPaths.remove(closingProject)
            com.intellij.openapi.util.io.FileUtil.delete(directory.toFile())
        }
    }

    fun testOpenAndMergeRequiresOneUsableWorktree() {
        val action = OpenAndMergeWorktreeAction()
        val worktree = GitWorkingTree(project.basePath!!, "branch", false, false, false, false, "abc")
        val prunable = GitWorkingTree(project.basePath!!, "branch", false, false, false, true, "abc")
        for ((selection, enabled) in listOf(
            emptyList<GitWorkingTree>() to false,
            listOf(worktree) to SystemInfoRt.isMac,
            listOf(worktree, prunable) to false,
            listOf(prunable) to false,
        )) {
            val event = AnActionEvent.createFromDataContext("test", null, DataContext { key ->
                when (key) {
                    CommonDataKeys.PROJECT.name -> project
                    "SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB" -> selection
                    else -> null
                }
            })
            action.update(event)
            assertEquals(enabled, event.presentation.isEnabled)
        }
    }
    fun testCloseVetoLeavesProjectOpen() = runBlocking {
        Files.createDirectories(Path.of(project.basePath!!))
        val manager = ProjectManager.getInstance()
        val veto = object : VetoableProjectManagerListener {
            override fun canClose(candidate: Project): Boolean = candidate !== project
        }
        withContext(Dispatchers.EDT) { manager.addProjectManagerListener(veto) }
        try {
            val result = JsonParser.parseString(WorktreeTools().worktree_close(project.basePath!!)).asJsonObject
            assertEquals("close_cancelled", result["status"].asString)
            assertTrue(project.isOpen)
            assertFalse(project.isDisposed)
            assertTrue(manager.openProjects.contains(project))
            assertFalse(WorktreeRemovalProjectListener.closingPaths.containsKey(project))
        } finally {
            withContext(Dispatchers.EDT) { manager.removeProjectManagerListener(veto) }
        }
    }

    fun testExplicitFileResolutionRejectsOutsideSymlinkAndBinary() = runBlocking {
        val directory = Files.createDirectories(Path.of(project.basePath!!)).toRealPath()
        val content = Files.createDirectories(directory.resolve("content"))
        Files.writeString(content.resolve("owned.txt"), "owned")
        Files.write(content.resolve("binary.png"), byteArrayOf(0, 1, 2))
        Files.writeString(directory.resolve("not-content.txt"), "not content")
        val outside = createTempFile("outside.txt", "outside").toPath().toRealPath()
        Files.createSymbolicLink(content.resolve("escape.txt"), outside)
        val root = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(content)!!
        }
        withContext(Dispatchers.EDT) { PsiTestUtil.addContentRoot(module, root) }
        val tools = WorktreeOperations()
        assertNotNull(tools.resolveFile(project, directory, "content/owned.txt"))
        assertNotNull(tools.resolveFile(project, directory, "not-content.txt"))
        for (filePath in listOf(
            outside.toString(), "content/escape.txt", "content/binary.png",
            "content", "missing.txt", "", "../outside.txt",
        )) {
            assertNull(filePath, tools.resolveFile(project, directory, filePath))
        }
    }

    fun testExplicitContextNeverSubstitutesSelectedEditor() = runBlocking {
        val directory = Files.createDirectories(Path.of(project.basePath!!)).toRealPath()
        val content = Files.createDirectories(directory.resolve("content"))
        Files.writeString(content.resolve("requested.txt"), "requested")
        Files.writeString(content.resolve("selected.txt"), "selected")
        Files.writeString(content.resolve("unopened.txt"), "unopened")
        val root = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(content)!!
        }
        withContext(Dispatchers.EDT) { PsiTestUtil.addContentRoot(module, root) }
        val tools = WorktreeOperations()
        val requested = tools.resolveFile(project, directory, "content/requested.txt")!!
        val selected = tools.resolveFile(project, directory, "content/selected.txt")!!
        val unopened = tools.resolveFile(project, directory, "content/unopened.txt")!!
        withContext(Dispatchers.EDT) {
            HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
            val manager = FileEditorManager.getInstance(project)
            try {
                val requestedEditor = writeIntentReadAction {
                    manager.openTextEditor(OpenFileDescriptor(project, requested), true)
                }!!
                val selectedEditor = writeIntentReadAction {
                    manager.openTextEditor(OpenFileDescriptor(project, selected), true)
                }!!
                assertSame(selectedEditor, manager.selectedTextEditor)
                val openBefore = manager.openFiles.toSet()
                val file = readAction { tools.openFileContext(project, requested, directory) }!!
                val component = selectedEditor.component
                val context = tools.actionDataContext(project, component, file)
                assertSame(component, PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(context))
                assertSame(requestedEditor, CommonDataKeys.EDITOR.getData(context))
                assertSame(requested, CommonDataKeys.VIRTUAL_FILE.getData(context))
                assertSame(requestedEditor.document, CommonDataKeys.EDITOR.getData(context)!!.document)
                assertSame(project, CommonDataKeys.PSI_FILE.getData(context)!!.project)
                assertEquals("requested", readAction { CommonDataKeys.PSI_FILE.getData(context)!!.text })
                val injected = AnActionEvent.getInjectedDataContext(context)
                assertSame(component, PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(injected))
                assertSame(requestedEditor, CommonDataKeys.EDITOR.getData(injected))
                assertSame(requested, CommonDataKeys.VIRTUAL_FILE.getData(injected))
                val projectOnly = tools.actionDataContext(project, component, null)
                assertSame(component, PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(projectOnly))
                assertNull(CommonDataKeys.EDITOR.getData(projectOnly))
                assertNull(CommonDataKeys.VIRTUAL_FILE.getData(projectOnly))
                assertNull(CommonDataKeys.PSI_FILE.getData(projectOnly))
                assertNull(CommonDataKeys.EDITOR.getData(AnActionEvent.getInjectedDataContext(projectOnly)))
                assertNull(readAction { tools.openFileContext(project, unopened, directory) })
                assertNull(CommonDataKeys.VIRTUAL_FILE.getData(AnActionEvent.getInjectedDataContext(projectOnly)))
                assertSame(selectedEditor, manager.selectedTextEditor)
                assertEquals(openBefore, manager.openFiles.toSet())
                writeIntentReadAction { manager.closeFile(requested) }
                assertFalse(readAction { file.available(project) })
            } finally {
                writeIntentReadAction {
                    manager.closeFile(requested)
                    manager.closeFile(selected)
                }
            }
        }
    }
}
