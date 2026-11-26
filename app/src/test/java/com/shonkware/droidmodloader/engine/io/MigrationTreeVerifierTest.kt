package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class MigrationTreeVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val verifier = MigrationTreeVerifier()

    @Test fun `digest is independent of root path creation order and timestamps`() {
        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        write(first, "z/file.ini", "configuration")
        write(first, "a/café.ini", "unicode")
        File(first, "empty").mkdir()
        File(second, "empty").mkdir()
        write(second, "a/café.ini", "unicode")
        write(second, "z/file.ini", "configuration")
        Files.setLastModifiedTime(File(second, "z/file.ini").toPath(), FileTime.fromMillis(1000))
        val expected = verifier.capture(first)
        assertEquals(expected, verifier.capture(second))
        assertEquals(5, expected.entryCount)
        assertEquals(20L, expected.payloadBytes)
        verifier.requireMatching(second, expected)
    }

    @Test fun `empty directories names and same-size changed bytes affect digest`() {
        val root = temporaryFolder.newFolder()
        write(root, "file", "old")
        val original = verifier.capture(root)
        write(root, "file", "new")
        val changed = verifier.capture(root)
        assertNotEquals(original, changed)
        assertThrows(IOException::class.java) { verifier.requireMatching(root, original) }
        File(root, "empty").mkdir()
        val addedDirectory = verifier.capture(root)
        assertNotEquals(changed, addedDirectory)
        Files.move(File(root, "empty").toPath(), File(root, "renamed").toPath())
        assertNotEquals(addedDirectory, verifier.capture(root))
    }

    @Test fun `entry and workspace bounds fail without truncation or mutation`() {
        val root = temporaryFolder.newFolder()
        write(root, "one", "one")
        write(root, "two", "two")
        val before = verifier.capture(root)
        assertThrows(IOException::class.java) { MigrationTreeVerifier(maximumEntries = 1).capture(root) }
        assertThrows(IOException::class.java) {
            MigrationTreeVerifier(maximumWorkspaceBytes = 64 * 1024L).capture(root)
        }
        assertEquals(before, verifier.capture(root))
    }

    @Test fun `links root aliases and missing roots are not empty trees`() {
        val root = temporaryFolder.newFolder()
        val outside = temporaryFolder.newFolder()
        write(outside, "protected", "outside")
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { verifier.capture(root) }
        assertThrows(IOException::class.java) { verifier.capture(File(root, "linked")) }
        assertThrows(IOException::class.java) { verifier.capture(File(root, "missing")) }
        assertEquals("outside", File(outside, "protected").readText())
    }

    @Test fun `depth bound stops safely`() {
        val root = temporaryFolder.newFolder()
        var directory = root
        repeat(65) { directory = File(directory, "d").apply { mkdir() } }
        assertThrows(IOException::class.java) { verifier.capture(root) }
    }

    @Test fun `cancellation preserves payload bytes`() {
        val root = temporaryFolder.newFolder()
        write(root, "file", "protected")
        val expected = verifier.capture(root)
        val cancellation = InstallCancellationController().apply { cancel() }
        assertThrows(InstallCancelledException::class.java) { verifier.capture(root, cancellation.signal) }
        assertEquals(expected, verifier.capture(root))
    }

    @Test fun `file change during streaming is rejected`() {
        val root = temporaryFolder.newFolder()
        val file = File(root, "file").apply { writeBytes(ByteArray(128 * 1024)) }
        var callbacks = 0
        val signal = InstallCancellationSignal {
            if (++callbacks == 5) file.appendText("changed")
        }
        assertThrows(IOException::class.java) { verifier.capture(root, signal) }
    }

    @Test fun `new files invalidate the recorded tree`() {
        val root = temporaryFolder.newFolder()
        write(root, "file", "original")
        val signal = InstallCancellationSignal {
            // Root enumeration and entry visit follow the initial root visit.
            if (!File(root, "file").exists()) throw AssertionError("Payload disappeared")
        }
        val expected = verifier.capture(root, signal)
        write(root, "added", "new")
        assertThrows(IOException::class.java) { verifier.requireMatching(root, expected) }
        assertEquals("original", File(root, "file").readText())
    }

    private fun write(root: File, path: String, content: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }
}
