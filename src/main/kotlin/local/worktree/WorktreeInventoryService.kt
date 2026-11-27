package local.worktree

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal data class WorktreeStatusSnapshot(
    val entry: GitWorktree,
    val status: WorktreeDisplayStatus? = null,
    val failure: String? = null,
    val localLoading: Boolean = false,
)

internal data class WorktreeInventorySnapshot(
    val entries: List<GitWorktree>,
    val error: String?,
    val version: Long,
    val rows: List<WorktreeStatusSnapshot> = entries.map { WorktreeStatusSnapshot(it) },
    val refreshing: Boolean = false,
    val refreshFailure: String? = null,
) {
    fun removalCandidates(projectDirectories: Collection<String>): List<WorktreeRemovalCandidate> = rows.map { row ->
        WorktreeRemovalCandidate(row.entry, row.status, WorktreeRemovalPolicy.overlaps(row.entry.directory, projectDirectories), row.failure ?: error)
    }
}

@Service(Service.Level.APP)
internal class WorktreeInventoryService(private val scope: CoroutineScope) {
    private val inventories = mutableMapOf<Path, WorktreeInventory>()
    private val mutex = Mutex()

    suspend fun inventory(repository: String): WorktreeInventory {
        val common = withContext(Dispatchers.IO) {
            val supplied = Path.of(repository)
            require(supplied.isAbsolute) { "Repository directory must be absolute" }
            val directory = supplied.toRealPath()
            require(Files.isDirectory(directory)) { "Repository path is not a directory: $repository" }
            Path.of(GitWorktrees.git(directory, "rev-parse", "--path-format=absolute", "--git-common-dir")
                .removeSuffix("\n")).toRealPath()
        }
        return mutex.withLock {
            inventories.getOrPut(common) {
                // The common directory survives removal of the checkout that first opened a panel.
                WorktreeInventory(scope, { GitWorktrees.discover(common.toString()) }) { changed ->
                    watchInventory(common, changed)
                }
            }
        }
    }
}

