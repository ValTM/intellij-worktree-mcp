package local.worktree

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BinaryOSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import git4idea.config.GitExecutableManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal data class GitWorktree(
    val directory: String,
    val head: String?,
    val branch: String?,
    val main: Boolean,
    val detached: Boolean,
    val locked: String?,
    val prunable: String?,
    val bare: Boolean = false,
)

internal object GitWorktrees {
    internal enum class RemovalBlockReason {
        REPOSITORY_IDENTITY_UNKNOWN,
        INVENTORY_UNKNOWN,
        MAIN_WORKTREE,
        BARE_REPOSITORY,
        LOCKED,
        PRUNABLE,
        FOREIGN_WORKTREE,
        TARGET_IDENTITY_UNKNOWN,
        REPOSITORY_IDENTITY_MISMATCH,
        STATUS_UNKNOWN,
        HISTORY_UNKNOWN,
        TRACKED_CHANGES,
        UNTRACKED_FILES,
        IGNORED_FILES,
        REMOTE_BRANCH_UNKNOWN,
        REMOTE_BRANCH_PRESENT,
        BRANCH_REQUIRED,
        BRANCH_CHECKED_OUT,
        UNRETAINED_COMMITS,
        OPEN_PROJECT,
    }

    internal data class RemovalOptions(
        val deleteLocalBranch: Boolean = false,
        val deleteLocalChanges: Boolean = false,
    )

    internal data class RemovalPlan(
        val repositoryDirectory: String,
        val directory: String,
        val head: String?,
        val branch: String?,
        val options: RemovalOptions,
        val trackedChanges: List<String>,
        val untrackedFiles: List<String>,
        val ignoredFiles: List<String>,
        val remoteBranchAbsent: Boolean,
        val remoteBranchDetails: String,
        val unretainedCommitIds: List<String>,
        val unpushedCommits: Int?,
        val historyKnown: Boolean,
        val unsafeCommits: Int?,
        val blockedReasons: List<String>,
        val blockedCodes: List<RemovalBlockReason>,
        val confirmation: String,
    )

    internal class RemovalException(directory: String, branch: String, cause: Exception) : IOException(
        "Worktree was removed at $directory, but deletion of local branch '$branch' did not complete: ${cause.message}. " +
            "Inspect repository state before retrying; no remote branch was deleted.",
        cause,
    )

    internal data class CreationPlan(
        val repositoryDirectory: String,
        val directory: String,
        val branch: String,
        val startRef: String,
        val newBranch: Boolean,
        val head: String,
        val confirmation: String,
    )

    internal class CreationException(
        val directory: String,
        val branch: String,
        cause: Exception,
        val destinationExists: Boolean = Files.exists(Path.of(directory), NOFOLLOW_LINKS),
        val worktreeMetadataExists: Boolean = Files.exists(Path.of(directory).resolve(".git"), NOFOLLOW_LINKS),
    ) : IOException(
        "Creation did not complete at $directory: ${cause.message}. " +
            "Destination exists: $destinationExists; worktree metadata exists: $worktreeMetadataExists. " +
            "Branch '$branch' may still exist. No plugin rollback was performed; inspect remaining state before retrying.",
        cause,
    )

    suspend fun discover(repositoryDirectory: String): List<GitWorktree> = withContext(Dispatchers.IO) {
        parse(git(repositoryPath(repositoryDirectory), "worktree", "list", "--porcelain", "-z"))
    }

