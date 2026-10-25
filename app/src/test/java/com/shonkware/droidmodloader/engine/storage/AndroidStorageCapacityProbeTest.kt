package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AndroidStorageCapacityProbeTest {
    @Test
    fun `missing destination probes existing ancestor without creating folders`() = withRoot { root ->
        val target = File(root, "missing/deeper/stage")
        val seen = mutableListOf<File>()
        val probe = AndroidStorageCapacityProbe({ seen += it; "volume" }, { seen += it; 100L })
        assertEquals(StorageCapacityMeasurement("volume", 100L), probe.read(target))
        assertTrue(seen.all { it == root.canonicalFile })
        assertEquals(3, seen.size)
        assertFalse(File(root, "missing").exists())
    }

    @Test
    fun `measured zero remains a full volume`() = withRoot { root ->
        val probe = AndroidStorageCapacityProbe({ "volume" }, { 0L })
        assertEquals(0L, probe.read(root).availableBytes)
    }

    @Test
    fun `unavailable identity does not erase measured bytes or invent grouping`() = withRoot { root ->
        val probe = AndroidStorageCapacityProbe({ throw IOException("No UUID") }, { 100L })
        val measurement = probe.read(root)
        assertNull(measurement.volumeId)
        assertEquals(100L, measurement.availableBytes)
        assertTrue(measurement.details.contains("identity is unknown"))
    }

    @Test
    fun `permission loss preserves unknown capacity instead of zero`() = withRoot { root ->
        val probe = AndroidStorageCapacityProbe({ "volume" }, { throw SecurityException("Access denied") })
        val measurement = probe.read(root)
        assertEquals("volume", measurement.volumeId)
        assertNull(measurement.availableBytes)
        assertTrue(measurement.details.contains("capacity is unknown"))
    }

    @Test
    fun `volume switch invalidates captured measurement`() = withRoot { root ->
        var calls = 0
        val probe = AndroidStorageCapacityProbe({ calls++; if (calls == 1) "before" else "after" }, { 100L })
        val measurement = probe.read(root)
        assertNull(measurement.volumeId)
        assertNull(measurement.availableBytes)
        assertTrue(measurement.details.contains("changed"))
    }

    @Test
    fun `identity loss after measurement invalidates captured bytes`() = withRoot { root ->
        var calls = 0
        val probe = AndroidStorageCapacityProbe({
            calls++
            if (calls > 1) throw IOException("Removed")
            "volume"
        }, { 100L })
        assertNull(probe.read(root).availableBytes)
    }

    @Test
    fun `broken link is unavailable rather than its parent volume`() = withRoot { root ->
        val link = File(root, "broken")
        Files.createSymbolicLink(link.toPath(), File(root, "absent").toPath())
        val probe = AndroidStorageCapacityProbe({ throw AssertionError("Must not probe a broken link") }, { 100L })
        val measurement = probe.read(link)
        assertNull(measurement.volumeId)
        assertNull(measurement.availableBytes)
        assertTrue(measurement.details.contains("path is unavailable"))
    }

    @Test
    fun `non directory ancestor is not evidence about a future destination`() = withRoot { root ->
        val file = File(root, "file").apply { writeText("original") }
        val probe = AndroidStorageCapacityProbe({ throw AssertionError("Must not probe invalid path") }, { 100L })
        val measurement = probe.read(File(file, "stage"))
        assertNull(measurement.availableBytes)
        assertEquals("original", file.readText())
    }

    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("dml-capacity-probe-").toFile()
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
