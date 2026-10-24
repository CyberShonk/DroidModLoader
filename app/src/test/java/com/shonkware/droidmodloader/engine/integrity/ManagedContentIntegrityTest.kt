package com.shonkware.droidmodloader.engine.integrity

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ManagedContentIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val integrity = ManagedContentIntegrity

    private fun input(text: String = "expected"): IntegrityInput {
        val root = temporary.newFolder()
        val evidence = temporary.newFile()
        File(root, "file.txt").writeText(text)
        evidence.writeText("""[{"normalizedPath":"file.txt","hash":"${integrity.digest(text.toByteArray())}"}]""")
        return capture(root, evidence)
    }

    private fun capture(root: File, evidence: File) =
        integrity.capture("Data", "target-a", root, evidence, integrity::manifestRecords)

    @Test fun `same size changed bytes are detected without metadata writes`() {
        val input = input()
        val originalEvidence = input.evidenceFile.readBytes()
        val target = File(input.root, "file.txt")
        val time = target.lastModified()
        target.writeText("modified")
        target.setLastModified(time)
        val result = integrity.check(input) { false }
        assertEquals(1, result.checked)
        assertEquals(1, result.changed)
        assertTrue(result.complete)
        assertArrayEquals(originalEvidence, input.evidenceFile.readBytes())
        assertEquals("modified", target.readText())
    }

    @Test fun `matching bytes complete and missing bytes counted`() {
        val input = input()
        assertEquals(1, integrity.check(input) { false }.checked)
        File(input.root, "file.txt").delete()
        val result = integrity.check(input) { false }
        assertEquals(1, result.missing)
        assertTrue(result.complete)
    }

    @Test fun `missing hash is unavailable not a new baseline`() {
        val input = input().let { it.copy(records = listOf(RecordedContent("file.txt", ""))) }
        val result = integrity.check(input) { false }
        assertEquals(0, result.checked)
        assertEquals(1, result.unavailable)
        assertFalse(result.complete)
    }

    @Test fun `missing root and nonfile records are not clean evidence`() {
        val input = input()
        File(input.root, "file.txt").delete()
        File(input.root, "file.txt").mkdir()
        assertEquals(1, integrity.check(input) { false }.unreadable)
        input.root!!.deleteRecursively()
        assertEquals("Missing", integrity.check(input) { false }.rootStatus)
    }

    @Test fun `missing blank malformed and duplicate metadata remain unavailable`() {
        val input = input()
        for (text in listOf("", "broken", "{}", "[{}]",
            """[{"normalizedPath":"a"},{"normalizedPath":"A"}]""")) {
            input.evidenceFile.writeText(text)
            val captured = capture(input.root!!, input.evidenceFile)
            assertNotNull(captured.unavailable)
            assertFalse(integrity.check(captured) { false }.complete)
        }
        input.evidenceFile.delete()
        assertNotNull(capture(input.root!!, input.evidenceFile).unavailable)
    }

    @Test fun `invalid paths fail before traversal`() {
        val input = input()
        for (path in listOf("../outside", "/absolute", "a/../b", "a//b", "C:/drive", "a\\b", ".")) {
            input.evidenceFile.writeText("""[{"normalizedPath":${org.json.JSONObject.quote(path)}}]""")
            val captured = capture(input.root!!, input.evidenceFile)
            assertNotNull(path, captured.unavailable)
            assertEquals(0, integrity.check(captured) { false }.checked)
        }
    }

    @Test fun `symlink aliases inside and outside root are not read`() {
        val input = input()
        val target = File(input.root, "file.txt")
        val outside = temporary.newFile().apply { writeText("expected") }
        val inside = File(input.root, "other.txt").apply { writeText("expected") }
        for (destination in listOf(outside, inside)) {
            target.delete()
            Files.createSymbolicLink(target.toPath(), destination.toPath())
            val result = integrity.check(input) { false }
            assertEquals(0, result.checked)
            assertEquals(1, result.unreadable)
        }
        assertEquals("expected", outside.readText())
    }

    @Test fun `root replaced with an outside alias after capture is refused before content reads`() {
        val input = input()
        val root = input.root!!
        val outside = temporary.newFolder().apply { File(this, "file.txt").writeText("expected") }
        root.deleteRecursively()
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        val result = integrity.check(input) { false }
        assertEquals("Unreadable", result.rootStatus)
        assertEquals(0, result.checked)
        Files.delete(root.toPath())
    }

    @Test fun `oversized evidence is unavailable without partial traversal`() {
        val input = input()
        input.evidenceFile.writeText(" ".repeat(8 * 1024 * 1024 + 1))
        val captured = capture(input.root!!, input.evidenceFile)
        assertNotNull(captured.unavailable)
        assertEquals(0, integrity.check(captured) { false }.checked)
    }

    @Test fun `cancellation preserves partial counts and stops within a file`() {
        val input = input("x".repeat(200_000))
        val polls = AtomicInteger()
        val report = integrity.verify({ listOf(input) }, { "profile-a" }) { polls.incrementAndGet() >= 3 }
        assertTrue(report.cancelled)
        assertEquals(0, report.groups.single().checked)
        assertFalse(report.groups.single().complete)
        assertTrue(report.toSummary().contains("CANCELLED / PARTIAL"))
    }

    @Test fun `changed manifest and profile revisions mark report stale`() {
        val input = input()
        var captures = 0
        val report = integrity.verify(
            capture = {
                captures++
                listOf(if (captures == 1) input else input.copy(revision = "different"))
            }, extraRevision = { "profile-a" }, cancelled = { false }
        )
        assertTrue(report.stale)
        var revisions = 0
        assertTrue(integrity.verify({ listOf(input) }, { "profile-${revisions++}" }) { false }.stale)
    }

    @Test fun `target switches cannot publish current evidence for the new identity`() {
        val input = input()
        var captures = 0
        val report = integrity.verify({
            listOf(if (captures++ == 0) input else input.copy(identity = "target-b"))
        }, { "profile-a" }) { false }
        assertTrue(report.stale)
        assertEquals("target-a", report.groups.single().identity)
    }

    @Test fun `index schema identity and original path mapping are required`() {
        val json = org.json.JSONObject()
            .put("schemaVersion", 1).put("modId", "mod").put("modRootPath", "/managed/mod")
            .put("entries", org.json.JSONArray("""[{"originalPath":"Data/File.TXT","normalizedPath":"file.txt","hash":""}]"""))
        assertEquals("Data/File.TXT", integrity.indexRecords(json.toString(), "mod", "/managed/mod").single().path)
        for (field in listOf("schemaVersion", "modId", "modRootPath")) {
            val invalid = org.json.JSONObject(json.toString()).put(field, "invalid")
            try {
                integrity.indexRecords(invalid.toString(), "mod", "/managed/mod")
                fail("Expected invalid $field to be refused")
            } catch (_: Exception) { }
        }
    }

    @Test fun `permission denied is unreadable not missing`() {
        val input = input()
        val target = File(input.root, "file.txt")
        Files.setPosixFilePermissions(target.toPath(), emptySet())
        try {
            // This fixture must actually deny access; a privileged runner is not permission-loss proof.
            assertFalse("Run permission fixture without root privileges", target.canRead())
            val result = integrity.check(input) { false }
            assertEquals(1, result.unreadable)
            assertEquals(0, result.missing)
        } finally {
            target.setReadable(true, true)
            target.setWritable(true, true)
        }
    }
}