    suspend fun previewCreate(
        repositoryDirectory: String,
        directory: String,
        branch: String,
        startRef: String,
        newBranch: Boolean = false,
    ): CreationPlan = withContext(Dispatchers.IO) {
        val repository = repositoryPath(repositoryDirectory)
        val commonDirectory = Path.of(git(repository, "rev-parse", "--path-format=absolute", "--git-common-dir").removeSuffix("\n")).toRealPath()
        val commonIdentity = Files.readAttributes(commonDirectory, BasicFileAttributes::class.java).fileKey()?.toString().orEmpty()
        val destination = destinationPath(directory)
        require(branch.isNotEmpty() && !branch.startsWith("-") && !branch.startsWith("@{")) {
            "Supply an explicit local branch name"
        }
        git(repository, "check-ref-format", "--branch", branch)
        require(startRef.isNotBlank()) { "Starting ref must be explicit" }
        val head = git(repository, "rev-parse", "--verify", "--end-of-options", "$startRef^{commit}").trim()
        val branchRef = "refs/heads/$branch"
        val exists = git(repository, "for-each-ref", "--format=%(refname)", branchRef)
            .lineSequence().any { it == branchRef }
        if (newBranch) {
            require(!exists) { "Branch already exists: $branch" }
        } else {
            require(exists) { "Local branch does not exist: $branch" }
            val branchHead = git(repository, "rev-parse", "--verify", "--end-of-options", "$branchRef^{commit}").trim()
            require(head == branchHead) { "Starting ref must resolve to the existing branch tip; branches are never reset" }
        }
        val inventory = parse(git(repository, "worktree", "list", "--porcelain", "-z"))
        require(inventory.none { it.directory == destination.toString() }) { "Destination is already registered as a worktree: $destination" }
        require(inventory.none { it.branch == branch }) { "Branch is already checked out in another worktree: $branch" }
        val fields = listOf(repository.toString(), commonDirectory.toString(), commonIdentity, destination.toString(), branch, startRef, newBranch.toString(), head)
        // ponytail: a stateless digest binds approval to the resolved plan, not a token registry.
        val confirmation = MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        CreationPlan(repository.toString(), destination.toString(), branch, startRef, newBranch, head, confirmation)
    }

    suspend fun create(plan: CreationPlan, confirmation: String): String = withContext(Dispatchers.IO) {
        require(confirmation.isNotEmpty() && confirmation == plan.confirmation) { "Confirmation does not match the approved creation preview" }
        val current = previewCreate(plan.repositoryDirectory, plan.directory, plan.branch, plan.startRef, plan.newBranch)
        require(current == plan) { "Creation state changed; obtain and approve a new preview" }
        currentCoroutineContext().ensureActive()
        val destination = Path.of(plan.directory)
        Files.createDirectories(destination.parent)
        // Git accepts an empty directory. Claim it atomically ourselves so even an existing empty
        // directory or dangling symlink can never be mistaken for an approved nonexistent path.
        Files.createDirectory(destination)
        try {
            val arguments = mutableListOf("worktree", "add")
            if (plan.newBranch) arguments.addAll(listOf("-b", plan.branch))
            arguments.addAll(listOf("--", plan.directory, if (plan.newBranch) plan.head else plan.branch))
            git(Path.of(plan.repositoryDirectory), *arguments.toTypedArray())
            val createdHead = git(destination, "rev-parse", "--verify", "HEAD").trim()
            check(createdHead == plan.head) { "Branch changed while Git was creating the worktree" }
            val createdBranch = git(destination, "symbolic-ref", "--quiet", "HEAD").trim()
            check(createdBranch == "refs/heads/${plan.branch}") { "Git did not attach the approved local branch" }
            destination.toRealPath().toString()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cancelled.addSuppressed(CreationException(plan.directory, plan.branch, cancelled))
            throw cancelled
        } catch (error: Exception) {
            throw CreationException(plan.directory, plan.branch, error)
        }
    }

