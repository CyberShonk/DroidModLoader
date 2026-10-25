package com.shonkware.droidmodloader

import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.install.ArchiveEntryWriter
import com.shonkware.droidmodloader.engine.install.ArchiveReadException
import com.shonkware.droidmodloader.engine.install.ArchiveReadFailureCode
import com.shonkware.droidmodloader.engine.install.ExtractionLimits
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallFileCopier
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import com.shonkware.droidmodloader.engine.storage.StorageCapacityStatus
import com.shonkware.droidmodloader.engine.storage.StorageWriteRequirement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** App-owned disposable fixtures only; never fills a disk, changes permissions or selects a game target. */
@RunWith(AndroidJUnit4::class)
class StorageCapacityTest {
    @Test
    fun nativeProbeGroupsRelevantPathsByBackingVolumeWithoutCreatingDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = checkNotNull(context.getSystemService(StorageManager::class.java))
        val external = checkNotNull(context.getExternalFilesDir(null))
        val roots = listOf(context.cacheDir, external).map { parent ->
            Files.createTempDirectory(parent.toPath(), "capacity-fixture-").toFile()
        }
        try {
            val probe = AndroidStorageCapacityProbe(context)
            val requirements = roots.flatMapIndexed { index, root ->
                listOf(root, File(root, "missing/stage")).map { path ->
                    StorageWriteRequirement("fixture $index", path, "DML owned fixture", 1L, true)
                }
            }
            val expectedIds = roots.map { storage.getUuidForPath(it).toString() }.toSet()
            val groups = StorageCapacity.assess(requirements, probe, reserveBytes = 0L)
            assertEquals(expectedIds, groups.map { it.volumeId }.toSet())
            assertEquals(expectedIds.size, groups.size)
            assertEquals(4L, groups.sumOf { checkNotNull(it.minimumRequiredBytes) })
            assertTrue(groups.all {
                it.availableBytes != null && it.availableBytes >= 0L &&
                    it.status == StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES
            })
            assertTrue(roots.all { !File(it, "missing").exists() })
        } finally {
            roots.forEach { check(it.deleteRecursively()) }
        }
    }

    @Test
    fun lowSpaceBeforeCopyAndDuringExtractionPreservesOriginalAndCleansPartialFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "capacity-failure-fixture-").toFile()
        try {
            val source = File(root, "source").apply { writeText("original source") }
            val target = File(root, "target").apply { writeText("original target") }
            try {
                InstallFileCopier.copyRecursively(source, target, InstallCancellationSignal.NONE) { 0L }
                fail("Expected insufficient storage")
            } catch (failure: IOException) {
                assertTrue(failure.message.orEmpty().contains("Not enough free storage"))
            }
            assertEquals("original source", source.readText())
            assertEquals("original target", target.readText())
            val output = File(root, "extracted").apply { mkdirs() }
            var checks = 0
            val writer = ArchiveEntryWriter(output,
                ExtractionLimits(minimumFreeSpaceBytes = 0L, spaceCheckIntervalBytes = 1L), {},
                usableSpaceBytes = { checks++; if (checks == 1) 100L else 0L })
            try {
                writer.writeFile("partial") { stream ->
                    stream.write(byteArrayOf(1))
                    stream.write(byteArrayOf(2))
                }
                fail("Expected extraction storage failure")
            } catch (failure: ArchiveReadException) {
                assertEquals(ArchiveReadFailureCode.INSUFFICIENT_STORAGE, failure.code)
            }
            assertFalse(File(output, "partial").exists())
        } finally {
            check(root.deleteRecursively())
        }
    }
}
