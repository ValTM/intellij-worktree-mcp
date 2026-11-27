package local.worktree

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class PortableIdeSettingsTest : TestCase() {
    private lateinit var temporary: Path
    private lateinit var source: Path
    private lateinit var destination: Path

    override fun setUp() {
        temporary = Files.createTempDirectory("portable-ide-settings")
        source = Files.createDirectory(temporary.resolve("source"))
        destination = Files.createDirectory(temporary.resolve("destination"))
    }

    override fun tearDown() {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    fun testCopiesOnlyPortableSettingsWithoutRewritingPayloads() {
        val run = configuration("Shared", "<envs><env name=\"TOKEN\" value=\"unchanged\" /></envs><option name=\"workingDirectory\" value=\"\$PROJECT_DIR\$\" />")
        put(source, ".idea/runConfigurations/shared.xml", run)
        put(source, ".run/nested/other.run.xml", configuration("Other"))
        put(source, ".idea/inspectionProfiles/Project.xml", "<component><profile version=\"1.0\" /></component>")
        put(source, ".idea/codeStyles/codeStyleConfig.xml", "<component><state /></component>")
        for (file in listOf(".idea/workspace.xml", ".idea/modules.xml", ".idea/dataSources.xml", ".idea/vcs.xml", ".idea/project.iml", ".idea/runConfigurations/nested/notShared.xml", ".run/notes.xml")) {
            put(source, file, "<private />")
        }

        val result = PortableIdeSettings.transfer(source, destination, emptySet())

        assertEquals(4, result.copied)
        assertTrue(result.skipped.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(run, Files.readString(destination.resolve(".idea/runConfigurations/shared.xml")))
        assertTrue(Files.exists(destination.resolve(".run/nested/other.run.xml")))
        assertFalse(Files.exists(destination.resolve(".idea/workspace.xml")))
        assertFalse(Files.exists(destination.resolve(".idea/modules.xml")))
        assertFalse(Files.exists(destination.resolve(".idea/runConfigurations/nested")))
        assertFalse(Files.exists(destination.resolve(".run/notes.xml")))
    }

    fun testSkipsTrackedExistingAndLiteralSourcePathsIncludingXmlEscapes() {
        put(source, ".run/tracked.run.xml", configuration("Tracked"))
        put(source, ".idea/codeStyles/existing.xml", "<component name=\"source\" />")
        put(destination, ".idea/codeStyles/existing.xml", "<component name=\"destination\" />")
        val escapedRoot = source.toString().replace("/", "&#47;")
        put(source, ".run/absolute.run.xml", configuration("Absolute", "<envs><env name=\"ROOT\" value=\"$escapedRoot/child\" /></envs>"))

        val result = PortableIdeSettings.transfer(source, destination, setOf(".run/tracked.run.xml"))

        assertEquals(0, result.copied)
        assertEquals(3, result.skipped.size)
        assertTrue(result.failed.isEmpty())
        assertFalse(Files.exists(destination.resolve(".run/tracked.run.xml")))
        assertFalse(Files.exists(destination.resolve(".run/absolute.run.xml")))
        assertEquals("<component name=\"destination\" />", Files.readString(destination.resolve(".idea/codeStyles/existing.xml")))
        assertTrue(result.summary().contains("0 copied, 3 skipped, 0 failed"))
    }

    fun testSkipsTypeNameCollisionsAcrossSharedFormatsAndWithinSourceFiles() {
        put(destination, ".run/already.run.xml", configuration("Same"))
        put(source, ".idea/runConfigurations/collision.xml", configuration("Same"))
        put(source, ".idea/runConfigurations/first.xml", configuration("New"))
        put(source, ".run/duplicate.run.xml", configuration("New"))
        put(source, ".run/differentType.run.xml", "<component><configuration type=\"Other\" name=\"Same\" /></component>")

        val result = PortableIdeSettings.transfer(source, destination, emptySet())

        assertEquals(2, result.copied)
        assertEquals(2, result.skipped.size)
        assertFalse(Files.exists(destination.resolve(".idea/runConfigurations/collision.xml")))
        assertFalse(Files.exists(destination.resolve(".run/duplicate.run.xml")))
        assertTrue(result.failed.isEmpty())
    }

    fun testRejectsSourceAndDestinationSymlinkEscapes() {
        val outside = Files.createDirectory(temporary.resolve("outside"))
        put(outside, "secret.run.xml", configuration("Secret"))
        Files.createSymbolicLink(source.resolve(".run"), outside)
        put(source, ".idea/codeStyles/Project.xml", "<component />")
        Files.createDirectory(destination.resolve(".idea"))
        Files.createSymbolicLink(destination.resolve(".idea/codeStyles"), outside)

        val result = PortableIdeSettings.transfer(source, destination, emptySet())

        assertEquals(0, result.copied)
        assertEquals(2, result.skipped.size)
        assertFalse(Files.exists(destination.resolve(".run")))
        assertFalse(Files.exists(outside.resolve("Project.xml")))
        assertTrue(result.failed.isEmpty())
        Files.createSymbolicLink(destination.resolve(".run"), outside)
        assertFailsWith<IllegalArgumentException> { PortableIdeSettings.transfer(source, destination, emptySet()) }
    }

    fun testReportsUnsafeXmlFailureWithoutRollingBackCopiedSettingsOrWorktree() {
        put(source, ".idea/codeStyles/a.xml", "<component />")
        put(source, ".idea/codeStyles/b.xml", "<!DOCTYPE component [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]><component>&secret;</component>")
        put(destination, "checkout.txt", "retained")

        val result = PortableIdeSettings.transfer(source, destination, emptySet())

        assertEquals(1, result.copied)
        assertEquals(1, result.failed.size)
        assertTrue(Files.exists(destination.resolve(".idea/codeStyles/a.xml")))
        assertFalse(Files.exists(destination.resolve(".idea/codeStyles/b.xml")))
        assertEquals("retained", Files.readString(destination.resolve("checkout.txt")))
        assertTrue(result.summary().contains("Created worktree retained"))
    }

    private fun configuration(name: String, payload: String = ""): String =
        "<component name=\"ProjectRunConfigurationManager\"><configuration type=\"Shell\" name=\"$name\">$payload</configuration></component>"

    private fun put(root: Path, relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }
}
