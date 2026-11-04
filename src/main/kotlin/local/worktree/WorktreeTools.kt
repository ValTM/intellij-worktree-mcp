package local.worktree

import com.google.gson.Gson
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.MacUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JFrame

class WorktreeTools : McpToolset {
    private val gson = Gson()

    companion object {
        // ponytail: serialize plugin opens; use per-path locks only if parallel opening becomes necessary.
        private val opening = Mutex()
    }

    private data class Outcome(
        val status: String,
        val directory: String? = null,
        val actionId: String? = null,
        val merge: Outcome? = null,
    )

    private data class Target(val project: Project, val frame: JFrame) {
        fun available(): Boolean = !project.isDisposed && project.isOpen && frame.isDisplayable
    }

    @McpTool
    @McpDescription("List open project paths and window state without selecting or focusing them. Also reports the last active WebStorm project and the installed IDE build.")
    suspend fun worktree_status(): String = withContext(Dispatchers.EDT) {
        val windows = WindowManager.getInstance()
        gson.toJson(mapOf(
            "build" to ApplicationInfo.getInstance().build.asString(),
            "lastActiveDirectory" to lastTarget()?.project?.basePath,
            "projects" to ProjectManager.getInstance().openProjects.map { project ->
                val frame = windows.getFrame(project)
                mapOf(
                    "directory" to project.basePath,
                    "name" to project.name,
                    "frame" to frame?.let { System.identityHashCode(it) },
                    "title" to frame?.title,
                    "x" to frame?.x, "y" to frame?.y,
                    "width" to frame?.width, "height" to frame?.height,
                    "focused" to frame?.isFocused,
                    "ideFullscreen" to windows.getIdeFrame(project)?.isInFullScreen
                )
            }
        ))
    }

    @McpTool
    @McpDescription("Open an existing directory as an independent project, or reuse its canonical path. Merge is OFF by default. When requested, Merge All includes ALL eligible IDE project windows, not only this project and its target. Captures the previous last-active IDE window before opening; never attaches projects or bypasses trust prompts.")
    suspend fun worktree_open(
        @McpDescription("Existing absolute project directory.") directory: String,
        @McpDescription("Explicit opt-in to Merge All Project Windows after opening or reusing.") merge: Boolean = false,
        @McpDescription("Already-open merge target; omit to capture the last-active IDE project before opening.") targetDirectory: String? = null,
    ): String {
        val captured = onEdt { lastTarget() }
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = if (merge && targetDirectory != null) {
            val targetPath = canonical(targetDirectory)
                ?: return encode(Outcome("invalid_target_directory", targetDirectory))
            onEdt { target(findProject(targetPath)) }
                ?: return encode(Outcome("target_not_open", targetDirectory))
        } else captured
        return opening.withLock {
            val existing = onEdt { findProject(path) }
            if (existing == null && onEdt { modal() }) {
                return@withLock encode(Outcome("unavailable_modal", path.toString()))
            }
            val project = existing ?: ProjectManagerEx.getInstanceEx().openProjectAsync(
                path, OpenProjectTask { forceOpenInNewFrame = true }
            )
            if (project == null || !project.isOpen || project.isDisposed) {
                return@withLock encode(Outcome("open_cancelled", path.toString()))
            }
            val mergeResult = if (!merge) null else if (target == null) Outcome("not_needed") else merge(target)
            encode(Outcome(if (existing == null) "opened" else "reused", path.toString(), merge = mergeResult))
        }
    }

    @McpTool
    @McpDescription("Opt-in macOS Merge All Project Windows. Merges ALL eligible project windows, not a selected pair. Uses targetDirectory when supplied, otherwise the last active IDE project window captured at entry. Never switches focus to resolve a target.")
    suspend fun worktree_merge(
        @McpDescription("Existing target project directory; omit for the last active WebStorm project window.") targetDirectory: String? = null,
    ): String {
        val captured = onEdt { lastTarget() }
        val target = if (targetDirectory == null) captured else {
            val path = canonical(targetDirectory)
                ?: return encode(Outcome("invalid_target_directory", targetDirectory))
            onEdt { target(findProject(path)) }
                ?: return encode(Outcome("target_not_open", targetDirectory))
        }
        if (target == null) {
            return encode(Outcome(if (ProjectManager.getInstance().openProjects.size < 2) "not_needed" else "no_target"))
        }
        return encode(merge(target))
    }

