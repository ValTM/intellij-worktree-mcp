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
        UNRETAINED_COMMITS,
        OPEN_PROJECT,
    }

    internal data class RemovalPlan(
        val repositoryDirectory: String,
        val directory: String,
        val head: String?,
        val branch: String?,
        val trackedChanges: List<String>,
        val untrackedFiles: List<String>,
        val unpushedCommits: Int?,
        val historyKnown: Boolean,
        val unsafeCommits: Int?,
        val blockedReasons: List<String>,
        val blockedCodes: List<RemovalBlockReason>,
        val confirmation: String,
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

    suspend fun previewRemove(repositoryDirectory: String, directory: String): RemovalPlan = withContext(Dispatchers.IO) {
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
        val state = mutableListOf(repository.toString(), target.toString())
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
        val tracked = mutableListOf<String>()
        val untracked = mutableListOf<String>()
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
                    if (code == "??" || code == "!!") untracked.add(path) else tracked.add(record)
                    if ('R' in code || 'C' in code) {
                        if (index >= records.lastIndex) {
                            block(RemovalBlockReason.STATUS_UNKNOWN, "Worktree status is unknown: incomplete rename")
                            break
                        }
                        tracked.add(records[index++])
                    }
                }
                if (status.isNotEmpty() && !status.endsWith('\u0000')) {
                    block(RemovalBlockReason.STATUS_UNKNOWN, "Worktree status is unknown: incomplete Git output")
                }
            }
            if (tracked.isNotEmpty()) block(RemovalBlockReason.TRACKED_CHANGES, "Worktree has tracked changes")
            if (untracked.isNotEmpty()) block(RemovalBlockReason.UNTRACKED_FILES, "Worktree has untracked or ignored files")
            val history = observe("Commit history", RemovalBlockReason.HISTORY_UNKNOWN) {
                val head = git(target, "rev-parse", "--verify", "HEAD^{commit}").trim()
                val branch = git(target, "rev-parse", "--symbolic-full-name", "HEAD").trim()
                if (head != entry.head || branch != (entry.branch?.let { "refs/heads/$it" } ?: "HEAD")) {
                    throw IOException("HEAD or branch changed while inspecting the worktree")
                }
                state.add(head)
                state.add(branch)
                // Only persistent refs retain commits: another worktree's HEAD or a reflog does not.
                val refs = git(repository, "for-each-ref", "--format=%(refname)%00%(objectname)%00%(upstream)")
                state.add(refs)
                val refRecords = refs.lineSequence().filter { it.isNotEmpty() }.map { it.split('\u0000') }.toList()
                val persistentRefs = refRecords.map { it.first() }.filter { ref ->
                    !ref.startsWith("refs/bisect/") && !ref.startsWith("refs/worktree/") && !ref.startsWith("refs/rewritten/")
                }
                fun count(output: String): Int = output.trim().toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw IOException("Git returned an invalid commit count")
                unsafe = count(git(target, "rev-list", "--count", "HEAD", "--not", *persistentRefs.toTypedArray(), "--"))
                val branchRef = entry.branch?.let { "refs/heads/$it" }
                val upstream = refRecords.firstOrNull { it.firstOrNull() == branchRef }
                    ?.getOrNull(2)?.takeIf { it.isNotEmpty() }
                unpushed = upstream?.let { count(git(target, "rev-list", "--count", "$it..HEAD", "--")) }
                "${unsafe}:${unpushed}"
            }
            historyKnown = history != null
            if (!historyKnown) {
                unsafe = null
                unpushed = null
            } else if (unsafe != 0) {
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
        RemovalPlan(repository.toString(), target.toString(), entry?.head, entry?.branch, tracked, untracked,
            unpushed, historyKnown, unsafe, blocked, blockedCodes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    suspend fun remove(plan: RemovalPlan, confirmation: String): String = withContext(Dispatchers.IO) {
        require(confirmation.isNotEmpty() && confirmation == plan.confirmation) { "Confirmation does not match the approved removal preview" }
        require(plan.blockedCodes.isEmpty()) { "Removal is blocked: ${plan.blockedReasons.joinToString("; ")}" }
        val current = previewRemove(plan.repositoryDirectory, plan.directory)
        require(current == plan) { "Removal state changed; obtain and approve a new preview" }
        require(current.blockedCodes.isEmpty() && current.historyKnown && current.unsafeCommits == 0) { "Removal safety could not be established" }
        currentCoroutineContext().ensureActive()
        git(Path.of(current.repositoryDirectory), "worktree", "remove", "--", current.directory)
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
