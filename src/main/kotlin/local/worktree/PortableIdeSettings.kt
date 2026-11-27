package local.worktree

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object PortableIdeSettings {
    internal data class Result(val copied: Int, val skipped: List<String>, val failed: List<String>) {
        fun summary(): String = buildString {
            append("IDE settings: $copied copied, ${skipped.size} skipped, ${failed.size} failed")
            if (skipped.isNotEmpty()) append(". Skipped: ${skipped.joinToString("; ")}")
            if (failed.isNotEmpty()) append(". Failed: ${failed.joinToString("; ")}. Created worktree retained")
        }
    }

    suspend fun copy(source: Path, destination: Path): Result = withContext(Dispatchers.IO) {
        val tracked = GitWorktrees.git(destination, "ls-files", "-z").split('\u0000').filter(String::isNotEmpty).toSet()
        transfer(source, destination, tracked)
    }

    internal fun transfer(source: Path, destination: Path, tracked: Set<String>): Result {
        val sourceRoot = source.toRealPath()
        val destinationRoot = destination.toRealPath()
        require(sourceRoot != destinationRoot) { "Settings source and destination must differ" }
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var copied = 0
        val configurations = mutableSetOf<Pair<String, String>>()
        // Existing shared configurations reserve their type/name even if stored in a different format.
        for (file in candidates(destinationRoot, runsOnly = true)) {
            require(safePath(destinationRoot, file)) { "Unsafe destination shared configuration: ${destinationRoot.relativize(file)}" }
            configurations.addAll(runKeys(parse(Files.readAllBytes(file))))
        }
        val literalRoots = setOf(source.toAbsolutePath().normalize().toString(), sourceRoot.toString())
        for (file in candidates(sourceRoot)) {
            val relative = sourceRoot.relativize(file)
            val target = destinationRoot.resolve(relative)
            val label = relative.toString()
            try {
                val reason = when {
                    !safePath(sourceRoot, file) || !safePath(destinationRoot, target) -> "symlink or unsafe path"
                    label.replace('\\', '/') in tracked -> "tracked in destination"
                    Files.exists(target, NOFOLLOW_LINKS) -> "already exists"
                    else -> null
                }
                if (reason != null) {
                    skipped.add("$label ($reason)")
                    continue
                }
                val bytes = Files.readAllBytes(file)
                val document = parse(bytes)
                if (containsRoot(document, literalRoots)) {
                    skipped.add("$label (literal source checkout path)")
                    continue
                }
                val keys = if (isRunConfiguration(relative)) runKeys(document) else emptyList()
                if (isRunConfiguration(relative) && (keys.isEmpty() || keys.distinct().size != keys.size || keys.any { it in configurations })) {
                    skipped.add("$label (missing identity or duplicate run type/name)")
                    continue
                }
                Files.createDirectories(target.parent)
                check(safePath(destinationRoot, target)) { "Destination path became unsafe" }
                // CREATE_NEW never replaces a checkout file, including a concurrently created symlink.
                Files.write(target, bytes, CREATE_NEW, WRITE)
                configurations.addAll(keys)
                copied++
            } catch (error: Exception) {
                failed.add("$label (${error.message ?: error.javaClass.simpleName})")
            }
        }
        return Result(copied, skipped, failed)
    }

    private fun candidates(root: Path, runsOnly: Boolean = false): List<Path> = buildList {
        val directories = mutableListOf(".idea/runConfigurations", ".run")
        if (!runsOnly) directories.addAll(listOf(".idea/inspectionProfiles", ".idea/codeStyles"))
        for (directory in directories) {
            val base = root.resolve(directory)
            if (!safePath(root, base)) {
                add(base)
                continue
            }
            if (!Files.isDirectory(base, NOFOLLOW_LINKS)) continue
            Files.walk(base, if (directory == ".idea/runConfigurations") 1 else Int.MAX_VALUE).use { paths ->
                paths.filter { it != base && (Files.isSymbolicLink(it) || Files.isRegularFile(it, NOFOLLOW_LINKS)) }
                    .filter { Files.isSymbolicLink(it) || if (directory == ".run") it.fileName.toString().endsWith(".run.xml") else it.fileName.toString().endsWith(".xml") }
                    .sorted().forEach { add(it) }
            }
        }
    }

    private fun safePath(root: Path, path: Path): Boolean {
        if (!path.normalize().startsWith(root)) return false
        var current = root
        for (part in root.relativize(path)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    private fun isRunConfiguration(path: Path): Boolean = path.startsWith(".run") || path.startsWith(Path.of(".idea", "runConfigurations"))

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun runKeys(document: org.w3c.dom.Document): List<Pair<String, String>> {
        val nodes = document.getElementsByTagName("configuration")
        return (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            val type = node.attributes.getNamedItem("type")?.nodeValue.orEmpty()
            val name = node.attributes.getNamedItem("name")?.nodeValue.orEmpty()
            require(type.isNotBlank() && name.isNotBlank()) { "Run configuration has no type/name" }
            type to name
        }
    }

    private fun containsRoot(node: Node, roots: Set<String>): Boolean {
        if (roots.any { node.nodeValue?.contains(it) == true }) return true
        val attributes = node.attributes
        if (attributes != null && (0 until attributes.length).any { containsRoot(attributes.item(it), roots) }) return true
        return (0 until node.childNodes.length).any { containsRoot(node.childNodes.item(it), roots) }
    }
}
