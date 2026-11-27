package local.worktree

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

internal data class LifecycleOutcome(
    val status: String,
    val directory: String? = null,
    val actionId: String? = null,
    val merge: LifecycleOutcome? = null,
    val reason: String? = null,
)

internal enum class MergeState { MERGED, PENDING, TARGET_UNAVAILABLE, WINDOW_UNAVAILABLE }

/** Only platform effects live here; the orchestration below also runs against fake windows. */
internal interface ProjectWindowAdapter<P : Any> {
    val supportsMerge: Boolean
    suspend fun canonical(directory: String): String?
    suspend fun lastTarget(): P?
    suspend fun findProject(directory: String): P?
    suspend fun available(project: P): Boolean
    suspend fun targetAvailable(project: P): Boolean
    suspend fun modal(): Boolean
    suspend fun open(directory: String): P?
    suspend fun focus(project: P): Boolean
    suspend fun windows(): List<P>
    suspend fun mergeState(target: P, windows: List<P>, select: Boolean): MergeState
    suspend fun dispatchMerge(target: P): LifecycleOutcome
}

internal class ProjectWindowLifecycle<P : Any>(
    private val ide: ProjectWindowAdapter<P>,
    private val mergeTimeoutMillis: Long = 5_000,
    private val pollMillis: Long = 50,
) {
    companion object {
        // ponytail: one IDE owns all native windows; reject repeats instead of queuing stale user intent.
        internal val operation = Mutex()
        internal val busy: Boolean get() = operation.isLocked
    }

    suspend fun open(directory: String, merge: Boolean = false, targetDirectory: String? = null): LifecycleOutcome = guarded(directory) {
        val captured = ide.lastTarget()
        val path = ide.canonical(directory) ?: return@guarded LifecycleOutcome("invalid_directory", directory)
        val target = if (merge && targetDirectory != null) {
            val targetPath = ide.canonical(targetDirectory)
                ?: return@guarded LifecycleOutcome("invalid_target_directory", targetDirectory)
            ide.findProject(targetPath)?.takeIf { ide.targetAvailable(it) }
                ?: return@guarded LifecycleOutcome("target_not_open", targetDirectory)
        } else captured
        if (ide.modal()) return@guarded LifecycleOutcome("unavailable_modal", path)
        val existing = ide.findProject(path)
        val project = existing ?: ide.open(path)
        if (project == null || !ide.available(project)) return@guarded LifecycleOutcome("open_cancelled", path)
        val merged = if (!merge) null else if (target == null) LifecycleOutcome("not_needed") else mergeTarget(target)
        if (!ide.focus(project)) return@guarded LifecycleOutcome("target_unavailable", path, merge = merged)
        LifecycleOutcome(if (existing == null) "opened" else "reused", path, merge = merged)
    }

    suspend fun merge(targetDirectory: String? = null): LifecycleOutcome = guarded(targetDirectory) {
        val captured = ide.lastTarget()
        val target = if (targetDirectory == null) captured else {
            val path = ide.canonical(targetDirectory)
                ?: return@guarded LifecycleOutcome("invalid_target_directory", targetDirectory)
            ide.findProject(path)?.takeIf { ide.targetAvailable(it) }
                ?: return@guarded LifecycleOutcome("target_not_open", targetDirectory)
        }
        if (target == null) return@guarded LifecycleOutcome(if (ide.windows().size < 2) "not_needed" else "no_target")
        mergeTarget(target)
    }

    private suspend fun mergeTarget(target: P): LifecycleOutcome {
        if (!ide.supportsMerge) return LifecycleOutcome("unsupported_platform")
        if (!ide.targetAvailable(target)) return LifecycleOutcome("target_unavailable")
        if (ide.modal()) return LifecycleOutcome("unavailable_modal", reason = "modal_dialog")
        val windows = ide.windows()
        if (windows.size < 2) return LifecycleOutcome("not_needed")
        when (ide.mergeState(target, windows, false)) {
            MergeState.MERGED -> return LifecycleOutcome("not_needed")
            MergeState.TARGET_UNAVAILABLE -> return LifecycleOutcome("target_unavailable")
            MergeState.WINDOW_UNAVAILABLE -> return LifecycleOutcome("merge_pending", reason = "window_unavailable")
            MergeState.PENDING -> Unit
        }
        val dispatched = ide.dispatchMerge(target)
        if (dispatched.status != "dispatched") return dispatched
        return withTimeoutOrNull(mergeTimeoutMillis) {
            while (true) {
                currentCoroutineContext().ensureActive()
                when (ide.mergeState(target, windows, true)) {
                    MergeState.MERGED -> return@withTimeoutOrNull LifecycleOutcome("merged", dispatched.directory)
                    MergeState.TARGET_UNAVAILABLE -> return@withTimeoutOrNull LifecycleOutcome("target_unavailable")
                    MergeState.WINDOW_UNAVAILABLE -> return@withTimeoutOrNull LifecycleOutcome("merge_pending", reason = "window_unavailable")
                    MergeState.PENDING -> delay(pollMillis)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        } ?: LifecycleOutcome("merge_pending", dispatched.directory, reason = "merge_timeout")
    }

    private suspend fun guarded(directory: String?, block: suspend () -> LifecycleOutcome): LifecycleOutcome {
        if (!operation.tryLock()) return LifecycleOutcome("operation_in_progress", directory?.let { ide.canonical(it) } ?: directory)
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            operation.unlock()
        }
    }
}
