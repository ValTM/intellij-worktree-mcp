package local.worktree

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.DataManager
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
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
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.impl.ProjectLifecycleListener
import com.intellij.openapi.project.ProjectManager
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

private typealias Outcome = LifecycleOutcome

internal class RemovalPreparationException(val project: Project, message: String, cause: Exception) : IOException(message, cause)

internal class WorktreeOperations {
    private val gson = Gson()

    companion object {
        internal val lifecycleBusy: Boolean get() = ProjectWindowLifecycle.busy
    }

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

    suspend fun worktree_status(): JsonObject = withContext(Dispatchers.EDT) {
        val windows = WindowManager.getInstance()
        json(mapOf(
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

    private suspend fun discover(repositoryDirectory: String): List<GitWorktree> {
        val snapshot = service<WorktreeInventoryService>().inventory(repositoryDirectory).refresh()
        snapshot.error?.let { throw IOException(it) }
        return snapshot.entries
    }

    suspend fun setLocked(repositoryDirectory: String, directory: String, locked: Boolean): JsonObject = inventoryResult {
        val inventory = service<WorktreeInventoryService>().inventory(repositoryDirectory)
        val path = canonical(directory) ?: return@inventoryResult encode(Outcome("invalid_directory", directory))
        require(discover(repositoryDirectory).any { it.directory == path.toString() }) {
            "Target is not a registered worktree of this repository"
        }
        try {
            GitWorktrees.git(Path.of(repositoryDirectory), "worktree", if (locked) "lock" else "unlock", "--", path.toString())
            encode(Outcome(if (locked) "locked" else "unlocked", path.toString()))
        } finally {
            // Refresh after failure too: another Git client may have changed the lock.
            withContext(kotlinx.coroutines.NonCancellable) { inventory.refresh() }
        }
    }

    suspend fun copySettings(sourceDirectory: String, directory: String): PortableIdeSettings.Result =
        PortableIdeSettings.copy(Path.of(sourceDirectory), Path.of(directory))

    suspend fun worktree_discover(
        repositoryDirectory: String,
    ): JsonObject = inventoryResult {
        val entries = discover(repositoryDirectory)
        val current = onEdt { lastTarget()?.project }
        val worktrees = entries.map { entry ->
            val path = canonical(entry.directory)
            val project = if (path == null) null else onEdt { findProject(path) }
            mapOf(
                "directory" to entry.directory, "head" to entry.head, "branch" to entry.branch,
                "main" to entry.main, "detached" to entry.detached, "bare" to entry.bare,
                "locked" to entry.locked, "prunable" to entry.prunable,
                "open" to (project != null), "current" to (project != null && project === current),
            )
        }
        json(mapOf("status" to "ok", "worktrees" to worktrees))
    }

    suspend fun worktree_switch(
        repositoryDirectory: String,
        directory: String,
    ): JsonObject = inventoryResult {
        val path = canonical(directory) ?: return@inventoryResult encode(Outcome("invalid_directory", directory))
        val entries = discover(repositoryDirectory)
        val entry = entries.firstOrNull { canonical(it.directory) == path }
            ?: return@inventoryResult encode(Outcome("not_in_repository", path.toString()))
        if (entry.bare) return@inventoryResult encode(Outcome("bare_repository", path.toString()))
        val opened = lifecycle.open(path.toString())
        encode(if (opened.status == "opened" || opened.status == "reused") opened.copy(status = "switched") else opened)
    }

    private suspend fun inventoryResult(block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (error: IOException) {
        encode(Outcome("unavailable", reason = error.message))
    } catch (error: IllegalArgumentException) {
        encode(Outcome("invalid_directory", reason = error.message))
    }

    suspend fun worktree_create_preview(
        repositoryDirectory: String,
        directory: String,
        branch: String,
        startRef: String,
        newBranch: Boolean = false,
    ): JsonObject = creationResult {
        val plan = GitWorktrees.previewCreate(repositoryDirectory, directory, branch, startRef, newBranch)
        val result = gson.toJsonTree(plan).asJsonObject
        result.addProperty("status", "preview")
        result
    }

    suspend fun worktree_create(
        repositoryDirectory: String,
        directory: String,
        branch: String,
        startRef: String,
        confirmation: String,
        newBranch: Boolean = false,
        open: Boolean = false,
        merge: Boolean = false,
        targetDirectory: String? = null,
    ): JsonObject = creationResult {
        require(open || (!merge && targetDirectory == null)) { "Window options require opening the created worktree" }
        val plan = GitWorktrees.previewCreate(repositoryDirectory, directory, branch, startRef, newBranch)
        val created = GitWorktrees.create(plan, confirmation)
        if (!open) return@creationResult json(mapOf("status" to "created", "directory" to created))
        val opening = try {
            worktree_open(created, merge, targetDirectory)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            gson.toJsonTree(mapOf("status" to "open_failed", "reason" to error.message)).asJsonObject
        }
        val openStatus = opening["status"].asString
        val opened = openStatus == "opened" || openStatus == "reused"
        json(mapOf(
            "status" to if (opened) "created" else "created_open_failed",
            "directory" to created, "opening" to opening,
        ))
    }

    private suspend fun creationResult(block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (error: GitWorktrees.CreationException) {
        json(mapOf(
            "status" to "creation_incomplete", "directory" to error.directory,
            "branch" to error.branch, "reason" to error.message,
        ))
    } catch (error: IOException) {
        encode(Outcome("creation_failed", reason = error.message))
    } catch (error: IllegalArgumentException) {
        encode(Outcome("invalid_request", reason = error.message))
    }

    suspend fun worktree_remove_preview(
        repositoryDirectory: String,
        directories: List<String>,
        cleanup: Boolean = false,
        deleteLocalBranches: List<String> = emptyList(),
        deleteLocalChanges: List<String> = emptyList(),
        includeClosedUnmerged: List<String> = emptyList(),
    ): JsonObject = inventoryResult {
        validateRemovalOptions(directories, deleteLocalBranches, deleteLocalChanges, includeClosedUnmerged)
        if (directories.isEmpty()) return@inventoryResult json(mapOf("status" to "preview", "results" to emptyList<JsonObject>()))
        require(Path.of(repositoryDirectory).isAbsolute) { "Repository directory must be absolute" }
        require(directories.all { Path.of(it).isAbsolute }) { "Worktree directories must be absolute" }
        val cleanupProject = cleanupProjectProvider(repositoryDirectory, cleanup)
        // Fetch before the batch snapshot, but preserve per-target blockers if cleanup is unavailable.
        val project = runCatching { cleanupProject() }.onFailure {
            if (it !is IOException && it !is IllegalArgumentException) throw it
        }
        val entries = discover(repositoryDirectory)
        val results = directories.map { directory ->
            removalResult(directory) {
                removalPreview(repositoryDirectory, directory, { project.getOrThrow() },
                    GitWorktrees.RemovalOptions(directory in deleteLocalBranches, directory in deleteLocalChanges),
                    directory in includeClosedUnmerged, entries).second
            }
        }
        json(mapOf("status" to "preview", "results" to results))
    }

    suspend fun worktree_remove(
        repositoryDirectory: String,
        directories: List<String>,
        confirmations: List<String>,
        cleanup: Boolean = false,
        deleteLocalBranches: List<String> = emptyList(),
        deleteLocalChanges: List<String> = emptyList(),
        includeClosedUnmerged: List<String> = emptyList(),
    ): JsonObject = inventoryResult {
        require(directories.size == confirmations.size) { "Each directory requires its own preview confirmation" }
        validateRemovalOptions(directories, deleteLocalBranches, deleteLocalChanges, includeClosedUnmerged)
        val cleanupProject = cleanupProjectProvider(repositoryDirectory, cleanup)
        val results = directories.mapIndexed { index, directory ->
            removalResult(directory) {
                ProjectWindowLifecycle.operation.withLock {
                    val (candidate, preview) = removalPreview(repositoryDirectory, directory, cleanupProject,
                        GitWorktrees.RemovalOptions(directory in deleteLocalBranches, directory in deleteLocalChanges),
                        directory in includeClosedUnmerged)
                    if (preview["status"].asString != "preview") return@withLock preview
                    val plan = requireNotNull(candidate)
                    require(confirmations[index].isNotEmpty() && confirmations[index] == preview["confirmation"].asString) {
                        "Removal state or options changed; obtain and approve a new preview"
                    }
                    val removed = GitWorktrees.remove(plan, plan.confirmation) {
                        val path = canonical(plan.directory)
                        require(path != null && !onEdt { overlapsOpenProject(path) }) {
                            "Project opened or is still closing; close it separately and obtain a new preview"
                        }
                    }
                    gson.toJsonTree(mapOf("directory" to removed, "status" to "removed",
                        "localBranchDeleted" to plan.options.deleteLocalBranch)).asJsonObject
                }
            }
        }
        json(mapOf("status" to "completed", "results" to results))
    }

    internal suspend fun prepareRemovalProjects(repositoryDirectory: String, directories: List<String>): Project {
        require(directories.isNotEmpty()) { "Select the exact worktree projects authorized for closure" }
        val entries = discover(repositoryDirectory)
        val main = entries.singleOrNull { it.main && !it.bare }
            ?: throw IOException("The repository has no usable main worktree")
        val mainPath = canonical(main.directory) ?: throw IOException("Main worktree directory is unavailable")
        val paths = directories.map { canonical(it) ?: throw IOException("Worktree directory is unavailable: $it") }
        require(paths.distinct().size == paths.size) { "Worktree directories must be unique" }
        for (path in paths) {
            val entry = entries.singleOrNull { it.directory == path.toString() }
            val blocks = WorktreeRemovalPolicy.blockers(entry, open = false, closeProjects = true)
            require(blocks.isEmpty()) { "Worktree is not eligible for removal: $path: ${blocks.values.joinToString("; ")}" }
        }
        onEdt { validateRemovalProjects(paths) }
        val opening = lifecycle.open(mainPath.toString())
        require(opening.status == "opened" || opening.status == "reused") {
            "Could not switch to the main worktree: ${opening.status}"
        }
        val mainProject = onEdt { findProject(mainPath) }
            ?: throw IOException("The main worktree project is no longer open")
        try {
            require(ProjectWindowLifecycle.operation.tryLock()) { "Another project operation is in progress" }
            try {
                onEdt {
                    for (path in paths) {
                        require(mainProject.isOpen && !mainProject.isDisposed) { "The main worktree project is no longer open" }
                        validateRemovalProjects(paths)
                        val closing = findProject(path)
                        if (closing != null) {
                            val outcome = closeProject(closing, path)
                            require(outcome.status == "closed") { "Project closure stopped at $path: ${outcome.status}" }
                        }
                        require(!overlapsOpenProject(path)) { "Project is still open or closing: $path" }
                    }
                    require(mainProject.isOpen && !mainProject.isDisposed) { "The main worktree project is no longer open" }
                }
            } finally {
                ProjectWindowLifecycle.operation.unlock()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw RemovalPreparationException(mainProject, error.message ?: "Project closure did not complete", error)
        }
        return mainProject
    }

    private suspend fun validateRemovalProjects(paths: List<Path>) {
        for (directory in WorktreeRemovalProjectListener.closingPaths.values) {
            val base = canonical(directory) ?: Path.of(directory).toAbsolutePath().normalize()
            require(paths.none { base.startsWith(it) || it.startsWith(base) }) { "Project is still closing: $base" }
        }
        val roots = ProjectManager.getInstance().openProjects.mapNotNull { project ->
            project.basePath?.let { canonical(it) ?: Path.of(it).toAbsolutePath().normalize() }
        }
        for (path in paths) {
            val overlaps = roots.filter { it.startsWith(path) || path.startsWith(it) }
            require(overlaps.all { it == path } && overlaps.size <= 1) {
                "An overlapping or ambiguous project prevents exact worktree closure: $path"
            }
        }
    }

    private fun validateRemovalOptions(directories: List<String>, vararg options: List<String>) {
        require(directories.distinct().size == directories.size) { "Worktree directories must be unique" }
        require(options.all { directories.containsAll(it) }) { "Removal options must refer only to selected directories" }
    }

    private suspend fun cleanupProject(repositoryDirectory: String): Project {
        val path = canonical(repositoryDirectory) ?: throw IOException("Repository directory is unavailable")
        return onEdt(ModalityState.any()) {
            ProjectManager.getInstance().openProjects.firstOrNull { project ->
                git4idea.repo.GitRepositoryManager.getInstance(project).repositories.any {
                    canonical(it.root.path) == path
                }
            }
        } ?: throw IOException("Open the repository as an IDE Git root before cleanup")
    }

    private fun cleanupProjectProvider(repositoryDirectory: String, cleanup: Boolean): suspend () -> Project? {
        val mutex = Mutex()
        var project: Result<Project>? = null
        return {
            if (!cleanup) null else mutex.withLock {
                val initialized = project ?: runCatching {
                    cleanupProject(repositoryDirectory).also { WorktreeStatusReader.refresh(it, repositoryDirectory) }
                }.also { project = it }
                initialized.getOrThrow()
            }
        }
    }

    private suspend fun removalPreview(
        repositoryDirectory: String, directory: String, cleanupProject: suspend () -> Project?,
        options: GitWorktrees.RemovalOptions, includeClosedUnmerged: Boolean,
        batchEntries: List<GitWorktree>? = null,
    ): Pair<GitWorktrees.RemovalPlan?, com.google.gson.JsonObject> {
        val supplied = Path.of(directory)
        require(supplied.isAbsolute) { "Worktree directory must be absolute" }
        val path = canonical(directory) ?: supplied.normalize()
        val entries = batchEntries ?: discover(repositoryDirectory)
        removalPreflight(path, entries, options)?.let { return null to it }
        val plan = GitWorktrees.previewRemove(repositoryDirectory, directory, options)
        val project = cleanupProject()
        val currentEntry = (if (project != null && batchEntries == null) discover(repositoryDirectory) else entries)
            .singleOrNull { it.directory == plan.directory }
        val evidence = if (project != null) {
            if (currentEntry != null) WorktreeCleanup.preview(project, repositoryDirectory, currentEntry)
            else CleanupEvidence("BLOCKED", "Cleanup evidence is unavailable", "")
        } else null
        return plan to removalProjection(currentEntry, plan, evidence, includeClosedUnmerged,
            onEdt(ModalityState.any()) { overlapsOpenProject(path) })
    }

    private suspend fun removalPreflight(
        path: Path, entries: List<GitWorktree>, options: GitWorktrees.RemovalOptions,
    ): JsonObject? {
        val entry = entries.singleOrNull { it.directory == path.toString() }
        val open = onEdt(ModalityState.any()) { overlapsOpenProject(path) }
        val blocks = WorktreeRemovalPolicy.blockers(entry, open)
        if (blocks.isNotEmpty()) {
            return gson.toJsonTree(mapOf(
                "directory" to path.toString(), "head" to entry?.head, "branch" to entry?.branch,
                "options" to options, "status" to "blocked", "open" to open,
                "selectable" to false, "safe" to false,
                "blockedCodes" to blocks.keys, "blockedReasons" to blocks.values,
                "cleanupKind" to "NOT_CHECKED", "cleanupDetails" to "Not evaluated because removal is blocked",
            )).asJsonObject
        }
        return null
    }

    private fun removalProjection(
        entry: GitWorktree?, plan: GitWorktrees.RemovalPlan, evidence: CleanupEvidence?,
        includeClosedUnmerged: Boolean, stillOpen: Boolean,
    ): JsonObject {
        val options = plan.options
        val kind = evidence?.kind ?: "NONE"
        val result = gson.toJsonTree(plan).asJsonObject
        val eligibility = WorktreeRemovalPolicy.evaluate(
            entry, stillOpen, GitWorktrees.RemovalBlockReason.STATUS_UNKNOWN !in plan.blockedCodes,
            plan.trackedChanges.isNotEmpty() || plan.untrackedFiles.isNotEmpty(), kind, cleanup = evidence != null,
        )
        val blocks = linkedMapOf<String, String>()
        plan.blockedCodes.zip(plan.blockedReasons).forEach { (code, reason) -> blocks[code.name] = reason }
        eligibility.blocked.forEach { (code, reason) ->
            blocks.putIfAbsent(code, if (code == "CLEANUP_UNPROVEN") evidence?.details ?: reason else reason)
        }
        if (kind == "CLOSED_UNMERGED" && !includeClosedUnmerged) {
            blocks["CLOSED_UNMERGED_OPT_IN"] = "Closed without merging; explicitly select this worktree to opt in"
        }
        val optionalBlocks = setOf("TRACKED_CHANGES", "UNTRACKED_FILES", "CLOSED_UNMERGED_OPT_IN")
        result.add("blockedCodes", gson.toJsonTree(blocks.keys))
        result.add("blockedReasons", gson.toJsonTree(blocks.values))
        result.addProperty("open", stillOpen)
        result.addProperty("cleanupKind", kind)
        result.addProperty("cleanupDetails", evidence?.details ?: "Individually approved removal; no merge classification required")
        result.addProperty("selectable", eligibility.selectable && blocks.keys.all { it in optionalBlocks })
        val allowed = blocks.isEmpty()
        result.addProperty("safe", allowed && eligibility.safe && !options.deleteLocalBranch && !options.deleteLocalChanges)
        result.addProperty("status", if (allowed) "preview" else "blocked")
        val state = listOf(plan.confirmation, evidence?.fingerprint.orEmpty(), kind, includeClosedUnmerged.toString(),
            stillOpen.toString())
        result.addProperty("confirmation", java.security.MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(state).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        return result
    }

    private suspend fun removalResult(directory: String, block: suspend () -> com.google.gson.JsonObject): com.google.gson.JsonObject = try {
        block()
    } catch (error: IOException) {
        gson.toJsonTree(Outcome("removal_failed", directory, reason = error.message)).asJsonObject
    } catch (error: IllegalArgumentException) {
        gson.toJsonTree(Outcome("blocked", directory, reason = error.message)).asJsonObject
    }

    suspend fun worktree_open(
        directory: String,
        merge: Boolean = false,
        targetDirectory: String? = null,
    ): JsonObject = encode(lifecycle.open(directory, merge, targetDirectory))

    suspend fun worktree_merge(
        targetDirectory: String? = null,
    ): JsonObject = encode(lifecycle.merge(targetDirectory))

    suspend fun worktree_close(
        directory: String,
    ): JsonObject {
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        return encode(onEdt {
            val project = findProject(path) ?: return@onEdt Outcome("already_closed", path.toString())
            closeProject(project, path)
        })
    }

    private suspend fun closeProject(project: Project, path: Path): Outcome {
        if (modal()) return Outcome("unavailable_modal", path.toString())
        val closed = writeIntentReadAction { ProjectManager.getInstance().closeAndDispose(project) }
        val stillOpen = ProjectManager.getInstance().openProjects.contains(project)
        return Outcome(if (closed && !stillOpen) "closed" else "close_cancelled", path.toString())
    }

    suspend fun worktree_action(
        actionId: String,
        directory: String,
        filePath: String? = null,
    ): JsonObject {
        if (modalPreflight()) return encode(Outcome("unavailable_modal", actionId = actionId, reason = "modal_dialog"))
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        return withFileContext(target, path, filePath) { file -> encode(invoke(actionId, target, file)) }
    }

    suspend fun worktree_search_actions(
        query: String,
        directory: String,
        limit: Int = 50,
        filePath: String? = null,
    ): JsonObject {
        if (modalPreflight()) return encode(Outcome("unavailable_modal", reason = "modal_dialog"))
        val text = query.trim()
        if (text.isEmpty() || limit !in 1..100) return encode(Outcome("invalid_query"))
        val path = canonical(directory) ?: return encode(Outcome("invalid_directory", directory))
        val target = onEdt { target(findProject(path)) }
            ?: return encode(Outcome("target_not_open", path.toString()))
        return withFileContext(target, path, filePath) { file -> searchActions(text, limit, target, path, file) }
    }

    private suspend fun searchActions(text: String, limit: Int, target: Target, path: Path, file: FileContext?): JsonObject {
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
        return json(mapOf(
            "status" to "ok", "directory" to path.toString(),
            "actions" to actions, "truncated" to (matches.size > limit),
        ))
    }

    internal suspend fun shareWindowGroup(first: Project, second: Project): Boolean {
        if (first === second) return true
        if (!SystemInfoRt.isMac || ApplicationManager.getApplication().isHeadlessEnvironment) return false
        val frames = onEdt { target(first) to target(second) }
        val left = frames.first ?: return false
        val right = frames.second ?: return false
        return native {
            if (!left.available() || !right.available()) return@native false
            val group = Foundation.invoke(MacUtil.getWindowFromJavaWindow(left.frame), "tabGroup")
            group.toLong() != 0L &&
                Foundation.invoke(MacUtil.getWindowFromJavaWindow(right.frame), "tabGroup") == group
        }
    }

    internal suspend fun windowStates(reference: Project): Map<String, String> {
        val projects = onEdt { ProjectManager.getInstance().openProjects.toList() }
        return buildMap {
            for (project in projects) {
                val directory = project.basePath?.let { canonical(it) } ?: continue
                put(directory.toString(), when {
                    project === reference -> "current"
                    shareWindowGroup(reference, project) -> "same_window"
                    else -> "other_window"
                })
            }
        }
    }

    private val lifecycle = ProjectWindowLifecycle(object : ProjectWindowAdapter<Project> {
        override val supportsMerge: Boolean get() = SystemInfoRt.isMac
        override suspend fun canonical(directory: String): String? = this@WorktreeOperations.canonical(directory)?.toString()
        override suspend fun lastTarget(): Project? = onEdt(ModalityState.any()) { this@WorktreeOperations.lastTarget()?.project }
        override suspend fun findProject(directory: String): Project? = onEdt(ModalityState.any()) { this@WorktreeOperations.findProject(Path.of(directory)) }
        override suspend fun available(project: Project): Boolean = onEdt(ModalityState.any()) { project.isOpen && !project.isDisposed }
        override suspend fun targetAvailable(project: Project): Boolean = onEdt(ModalityState.any()) { target(project)?.available() == true }
        override suspend fun modal(): Boolean = modalPreflight()

        override suspend fun open(directory: String): Project? = withContext(Dispatchers.IO) {
            // The public overload initializes fresh/moduleless projects and retains normal trust prompts.
            ProjectUtil.openOrImport(Path.of(directory), null, true)
        }

        override suspend fun focus(project: Project): Boolean {
            if (ApplicationManager.getApplication().isHeadlessEnvironment) return available(project)
            if (modalPreflight()) return false
            val frame = onEdt {
                if (!project.isOpen || project.isDisposed) return@onEdt null
                ProjectUtil.focusProjectWindow(project, true)
                target(project)
            }
            if (frame == null) return false
            if (!SystemInfoRt.isMac) return frame.available()
            return native {
                if (!frame.available()) return@native false
                val window = MacUtil.getWindowFromJavaWindow(frame.frame)
                val group = Foundation.invoke(window, "tabGroup")
                if (group.toLong() == 0L) return@native true
                Foundation.invoke(group, "setSelectedWindow:", window)
                Foundation.invoke(group, "selectedWindow") == window
            }
        }

        override suspend fun windows(): List<Project> = onEdt(ModalityState.any()) {
            ProjectManager.getInstance().openProjects.filter { target(it)?.available() == true }
        }

        override suspend fun mergeState(target: Project, windows: List<Project>, select: Boolean): MergeState {
            val destination = onEdt(ModalityState.any()) { this@WorktreeOperations.target(target)?.takeIf { it.available() } }
                ?: return MergeState.TARGET_UNAVAILABLE
            val frames = onEdt(ModalityState.any()) { windows.map { this@WorktreeOperations.target(it)?.takeIf { frame -> frame.available() } } }
            if (frames.any { it == null }) return MergeState.WINDOW_UNAVAILABLE
            return native {
                if (!destination.available()) return@native MergeState.TARGET_UNAVAILABLE
                if (frames.any { it?.available() != true }) return@native MergeState.WINDOW_UNAVAILABLE
                val window = MacUtil.getWindowFromJavaWindow(destination.frame)
                val group = Foundation.invoke(window, "tabGroup")
                if (group.toLong() == 0L || frames.any {
                        Foundation.invoke(MacUtil.getWindowFromJavaWindow(it!!.frame), "tabGroup") != group
                    }) return@native MergeState.PENDING
                if (select) Foundation.invoke(group, "setSelectedWindow:", window)
                if (!select || Foundation.invoke(group, "selectedWindow") == window) MergeState.MERGED else MergeState.PENDING
            }
        }

        override suspend fun dispatchMerge(target: Project): Outcome {
            val destination = onEdt { this@WorktreeOperations.target(target) } ?: return Outcome("target_unavailable")
            return invoke("MergeAllWindowsAction", destination)
        }
    })

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
        CustomizedDataContext.withSnapshot(DataManager.getInstance().getDataContext(component)) { sink ->
            sink[CommonDataKeys.PROJECT] = project
            // Null snapshot values are ignored; explicit nulls prevent selected-editor fallback.
            if (file == null) {
                sink.setNull(CommonDataKeys.VIRTUAL_FILE)
                sink.setNull(CommonDataKeys.VIRTUAL_FILE_ARRAY)
                sink.setNull(CommonDataKeys.PSI_FILE)
                sink.setNull(CommonDataKeys.PSI_ELEMENT)
                sink.setNull(CommonDataKeys.EDITOR)
                sink.setNull(CommonDataKeys.HOST_EDITOR)
                sink.setNull(CommonDataKeys.EDITOR_EVEN_IF_INACTIVE)
                sink.setNull(CommonDataKeys.CARET)
                sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)
            } else {
                sink[CommonDataKeys.VIRTUAL_FILE] = file.file
                sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = arrayOf(file.file)
                sink[CommonDataKeys.PSI_FILE] = file.psi
                sink[CommonDataKeys.PSI_ELEMENT] = file.psi
                sink[CommonDataKeys.EDITOR] = file.editor
                sink[CommonDataKeys.HOST_EDITOR] = file.editor
                sink[CommonDataKeys.EDITOR_EVEN_IF_INACTIVE] = file.editor
                sink[CommonDataKeys.CARET] = file.editor.caretModel.currentCaret
                sink[PlatformCoreDataKeys.FILE_EDITOR] = file.fileEditor
            }
        }

    private suspend fun withFileContext(
        target: Target, directory: Path, filePath: String?, block: suspend (FileContext?) -> JsonObject,
    ): JsonObject {
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

    internal suspend fun overlapsOpenProject(path: Path): Boolean {
        // Closing projects leave openProjects before their stores and components are disposed.
        val paths = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath } +
            WorktreeRemovalProjectListener.closingPaths.values
        return paths.any { directory ->
            val base = canonical(directory) ?: Path.of(directory).toAbsolutePath().normalize()
            base.startsWith(path) || path.startsWith(base)
        }
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

    private suspend fun <T> onEdt(modality: ModalityState = ModalityState.nonModal(), block: suspend () -> T): T =
        withContext(Dispatchers.EDT + modality.asContextElement()) { block() }

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

    private fun encode(outcome: Outcome): JsonObject = json(outcome)

    private fun json(value: Any): JsonObject = gson.toJsonTree(value).asJsonObject
}

internal class WorktreeRemovalProjectListener : ProjectCloseListener, ProjectLifecycleListener {
    companion object {
        internal val closingPaths = java.util.concurrent.ConcurrentHashMap<Project, String>()
    }

    override fun projectClosing(project: Project) {
        project.basePath?.let { closingPaths[project] = it }
    }

    override fun projectClosed(project: Project) = projectClosing(project)

    override fun afterProjectClosed(project: Project) {
        closingPaths.remove(project)
    }
}
