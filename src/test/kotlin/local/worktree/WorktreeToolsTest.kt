package local.worktree

import com.google.gson.JsonParser
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.VetoableProjectManagerListener
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.openapi.util.SystemInfoRt
import git4idea.GitWorkingTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withContext

class WorktreeToolsTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    override fun isCreateDirectoryBasedProject(): Boolean = true


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
        val tools = WorktreeTools()
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
        val tools = WorktreeTools()
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
