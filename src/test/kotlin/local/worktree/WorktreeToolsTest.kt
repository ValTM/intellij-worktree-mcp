package local.worktree

import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.VetoableProjectManagerListener
import com.intellij.testFramework.HeavyPlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withContext

class WorktreeToolsTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    override fun isCreateDirectoryBasedProject(): Boolean = true

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
}
