package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManagedArchiveInventoryTest {
    @Test
    fun `inventory counts owned regular files without validating archives or changing bytes`() = fixture { base ->
        val root = File(base, "downloads/archive_library").apply { mkdirs() }
        val first = File(root, "not-really.zip").apply { writeText("first") }
        val second = File(root, "nested/second.bin").apply { parentFile!!.mkdirs(); writeText("second") }
        val before = snapshot(base)
        val result = ManagedArchiveInventory.read(base, root)
        assertTrue(result.complete)
        assertEquals(2, result.uniqueFiles)
        assertEquals(11L, result.bytes)
        assertEquals(before, snapshot(base))
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test
    fun `duplicate hard links are counted once by physical identity`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        val original = File(root, "one.zip").apply { writeText("content") }
        Files.createLink(File(root, "two.zip").toPath(), original.toPath())
        val result = ManagedArchiveInventory.read(base, root)
        assertTrue(result.complete)
        assertEquals(1, result.uniqueFiles)
        assertEquals(1, result.duplicates)
        assertEquals(7L, result.bytes)
        assertEquals(2, root.list()!!.size)
    }

    @Test
    fun `linked entries never follow outside the archive root and remain partial`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        val foreign = File(base, "sources/original.zip").apply { parentFile!!.mkdirs(); writeText("original") }
        Files.createSymbolicLink(File(root, "linked").toPath(), foreign.parentFile!!.toPath())
        val result = ManagedArchiveInventory.read(base, root)
        assertFalse(result.complete)
        assertEquals(0, result.uniqueFiles)
        assertEquals(1, result.excluded)
        assertNull(result.bytes)
        assertEquals("original", foreign.readText())
    }

    @Test
    fun `linked root and linked ancestor are unavailable rather than empty`() = fixture { base ->
        val actual = File(base, "actual").apply { mkdirs() }
        File(actual, "original.zip").writeText("original")
        val linked = File(base, "linked")
        Files.createSymbolicLink(linked.toPath(), actual.toPath())
        for (root in listOf(linked, File(linked, "missing"))) {
            val result = ManagedArchiveInventory.read(base, root)
            assertFalse(result.complete)
            assertNull(result.bytes)
        }
        assertEquals("original", File(actual, "original.zip").readText())
    }

    @Test
    fun `absent archive root is zero only when its owned base is accessible`() = fixture { base ->
        val root = File(base, "downloads/archive_library")
        val result = ManagedArchiveInventory.read(base, root)
        assertTrue(result.complete)
        assertEquals(0L, result.bytes)
        assertEquals(0, result.uniqueFiles)
        assertFalse(File(base, "downloads").exists())
        assertFalse(ManagedArchiveInventory.read(File(base, "unavailable"), File(base, "unavailable/archives")).complete)
        assertNull(ManagedArchiveInventory.read(null, root).bytes)
    }

    @Test
    fun `an unowned root or entire base cannot be scanned`() = fixture { base ->
        val sibling = File(base, "source").apply { mkdirs() }
        val claimedBase = File(base, "owned").apply { mkdirs() }
        val original = File(sibling, "original").apply { writeText("source") }
        for ((owner, root) in listOf(claimedBase to sibling, base to base)) {
            val result = ManagedArchiveInventory.read(owner, root)
            assertFalse(result.complete)
            assertNull(result.bytes)
        }
        assertEquals("source", original.readText())
    }

    @Test
    fun `entry bound retains only a partial observed size`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        repeat(3) { File(root, "$it.zip").writeText("data") }
        val result = ManagedArchiveInventory.read(base, root, maxEntries = 2)
        assertFalse(result.complete)
        assertEquals(1, result.uniqueFiles)
        assertEquals(4L, result.bytes)
        assertTrue(result.details.any { it.contains("limit reached") })
        assertEquals(3, root.list()!!.size)
    }

    @Test
    fun `depth bound never turns unseen nested bytes into an exact estimate`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        File(root, "one/two/three.zip").apply { parentFile!!.mkdirs(); writeText("deep") }
        val result = ManagedArchiveInventory.read(base, root, maxDepth = 1)
        assertFalse(result.complete)
        assertNull(result.bytes)
        assertEquals(0, result.uniqueFiles)
        assertTrue(result.details.any { it.contains("depth-limited") })
    }

    @Test
    fun `cancellation does not prune content or invent a zero-byte result`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        File(root, "original.zip").writeText("original")
        val before = snapshot(base)
        val result = ManagedArchiveInventory.read(base, root, cancelled = { true })
        assertFalse(result.complete)
        assertNull(result.bytes)
        assertTrue(result.details.any { it.contains("cancelled") })
        assertEquals(before, snapshot(base))
    }

    @Test
    fun `unexpected file at archive directory path is unavailable rather than empty`() = fixture { base ->
        val root = File(base, "archives").apply { writeText("preserve") }
        val result = ManagedArchiveInventory.read(base, root)
        assertFalse(result.complete)
        assertNull(result.bytes)
        assertEquals("preserve", root.readText())
    }

    @Test
    fun `inventory never reads or counts historical duplicate records`() = fixture { base ->
        val root = File(base, "archives").apply { mkdirs() }
        File(root, "retained.zip").writeText("data")
        val history = File(base, "downloaded_archives.json").apply { writeText("duplicate or corrupt history is not inventory") }
        val result = ManagedArchiveInventory.read(base, root)
        assertTrue(result.complete)
        assertEquals(1, result.uniqueFiles)
        assertEquals(4L, result.bytes)
        assertEquals("duplicate or corrupt history is not inventory", history.readText())
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.path to (it.readText() to it.lastModified()) }

    private fun fixture(test: (File) -> Unit) {
        val base = Files.createTempDirectory("dml-archive-inventory-").toFile()
        try { test(base) } finally { base.deleteRecursively() }
    }
}
