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
    suspend fun discover(repositoryDirectory: String): List<GitWorktree> = withContext(Dispatchers.IO) {
        val supplied = Path.of(repositoryDirectory)
        require(supplied.isAbsolute) { "Repository directory must be absolute" }
        val repository = try {
            supplied.toRealPath()
        } catch (error: IOException) {
            throw IllegalArgumentException("Repository directory does not exist or is inaccessible: $repositoryDirectory", error)
        }
        require(Files.isDirectory(repository)) { "Repository path is not a directory: $repositoryDirectory" }
        parse(git(repository))
    }

    private suspend fun git(repository: Path): String {
        currentCoroutineContext().ensureActive()
        val command = GeneralCommandLine(
            GitExecutableManager.getInstance().pathToGit,
            "-C", repository.toString(), "worktree", "list", "--porcelain", "-z",
        )
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
                if (System.nanoTime() >= deadline) throw IOException("Git worktree discovery timed out: $repository")
            }
            currentCoroutineContext().ensureActive()
            if (handler.exitCode != 0) {
                throw IOException("Git worktree discovery failed (${handler.exitCode}): ${stderr.toString().trim()}")
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