    suspend fun previewRemove(
        repositoryDirectory: String,
        directory: String,
        options: RemovalOptions = RemovalOptions(),
    ): RemovalPlan = withContext(Dispatchers.IO) {
        val repository = repositoryPath(repositoryDirectory)
        val supplied = Path.of(directory)
        require(supplied.isAbsolute) { "Worktree directory must be absolute" }
        val target = try {
            supplied.toRealPath()
        } catch (_: NoSuchFileException) {
            supplied.normalize()
        }
        val blocked = mutableListOf<String>()
        val blockedCodes = mutableListOf<RemovalBlockReason>()
        fun block(code: RemovalBlockReason, reason: String) {
            blockedCodes.add(code)
            blocked.add(reason)
        }
        val state = mutableListOf(repository.toString(), target.toString(),
            options.deleteLocalBranch.toString(), options.deleteLocalChanges.toString())
        suspend fun observe(
            description: String,
            failureCode: RemovalBlockReason,
            captureState: Boolean = true,
            operation: suspend () -> String,
        ): String? = try {
            operation().also { if (captureState) state.add(it) }
        } catch (error: IOException) {
            block(failureCode, "$description is unknown: ${error.message}")
            null
        }
        val common = observe("Repository identity", RemovalBlockReason.REPOSITORY_IDENTITY_UNKNOWN) {
            val path = Path.of(git(repository, "rev-parse", "--path-format=absolute", "--git-common-dir").removeSuffix("\n")).toRealPath()
            state.add(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()?.toString().orEmpty())
            path.toString()
        }
        val inventory = observe("Worktree inventory", RemovalBlockReason.INVENTORY_UNKNOWN, captureState = false) {
            git(repository, "worktree", "list", "--porcelain", "-z")
        }
        val entries = if (inventory == null) emptyList() else try {
            parse(inventory)
        } catch (error: IOException) {
            block(RemovalBlockReason.INVENTORY_UNKNOWN, "Worktree inventory is unknown: ${error.message}")
            emptyList()
        }
        val entry = entries.singleOrNull { it.directory == target.toString() }
        // Other linked worktrees may be removed by this batch without changing this approval.
        entry?.let {
            state.addAll(listOf(it.head.orEmpty(), it.branch.orEmpty(), it.main.toString(), it.detached.toString(),
                it.bare.toString(), it.locked?.let { reason -> "locked:$reason" }.orEmpty(),
                it.prunable?.let { reason -> "prunable:$reason" }.orEmpty()))
        }
        if (entry == null) block(RemovalBlockReason.FOREIGN_WORKTREE, "Target is not a registered worktree of this repository")
        if (entry?.main == true) block(RemovalBlockReason.MAIN_WORKTREE, "The main worktree cannot be removed")
        if (entry?.bare == true) block(RemovalBlockReason.BARE_REPOSITORY, "Bare repositories cannot be removed")
        if (entry?.locked != null) block(RemovalBlockReason.LOCKED, "Worktree is locked: ${entry.locked}")
        if (entry?.prunable != null) block(RemovalBlockReason.PRUNABLE, "Worktree is prunable: ${entry.prunable}")
        if (options.deleteLocalBranch) {
            if (entry?.branch == null) block(RemovalBlockReason.BRANCH_REQUIRED, "A detached worktree has no local branch to delete")
            val otherCheckouts = entries.filter { it.branch != null && it.branch == entry?.branch && it.directory != target.toString() }
            state.addAll(otherCheckouts.map { it.directory })
            if (otherCheckouts.isNotEmpty()) {
                block(RemovalBlockReason.BRANCH_CHECKED_OUT, "Local branch is checked out in another worktree: ${otherCheckouts.joinToString { it.directory }}")
            }
        }
        var remoteBranchAbsent = false
        var remoteStateKnown = false
        var remoteBranchDetails = "No attached local branch"
        if (entry?.branch != null) {
            remoteBranchDetails = "Remote branch absence could not be verified"
            try {
                val config = git(repository, "config", "--null", "--list").split('\u0000').filter { it.isNotEmpty() }.map {
                    val separator = it.indexOf('\n')
                    if (separator < 0) it to "" else it.substring(0, separator) to it.substring(separator + 1)
                }
                val branchSection = "branch.${entry.branch}"
                val branchPrefix = "$branchSection."
                state.addAll(config.filter { (key, _) -> key.substringBeforeLast('.') == branchSection ||
                    key.startsWith("remote.") || key.startsWith("url.") }
                    .map { (key, value) -> "$key\n$value" })
                val remotes = git(repository, "remote").lineSequence().filter { it.isNotEmpty() }.sorted().toList()
                state.add(remotes.joinToString("\u0000"))
                val upstreamRemotes = config.filter { it.first == "${branchPrefix}remote" }.map { it.second }
                val upstreamRefs = config.filter { it.first == "${branchPrefix}merge" }.map { it.second }
                val queries = if (upstreamRemotes.isNotEmpty() || upstreamRefs.isNotEmpty()) {
                    val remote = upstreamRemotes.singleOrNull() ?: throw IOException("Ambiguous upstream remote")
                    val ref = upstreamRefs.singleOrNull() ?: throw IOException("Ambiguous upstream ref")
                    if (remote == ".") {
                        // A local upstream is not evidence about any remote branch.
                        remotes.map { it to "refs/heads/${entry.branch}" }
                    } else {
                        if (remote !in remotes) throw IOException("Upstream remote is not configured")
                        if (!ref.startsWith("refs/heads/")) throw IOException("Upstream is not a remote branch")
                        git(repository, "check-ref-format", ref)
                        listOf(remote to ref)
                    }
                } else {
                    remotes.map { it to "refs/heads/${entry.branch}" }
                }
                if (queries.isEmpty()) {
                    remoteBranchDetails = "No configured remotes; remote branch absence cannot be verified"
                } else {
                    val details = mutableListOf<String>()
                    var absent = true
                    for ((remote, ref) in queries) {
                        state.add(remote)
                        state.add(ref)
                        remoteBranchDetails = "Remote branch absence could not be verified for $remote: $ref"
                        state.add(git(repository, "remote", "get-url", "--all", "--", remote))
                        val output = git(repository, "ls-remote", "--refs", "--", remote, ref)
                        state.add(output)
                        val heads = output.lineSequence().filter { it.isNotEmpty() }.map { line ->
                            val fields = line.split('\t')
                            if (fields.size != 2 || fields[1] != ref || (fields[0].length != 40 && fields[0].length != 64) ||
                                fields[0].any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                                throw IOException("Remote returned an invalid branch advertisement")
                            }
                            fields[0]
                        }.toList()
                        if (heads.size > 1) throw IOException("Remote returned duplicate branch advertisements")
                        if (heads.isNotEmpty()) absent = false
                        details.add("$remote: $ref ${heads.singleOrNull()?.let { "exists at $it" } ?: "is absent"}")
                    }
                    remoteBranchAbsent = absent
                    remoteStateKnown = true
                    remoteBranchDetails = details.joinToString("; ")
                }
            } catch (_: IOException) {
                // An offline remote must not prevent the default operation, which retains the branch.
                state.add("remote-absence:unknown")
            }
            if (options.deleteLocalBranch && !remoteBranchAbsent) {
                val code = if (remoteStateKnown) RemovalBlockReason.REMOTE_BRANCH_PRESENT else RemovalBlockReason.REMOTE_BRANCH_UNKNOWN
                block(code, "Local branch deletion requires freshly verified remote branch absence: $remoteBranchDetails")
            }
        }
        state.add(remoteBranchAbsent.toString())
        state.add(remoteBranchDetails)
        val tracked = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        val changedPaths = mutableSetOf<String>()
        var unretained = emptyList<String>()
        var unpushed: Int? = null
        var unsafe: Int? = null
        var historyKnown = false
        if (entry != null && !entry.bare && entry.prunable == null) {
            val targetCommon = observe("Target repository identity", RemovalBlockReason.TARGET_IDENTITY_UNKNOWN) {
                state.add(Files.readAttributes(target, BasicFileAttributes::class.java).fileKey()?.toString().orEmpty())
                Path.of(git(target, "rev-parse", "--path-format=absolute", "--git-common-dir").removeSuffix("\n")).toRealPath().toString()
            }
            if (targetCommon != common || common == null) {
                block(RemovalBlockReason.REPOSITORY_IDENTITY_MISMATCH, "Target repository identity does not match the requested repository")
            }
            val status = observe("Worktree status", RemovalBlockReason.STATUS_UNKNOWN) {
                git(target, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignored=matching")
            }
            if (status != null) {
                val records = status.split('\u0000')
                var index = 0
                while (index < records.lastIndex) {
                    val record = records[index++]
                    if (record.length < 4 || record[2] != ' ') {
                        block(RemovalBlockReason.STATUS_UNKNOWN, "Worktree status is unknown: malformed Git output")
                        break
                    }
                    val code = record.substring(0, 2)
                    val path = record.substring(3)
                    when (code) {
                        "??" -> untracked.add(path)
                        "!!" -> ignored.add(path)
                        else -> tracked.add(record)
                    }
                    changedPaths.add(path)
                    if ('R' in code || 'C' in code) {
                        if (index >= records.lastIndex) {
                            block(RemovalBlockReason.STATUS_UNKNOWN, "Worktree status is unknown: incomplete rename")
                            break
                        }
                        val source = records[index++]
                        tracked.add(source)
                        changedPaths.add(source)
                    }
                }
                if (status.isNotEmpty() && !status.endsWith('\u0000')) {
                    block(RemovalBlockReason.STATUS_UNKNOWN, "Worktree status is unknown: incomplete Git output")
                }
                observe("Worktree contents", RemovalBlockReason.STATUS_UNKNOWN) {
                    // Index object IDs bind staged bytes as well as the on-disk bytes below.
                    state.add(git(target, "ls-files", "--stage", "-z"))
                    val contents = sortedSetOf<Path>()
                    val ignoredRoots = ignored.toList()
                    ignored.clear()
                    for (name in changedPaths) {
                        val path = target.resolve(name).normalize()
                        if (!path.startsWith(target) || path == target) throw IOException("Git returned an unsafe file path")
                        var parent = path.parent
                        while (parent != target) {
                            if (Files.isSymbolicLink(parent)) throw IOException("Changed path traverses a symlink")
                            parent = parent.parent
                        }
                        val paths = if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                            Files.walk(path).use { paths ->
                                try {
                                    paths.toList()
                                } catch (error: java.io.UncheckedIOException) {
                                    throw IOException("Cannot inspect directory: ${target.relativize(path)}", error)
                                }
                            }
                        } else listOf(path)
                        contents.addAll(paths)
                        if (name in ignoredRoots) {
                            ignored.addAll(paths.map {
                                target.relativize(it).toString() + if (Files.isDirectory(it, NOFOLLOW_LINKS)) "/" else ""
                            })
                        }
                    }
                    ignored.sort()
                    val digest = MessageDigest.getInstance("SHA-256")
                    fun record(value: String) {
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
                        digest.update(bytes)
                    }
                    val buffer = ByteArray(8192)
                    for (path in contents) {
                        record(target.relativize(path).toString())
                        val attributes = try {
                            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        } catch (_: NoSuchFileException) {
                            record("missing")
                            continue
                        }
                        when {
                            attributes.isSymbolicLink -> {
                                record("symlink")
                                record(Files.readSymbolicLink(path).toString())
                            }
                            attributes.isDirectory -> record("directory")
                            attributes.isRegularFile -> {
                                record("file:${attributes.size()}")
                                var size = 0L
                                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        digest.update(buffer, 0, count)
                                        size += count
                                    }
                                }
                                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                if (size != attributes.size() || attributes.size() != after.size() ||
                                    attributes.lastModifiedTime() != after.lastModifiedTime() || attributes.fileKey() != after.fileKey()) {
                                    throw IOException("File changed while its contents were inspected")
                                }
                            }
                            else -> throw IOException("Cannot fingerprint special file: ${target.relativize(path)}")
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            if (!options.deleteLocalChanges) {
                if (tracked.isNotEmpty()) block(RemovalBlockReason.TRACKED_CHANGES, "Worktree has tracked changes")
                if (untracked.isNotEmpty()) block(RemovalBlockReason.UNTRACKED_FILES, "Worktree has untracked files")
                if (ignored.isNotEmpty()) block(RemovalBlockReason.IGNORED_FILES, "Worktree has ignored files")
            }
            val history = observe("Commit history", RemovalBlockReason.HISTORY_UNKNOWN) {
                val head = git(target, "rev-parse", "--verify", "HEAD^{commit}").trim()
                val branch = git(target, "rev-parse", "--symbolic-full-name", "HEAD").trim()
                if (head != entry.head || branch != (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")) {
                    throw IOException("HEAD or branch changed while inspecting the worktree")
                }
                state.add(head)
                state.add(branch)
                // Only persistent refs retain commits: another worktree's HEAD or a reflog does not.
                val refs = git(repository, "for-each-ref", "--format=%(refname)%00%(objectname)%00%(upstream)%00%(symref)")
                // Bind only branch/upstream refs and retention results: deleting an unrelated
                // branch in the same approved batch must not invalidate this target.
                val refRecords = refs.lineSequence().filter { it.isNotEmpty() }.map { it.split('\u0000') }.toList()
                val branchRef = entry.branch?.let { "refs/heads/$it" }
                val branchRecord = refRecords.firstOrNull { it.firstOrNull() == branchRef }
                if (branchRef != null && (branchRecord?.getOrNull(1) != head || !branchRecord?.getOrNull(3).isNullOrEmpty())) {
                    throw IOException("Local branch changed or is symbolic")
                }
                // Symbolic aliases do not retain objects after their target branch is deleted.
                val retainedRefs = refRecords.filter { it.getOrNull(3).isNullOrEmpty() }.map { it.first() }.filter { ref ->
                    ref != branchRef && !ref.startsWith("refs/bisect/") &&
                        !ref.startsWith("refs/worktree/") && !ref.startsWith("refs/rewritten/")
                }
                unretained = git(target, "--no-replace-objects", "rev-list", "HEAD", "--not", *retainedRefs.toTypedArray(), "--")
                    .lineSequence().filter { it.isNotEmpty() }.toList()
                unsafe = if (options.deleteLocalBranch || branchRef == null) unretained.size else 0
                val upstream = branchRecord?.getOrNull(2)?.takeIf { it.isNotEmpty() }
                state.add(branchRecord?.joinToString("\u0000").orEmpty())
                state.add(refRecords.firstOrNull { it.firstOrNull() == upstream }?.joinToString("\u0000").orEmpty())
                unpushed = when {
                    upstream == null -> null
                    remoteBranchAbsent && refRecords.none { record -> record.first() == upstream } -> null
                    else -> git(target, "--no-replace-objects", "rev-list", "--count", "$upstream..HEAD", "--").trim().toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw IOException("Git returned an invalid commit count")
                }
                "${unsafe}:${unpushed}:${unretained.joinToString(",")}"
            }
            historyKnown = history != null
            if (!historyKnown) {
                unsafe = null
                unpushed = null
                unretained = emptyList()
            } else if (unsafe != 0 && !(options.deleteLocalBranch && entry.branch != null)) {
                block(RemovalBlockReason.UNRETAINED_COMMITS, "Worktree has $unsafe commits not retained by persistent refs")
            }
        }
        state.addAll(blocked)
        state.addAll(blockedCodes.map { it.name })
        val digest = MessageDigest.getInstance("SHA-256")
        state.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        RemovalPlan(
            repositoryDirectory = repository.toString(), directory = target.toString(), head = entry?.head, branch = entry?.branch,
            options = options, trackedChanges = tracked, untrackedFiles = untracked, ignoredFiles = ignored,
            remoteBranchAbsent = remoteBranchAbsent, remoteBranchDetails = remoteBranchDetails, unretainedCommitIds = unretained,
            unpushedCommits = unpushed, historyKnown = historyKnown, unsafeCommits = unsafe,
            blockedReasons = blocked, blockedCodes = blockedCodes, confirmation = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    suspend fun remove(plan: RemovalPlan, confirmation: String): String = withContext(Dispatchers.IO) {
        require(confirmation.isNotEmpty() && confirmation == plan.confirmation) { "Confirmation does not match the approved removal preview" }
        require(plan.blockedCodes.isEmpty()) { "Removal is blocked: ${plan.blockedReasons.joinToString("; ")}" }
        val current = previewRemove(plan.repositoryDirectory, plan.directory, plan.options)
        require(current == plan) { "Removal state changed; obtain and approve a new preview" }
        require(current.blockedCodes.isEmpty() && current.historyKnown &&
            (current.unsafeCommits == 0 || current.options.deleteLocalBranch && current.branch != null) &&
            (!current.options.deleteLocalBranch || current.remoteBranchAbsent)) { "Removal safety could not be established" }
        currentCoroutineContext().ensureActive()
        val repository = Path.of(current.repositoryDirectory)
        val arguments = mutableListOf("worktree", "remove")
        if (current.options.deleteLocalChanges) arguments.add("--force")
        arguments.addAll(listOf("--", current.directory))
        git(repository, *arguments.toTypedArray())
        if (current.options.deleteLocalBranch) {
            val branch = requireNotNull(current.branch)
            try {
                val inventory = parse(git(repository, "worktree", "list", "--porcelain", "-z"))
                check(inventory.none { it.branch == branch }) { "Local branch is now checked out in another worktree" }
                // Compare-and-delete prevents a concurrent new tip from being discarded.
                git(repository, "update-ref", "--no-deref", "-d", "refs/heads/$branch", requireNotNull(current.head))
                val branchConfig = git(repository, "config", "--local", "--null", "--list")
                if (branchConfig.split('\u0000').any { it.substringBefore('\n').substringBeforeLast('.') == "branch.$branch" }) {
                    git(repository, "config", "--local", "--remove-section", "branch.$branch")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                cancelled.addSuppressed(RemovalException(current.directory, branch, cancelled))
                throw cancelled
            } catch (error: Exception) {
                throw RemovalException(current.directory, branch, error)
            }
        }
        current.directory
    }

    private fun repositoryPath(repositoryDirectory: String): Path {
        val supplied = Path.of(repositoryDirectory)
        require(supplied.isAbsolute) { "Repository directory must be absolute" }
        val repository = try {
            supplied.toRealPath()
        } catch (error: IOException) {
            throw IllegalArgumentException("Repository directory does not exist or is inaccessible: $repositoryDirectory", error)
        }
        require(Files.isDirectory(repository)) { "Repository path is not a directory: $repositoryDirectory" }
        return repository
    }

    private fun destinationPath(directory: String): Path {
        val supplied = Path.of(directory)
        require(supplied.isAbsolute) { "Destination directory must be absolute" }
        val normalized = supplied.normalize()
        require(!Files.exists(normalized, NOFOLLOW_LINKS)) { "Destination must not already exist: $directory" }
        var ancestor = normalized.parent
        while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.parent
        require(Files.isDirectory(ancestor)) { "Destination ancestor is not a directory: $ancestor" }
        val destination = ancestor.toRealPath().resolve(ancestor.relativize(normalized))
        require(!Files.exists(destination, NOFOLLOW_LINKS)) { "Destination must not already exist: $destination" }
        return destination
    }

    internal suspend fun git(repository: Path, vararg arguments: String): String {
        currentCoroutineContext().ensureActive()
        val command = GeneralCommandLine(GitExecutableManager.getInstance().pathToGit)
            .withParameters("-C", repository.toString(), "-c", "core.hooksPath=/dev/null")
            .withParameters(*arguments)
            .withWorkingDirectory(repository)
            .withCharset(Charsets.UTF_8)
        // Explicit -C must not be overridden by the IDE's inherited Git repository environment.
        val environment = command.effectiveEnvironment.filterKeys { !it.startsWith("GIT_") }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(environment)
        val handler = try {
            BinaryOSProcessHandler(command)
        } catch (error: ExecutionException) {
            throw IOException("Unable to start Git: ${error.message}", error)
        }
        val stderr = StringBuilder()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDERR) stderr.append(event.text)
            }
        })
        try {
            handler.startNotify()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!handler.waitFor(100)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw IOException("Git command timed out: ${arguments.toList()} in $repository")
            }
            currentCoroutineContext().ensureActive()
            if (handler.exitCode != 0) {
                throw IOException("Git command failed (${handler.exitCode}): ${stderr.toString().trim()}")
            }
            return handler.output.toString(Charsets.UTF_8)
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
    }

    private fun parse(output: String): List<GitWorktree> {
        if (!output.endsWith("\u0000\u0000")) throw IOException("Git returned an empty or incomplete worktree inventory")
        return output.removeSuffix("\u0000\u0000").split("\u0000\u0000").mapIndexed { index, record ->
            val fields = record.split('\u0000').associate { field ->
                val separator = field.indexOf(' ')
                if (separator < 0) field to "" else field.substring(0, separator) to field.substring(separator + 1)
            }
            val directory = fields["worktree"] ?: throw IOException("Git worktree inventory has no directory")
            val path = Path.of(directory)
            if (!path.isAbsolute) throw IOException("Git returned a nonabsolute worktree path: $directory")
            val canonical = try {
                path.toRealPath()
            } catch (_: NoSuchFileException) {
                path.toAbsolutePath().normalize()
            }
            val bare = "bare" in fields
            val head = fields["HEAD"]
            if (!bare && head.isNullOrEmpty()) throw IOException("Git worktree inventory has no HEAD: $directory")
            GitWorktree(
                directory = canonical.toString(),
                head = head,
                branch = fields["branch"]?.removePrefix("refs/heads/"),
                main = index == 0,
                detached = "detached" in fields,
                locked = fields["locked"],
                prunable = fields["prunable"],
                bare = bare,
            )
        }
    }
}