internal class WorktreeInventory(
    scope: CoroutineScope,
    private val discover: suspend () -> List<GitWorktree>,
    private val watch: suspend (() -> Unit) -> AutoCloseable,
) {
    private val state = MutableStateFlow<WorktreeInventorySnapshot?>(null)
    val snapshots: StateFlow<WorktreeInventorySnapshot?> = state.asStateFlow()
    private val requested = AtomicLong()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val fetchRequested = AtomicBoolean()
    @Volatile private var statusContext: Pair<Project, String>? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in changes) {
                while ((state.value?.version ?: 0) < requested.get()) {
                    // A Git worktree operation writes several metadata files; read once per burst.
                    delay(75)
                    val version = requested.get()
                    val context = statusContext?.takeUnless { it.first.isDisposed }
                    val snapshot = try {
                        val entries = discover()
                        val previous = state.value?.rows.orEmpty().associateBy { WorktreeIdentity(it.entry) }
                        WorktreeInventorySnapshot(entries, null, version, entries.map { entry ->
                            previous[WorktreeIdentity(entry)]?.copy(entry = entry, failure = null, localLoading = context != null)
                                ?: WorktreeStatusSnapshot(entry, localLoading = context != null)
                        }, refreshing = context != null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cancelled: ProcessCanceledException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failure = error.message ?: error.javaClass.simpleName
                        WorktreeInventorySnapshot(state.value?.entries.orEmpty(), failure, version,
                            state.value?.rows.orEmpty().map { it.copy(failure = failure, localLoading = false) })
                    }
                    state.value = snapshot
                    if (snapshot.error == null && context != null) readStatuses(context.first, context.second, fetchRequested.getAndSet(false))
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (observed) {
                    val registration = watch { requestRefresh() }
                    try {
                        requestRefresh() // Reconcile changes made while there were no observers.
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { registration.close() }
                    }
                }
            }
        }
        requestRefresh()
    }

    suspend fun refresh(): WorktreeInventorySnapshot {
        val version = requestRefresh()
        return snapshots.first { it != null && it.version >= version }!!
    }

    suspend fun current(project: Project? = null, repositoryDirectory: String? = null): WorktreeInventorySnapshot {
        if (project != null && repositoryDirectory != null) {
            synchronized(this) {
                if (statusContext == null || statusContext!!.first.isDisposed) {
                    statusContext = project to repositoryDirectory
                    requestRefresh()
                    project.messageBus.connect(project).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
                        if (state.value?.entries?.any { it.directory == repository.root.path } == true) requestRefresh()
                    })
                }
            }
        }
        return state.value ?: snapshots.first { it != null }!!
    }

    suspend fun refreshStatus(project: Project, repositoryDirectory: String, fetch: Boolean = false): WorktreeInventorySnapshot {
        current(project, repositoryDirectory)
        if (fetch) fetchRequested.set(true)
        val version = requestRefresh()
        return snapshots.first { it != null && it.version >= version && !it.refreshing }!!
    }

    fun removalCandidates(projectDirectories: Collection<String>): List<WorktreeRemovalCandidate> =
        state.value?.removalCandidates(projectDirectories).orEmpty()

    private suspend fun readStatuses(project: Project, repository: String, fetch: Boolean) {
        val permits = Semaphore(4)
        fun update(index: Int, transform: (WorktreeStatusSnapshot) -> WorktreeStatusSnapshot) {
            synchronized(this) {
                val current = state.value ?: return
                state.value = current.copy(rows = current.rows.mapIndexed { rowIndex, row -> if (rowIndex == index) transform(row) else row })
            }
        }
        suspend fun local(): List<WorktreeLocalStatus> = coroutineScope {
            state.value!!.entries.mapIndexed { index, entry ->
                async {
                    permits.withPermit {
                        val local = WorktreeStatusReader.readLocal(entry)
                        update(index) { row ->
                            row.copy(status = if (local.status.reviewLoading) WorktreeStatusReader.retainReview(local.status, row.status) else local.status,
                                failure = null, localLoading = false)
                        }
                        local
                    }
                }
            }.awaitAll()
        }
        var failure: String? = null
        try {
            var locals = local()
            // All local snapshots are published before credentials or provider/network work starts.
            if (fetch) {
                try { WorktreeStatusReader.refresh(project, repository) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cancelled: ProcessCanceledException) { throw cancelled }
                catch (error: Exception) { failure = "Fetch failed: ${error.message ?: "Remote refresh failed"}" }
                if (failure == null) locals = local()
            }
            coroutineScope {
                locals.mapIndexed { index, local ->
                    async {
                        permits.withPermit {
                            val status = WorktreeStatusReader.readReview(project, repository, local)
                            update(index) { row ->
                                row.copy(status = if (status.reviewError != null) WorktreeStatusReader.retainReview(status, row.status) else status)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (cancelled: CancellationException) {
            failure = "Status refresh cancelled; Refresh retries."
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            failure = "Status refresh cancelled; Refresh retries."
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        } finally {
            synchronized(this) {
                state.value = state.value!!.let { current ->
                    current.copy(refreshing = false, refreshFailure = failure, rows = current.rows.map { row ->
                        row.copy(localLoading = false,
                            failure = row.failure ?: failure.takeIf { row.status == null },
                            status = row.status?.let {
                                if (it.reviewLoading) it.copy(reviewLoading = false, reviewError = failure ?: "Review refresh interrupted; Refresh retries.") else it
                            })
                    })
                }
            }
        }
    }

    private fun requestRefresh(): Long {
        val version = requested.incrementAndGet()
        changes.trySend(Unit)
        return version
    }
}

private suspend fun watchInventory(common: Path, changed: () -> Unit): AutoCloseable {
    val fileSystem = LocalFileSystem.getInstance()
    val connection = ApplicationManager.getApplication().messageBus.connect()
    val roots = mutableListOf<LocalFileSystem.WatchRequest>()
    val worktrees = common.resolve("worktrees")
    val refs = common.resolve("refs")
    fun relevant(path: String): Boolean {
        val candidate = Path.of(path).normalize()
        return common.startsWith(candidate) || candidate.startsWith(worktrees) || candidate.startsWith(refs) ||
            candidate.parent == common
    }
    connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            var affected = false
            for (event in events) {
                if (event.fileSystem != fileSystem) continue
                val matches = when (event) {
                    is VFileMoveEvent -> relevant(event.oldPath) || relevant(event.newPath)
                    is VFilePropertyChangeEvent -> relevant(event.oldPath) || relevant(event.newPath)
                    else -> relevant(event.path)
                }
                if (!matches) continue
                affected = true
                // Newly created metadata directories were not in the VFS's cached child listings.
                if (event is VFileCreateEvent && event.isDirectory) {
                    val path = Path.of(event.path)
                    if (path.startsWith(worktrees) || path.startsWith(refs)) {
                        event.file?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
                    }
                }
            }
            if (affected) changed()
        }
    })
    try {
        // The flat parent watch catches creation of worktrees/ before the first linked checkout.
        // Explicit roots also cover common Git directories outside every open project's content roots.
        fileSystem.addRootToWatch(common.toString(), false)?.let(roots::add)
        roots.addAll(fileSystem.addRootsToWatch(listOf(worktrees.toString(), refs.toString()), true))
        val root = fileSystem.refreshAndFindFileByNioFile(common)
        if (root != null) {
            readAction { root.children } // Cache the parent listing, including a currently absent worktrees/.
            for (directory in listOf(worktrees, refs)) {
                val metadata = fileSystem.refreshAndFindFileByNioFile(directory) ?: continue
                readAction { VfsUtilCore.iterateChildrenRecursively(metadata, null) { true } }
            }
        }
        return AutoCloseable {
            connection.disconnect()
            fileSystem.removeWatchedRoots(roots)
        }
    } catch (error: Throwable) {
        connection.disconnect()
        fileSystem.removeWatchedRoots(roots)
        throw error
    }
}