    @McpTool
    @McpDescription("Close exactly the project at the supplied canonical path using normal IDE save, running-process and cancellation safeguards. Never force-disposes, merges other windows or intentionally quits the IDE. Requires user authorization for this exact project.")
    suspend fun worktree_close(
        @McpDescription("Existing absolute directory of the project authorized for closure.") directory: String,
    ): String {
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        return onEdt {
            val project = findProject(path) ?: return@onEdt encode(Outcome("already_closed", path.toString()))
            if (modal()) return@onEdt encode(Outcome("unavailable_modal", path.toString()))
            val closed = writeIntentReadAction { ProjectManager.getInstance().closeAndDispose(project) }
            val stillOpen = ProjectManager.getInstance().openProjects.contains(project)
            encode(Outcome(if (closed && !stillOpen) "closed" else "close_cancelled", path.toString()))
        }
    }

    @McpTool
    @McpDescription("Dispatch a registered IDE action by exact ID in the specified project window context. Does not simulate input or redirect to a focused project. Actions may open dialogs or change focus by their own behavior. Dispatched does not imply asynchronous work has completed; normal confirmation rules still apply.")
    suspend fun worktree_action(
        @McpDescription("Exact registered IDE action ID, not a search abbreviation.") actionId: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
    ): String {
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        return encode(invoke(actionId, target))
    }

    @McpTool
    @McpDescription("Find registered IDE actions by case-insensitive name or ID substring without executing them. Returns exact action IDs, names and enabled status in the same project/window context as worktree_action, not the focused editor context. Action groups are excluded.")
    suspend fun worktree_search_actions(
        @McpDescription("Non-empty substring of the action name or ID.") query: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
        @McpDescription("Maximum results, from 1 to 100.") limit: Int = 50,
    ): String {
        val text = query.trim()
        if (text.isEmpty() || limit !in 1..100) return encode(Outcome("invalid_query"))
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        val manager = ActionManager.getInstance()
        val matches = onEdt {
            manager.getActionIdList("").asSequence().filter { id ->
                !manager.isGroup(id) && (id.contains(text, ignoreCase = true) ||
                    manager.getActionOrStub(id)?.templatePresentation?.text?.contains(text, ignoreCase = true) == true)
            }.sorted().take(limit + 1).toList()
        }
        val actions = mutableListOf<Map<String, Any>>()
        for (id in matches.take(limit)) {
            val action = manager.getAction(id) ?: continue
            val event = updatedEvent(action, target)
                ?: return encode(Outcome("target_unavailable", path.toString()))
            actions.add(mapOf(
                "actionId" to id,
                "name" to (event.presentation.text ?: id),
                "enabled" to event.presentation.isEnabledAndVisible,
            ))
        }
        return gson.toJson(mapOf(
            "status" to "ok", "directory" to path.toString(),
            "actions" to actions, "truncated" to (matches.size > limit),
        ))
    }

    private suspend fun merge(target: Target): Outcome {
        if (!SystemInfoRt.isMac) return Outcome("unsupported_platform")
        val windows = onEdt {
            if (!target.available()) return@onEdt null
            ProjectManager.getInstance().openProjects.mapNotNull { project ->
                WindowManager.getInstance().getFrame(project)?.takeIf { it.isDisplayable }
            }
        } ?: return Outcome("target_unavailable")
        if (windows.size < 2) return Outcome("not_needed")
        val alreadyMerged = native {
            if (!target.available() || windows.any { !it.isDisplayable }) return@native false
            val group = Foundation.invoke(MacUtil.getWindowFromJavaWindow(target.frame), "tabGroup")
            group.toLong() != 0L && windows.all {
                Foundation.invoke(MacUtil.getWindowFromJavaWindow(it), "tabGroup") == group
            }
        }
        if (alreadyMerged) return Outcome("not_needed")
        val outcome = invoke("MergeAllWindowsAction", target)
        if (outcome.status != "dispatched") return outcome

        // Queue behind native merge, then behind its queued EDT tab-bar update.
        val updated = CompletableDeferred<Unit>(currentCoroutineContext().job)
        Foundation.executeOnMainThread(true, false) {
            ApplicationManager.getApplication().invokeLater { updated.complete(Unit) }
        }
        updated.await()
        return native {
            if (!target.available()) return@native Outcome("target_unavailable")
            val window = MacUtil.getWindowFromJavaWindow(target.frame)
            val group = Foundation.invoke(window, "tabGroup")
            if (group.toLong() == 0L) return@native Outcome("merge_pending")
            Foundation.invoke(group, "setSelectedWindow:", window)
            val merged = Foundation.invoke(group, "selectedWindow") == window && windows.all {
                it.isDisplayable && Foundation.invoke(MacUtil.getWindowFromJavaWindow(it), "tabGroup") == group
            }
            Outcome(if (merged) "merged" else "merge_pending", target.project.basePath)
        }
    }

