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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
import javax.swing.JComponent
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
        val reason: String? = null,
    )

    private data class ActionAvailability(val event: AnActionEvent? = null, val reason: String? = null)

    private data class Target(val project: Project, val frame: JFrame) {
        fun available(): Boolean = !project.isDisposed && project.isOpen && frame.isDisplayable
    }

    internal data class FileContext(
        val file: VirtualFile, val psi: PsiFile, val fileEditor: TextEditor, val directory: Path,
    ) {
        val editor get() = fileEditor.editor

        fun available(project: Project): Boolean =
            file.isValid && psi.isValid && psi.project === project && fileEditor.isValid &&
                Path.of(file.path).normalize().startsWith(directory) &&
                file.canonicalPath?.let { Path.of(it).startsWith(directory) } == true &&
                !editor.isDisposed && editor.project === project &&
                FileDocumentManager.getInstance().getCachedDocument(file) === editor.document
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
    @McpDescription("Dispatch a registered IDE action by exact ID in the specified project window context. Optional filePath supplies an already-open text editor in that exact project, without selecting it; unopened files return file_not_open. Does not redirect to the focused file or project. Actions may open dialogs or change focus by their own behavior. Dispatched does not imply asynchronous completion; normal confirmation rules still apply.")
    suspend fun worktree_action(
        @McpDescription("Exact registered IDE action ID, not a search abbreviation.") actionId: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
        @McpDescription("Optional absolute or project-relative text file inside this project, already open in a text editor. Uses its existing caret and selection without selecting its tab.") filePath: String? = null,
    ): String {
        if (modalPreflight()) return encode(Outcome("unavailable_modal", actionId = actionId, reason = "modal_dialog"))
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        return withFileContext(target, path, filePath) { file -> encode(invoke(actionId, target, file)) }
    }

    @McpTool
    @McpDescription("Find registered IDE actions by case-insensitive name or ID substring without executing them. Optional filePath uses an already-open text editor in the exact requested project, as worktree_action does. Never opens or selects editor tabs or redirects to the focused file. Without filePath, uses only project/window context. Action groups are excluded.")
    suspend fun worktree_search_actions(
        @McpDescription("Non-empty substring of the action name or ID.") query: String,
        @McpDescription("Existing project directory whose window supplies the action context.") directory: String,
        @McpDescription("Maximum results, from 1 to 100.") limit: Int = 50,
        @McpDescription("Optional absolute or project-relative text file inside this project, already open in a text editor. Never opens or selects a tab.") filePath: String? = null,
    ): String {
        if (modalPreflight()) return encode(Outcome("unavailable_modal", reason = "modal_dialog"))
        val text = query.trim()
        if (text.isEmpty() || limit !in 1..100) return encode(Outcome("invalid_query"))
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        return withFileContext(target, path, filePath) { file -> searchActions(text, limit, target, path, file) }
    }

    private suspend fun searchActions(text: String, limit: Int, target: Target, path: Path, file: FileContext?): String {
        val manager = ActionManager.getInstance()
        val matches = onEdt {
            manager.getActionIdList("").asSequence().filter { id ->
                !manager.isGroup(id) && (id.contains(text, ignoreCase = true) ||
                    manager.getActionOrStub(id)?.templatePresentation?.text?.contains(text, ignoreCase = true) == true)
            }.sorted().take(limit + 1).toList()
        }
        val actions = mutableListOf<Map<String, Any?>>()
        for (id in matches.take(limit)) {
            val action = manager.getAction(id) ?: continue
            val availability = updatedEvent(action, target, file)
            actions.add(mapOf(
                "actionId" to id,
                "name" to (availability.event?.presentation?.text ?: id),
                "enabled" to (availability.reason == null),
                "reason" to availability.reason,
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

    private suspend fun invoke(actionId: String, target: Target, file: FileContext? = null): Outcome {
        val action = ActionManager.getInstance().getAction(actionId)
            ?: return Outcome("unknown_action", actionId = actionId)
        val availability = updatedEvent(action, target, file)
        val event = availability.event
        if (availability.reason != null || event == null) {
            return Outcome("unavailable", actionId = actionId, reason = availability.reason)
        }
        return onEdt {
            if (!target.available()) return@onEdt Outcome("target_unavailable", actionId = actionId, reason = "target_unavailable")
            if (modal()) return@onEdt Outcome("unavailable_modal", actionId = actionId, reason = "modal_dialog")
            if (file != null && !readAction { file.available(target.project) }) {
                return@onEdt Outcome("target_unavailable", actionId = actionId, reason = "file_context_unavailable")
            }
            val performed = writeIntentReadAction { ActionUtil.performAction(action, event) }
            Outcome(
                if (performed.isPerformed) "dispatched" else "unavailable", target.project.basePath, actionId,
                reason = if (performed.isPerformed) null else if (performed.isFailed) "dispatch_failed" else "dispatch_rejected",
            )
        }
    }

    private suspend fun updatedEvent(action: AnAction, target: Target, file: FileContext? = null): ActionAvailability {
        val availability = onEdt {
            if (!target.available()) return@onEdt ActionAvailability(reason = "target_unavailable")
            if (modal()) return@onEdt ActionAvailability(reason = "modal_dialog")
            if (file != null && !readAction { file.available(target.project) }) {
                return@onEdt ActionAvailability(reason = "file_context_unavailable")
            }
            val dataContext = actionDataContext(target.project, target.frame.rootPane, file)
            ActionAvailability(AnActionEvent.createEvent(
                action, dataContext, action.templatePresentation.clone(),
                ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
            ))
        }
        val event = availability.event ?: return availability
        val update = if (action.actionUpdateThread == ActionUpdateThread.BGT) {
            withContext(Dispatchers.Default) { readAction { ActionUtil.updateAction(action, event) } }
        } else onEdt { ActionUtil.updateAction(action, event) }
        val reason = when {
            update.isFailed -> "update_failed"
            !update.isPerformed -> "update_rejected"
            !event.presentation.isVisible -> "hidden"
            !event.presentation.isEnabled -> "disabled"
            else -> null
        }
        return ActionAvailability(event, reason)
    }

    internal fun actionDataContext(project: Project, component: JComponent, file: FileContext?): DataContext =
        DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                PlatformCoreDataKeys.CONTEXT_COMPONENT.name -> component
                CommonDataKeys.VIRTUAL_FILE.name -> file?.file
                CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> file?.let { arrayOf(it.file) }
                CommonDataKeys.PSI_FILE.name -> file?.psi
                CommonDataKeys.PSI_ELEMENT.name -> file?.psi
                CommonDataKeys.EDITOR.name, CommonDataKeys.HOST_EDITOR.name,
                CommonDataKeys.EDITOR_EVEN_IF_INACTIVE.name -> file?.editor
                CommonDataKeys.CARET.name -> file?.editor?.caretModel?.currentCaret
                PlatformCoreDataKeys.FILE_EDITOR.name -> file?.fileEditor
                else -> null
            }
        }

    private suspend fun withFileContext(
        target: Target, directory: Path, filePath: String?, block: suspend (FileContext?) -> String,
    ): String {
        if (filePath == null) return block(null)
        val file = resolveFile(target.project, directory, filePath)
            ?: return encode(Outcome("invalid_file", directory.toString()))
        val context = onEdt {
            readAction {
                if (!target.available()) return@readAction null
                openFileContext(target.project, file, directory)
            }
        } ?: return encode(Outcome("file_not_open", directory.toString()))
        return block(context)
    }

    internal suspend fun resolveFile(project: Project, directory: Path, filePath: String): VirtualFile? {
        val path = withContext(Dispatchers.IO) {
            try {
                if (filePath.isBlank()) return@withContext null
                val requested = directory.resolve(filePath).normalize()
                if (!requested.startsWith(directory)) return@withContext null
                requested.toRealPath().takeIf { it.startsWith(directory) && Files.isRegularFile(it) }
            } catch (_: IOException) {
                null
            } catch (_: InvalidPathException) {
                null
            }
        } ?: return null
        val file = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        } ?: return null
        return readAction {
            file.takeIf {
                !project.isDisposed && it.isValid && !it.isDirectory && !it.fileType.isBinary &&
                    PsiManager.getInstance(project).findFile(it) != null &&
                    FileDocumentManager.getInstance().getDocument(it) != null
            }
        }
    }

    internal fun openFileContext(project: Project, file: VirtualFile, directory: Path): FileContext? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        // ponytail: reuse IDE-owned editors; temporary editors cannot outlive arbitrary asynchronous actions safely.
        val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>()
            .firstOrNull { !it.editor.isDisposed && it.editor.project === project } ?: return null
        return FileContext(file, psi, editor, directory).takeIf { it.available(project) }
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

    private suspend fun modalPreflight(): Boolean =
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { modal() }

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
