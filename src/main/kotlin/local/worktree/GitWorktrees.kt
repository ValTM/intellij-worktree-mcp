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

    private suspend fun git(repository: Path, vararg arguments: String): String {
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