    private suspend fun invoke(actionId: String, target: Target): Outcome {
        val action = ActionManager.getInstance().getAction(actionId)
            ?: return Outcome("unknown_action", actionId = actionId)
        val event = updatedEvent(action, target)
            ?: return Outcome("target_unavailable", actionId = actionId)
        if (!event.presentation.isEnabledAndVisible) {
            return Outcome("unavailable", actionId = actionId)
        }
        return onEdt {
            if (!target.available()) return@onEdt Outcome("target_unavailable", actionId = actionId)
            if (modal()) return@onEdt Outcome("unavailable_modal", actionId = actionId)
            val performed = writeIntentReadAction { ActionUtil.performAction(action, event) }
            Outcome(if (performed.isPerformed) "dispatched" else "unavailable", target.project.basePath, actionId)
        }
    }

    private suspend fun updatedEvent(action: AnAction, target: Target): AnActionEvent? {
        val event = onEdt {
            if (!target.available() || modal()) return@onEdt null
            val context = DataContext { key ->
                when (key) {
                    CommonDataKeys.PROJECT.name -> target.project
                    PlatformCoreDataKeys.CONTEXT_COMPONENT.name -> target.frame.rootPane
                    else -> null
                }
            }
            AnActionEvent.createEvent(
                action, context, action.templatePresentation.clone(),
                ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
            )
        } ?: return null
        val update = if (action.actionUpdateThread == ActionUpdateThread.BGT) {
            withContext(Dispatchers.Default) { readAction { ActionUtil.updateAction(action, event) } }
        } else onEdt { ActionUtil.updateAction(action, event) }
        if (!update.isPerformed) event.presentation.isEnabled = false
        return event
    }

    private suspend fun lastTarget(): Target? {
        val last = target(IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project) ?: return null
        if (!SystemInfoRt.isMac) return last
        val targets = ProjectManager.getInstance().openProjects.mapNotNull { target(it) }
        return native {
            if (!last.available()) return@native null
            val group = Foundation.invoke(MacUtil.getWindowFromJavaWindow(last.frame), "tabGroup")
            val selected = Foundation.invoke(group, "selectedWindow")
            // Native tab selection need not update the IDE's last-focused frame while another app is foreground.
            targets.firstOrNull {
                it.available() && MacUtil.getWindowFromJavaWindow(it.frame) == selected
            } ?: last
        }
    }

    private fun target(project: Project?): Target? {
        if (project == null || project.isDisposed) return null
        return WindowManager.getInstance().getFrame(project)?.let { Target(project, it) }
    }

    private suspend fun findProject(path: Path): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath?.let { base -> canonical(base) == path } == true
        }

    private suspend fun canonical(directory: String): Path? = withContext(Dispatchers.IO) {
        try {
            Path.of(directory).takeIf { it.isAbsolute }?.toRealPath()?.takeIf { Files.isDirectory(it) }
        } catch (_: IOException) {
            null
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun modal(): Boolean = ModalityState.current() != ModalityState.nonModal()

    private suspend fun <T> onEdt(block: suspend () -> T): T =
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { block() }

    private suspend fun <T> native(block: () -> T): T {
        val result = CompletableDeferred<T>(currentCoroutineContext().job)
        Foundation.executeOnMainThread(true, false) {
            if (result.isActive) {
                try {
                    result.complete(block())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        }
        return result.await()
    }

    private fun encode(outcome: Outcome): String = gson.toJson(outcome)
}
