package com.shonkware.droidmodloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.install.InstallerSelection
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstaller
import com.shonkware.droidmodloader.engine.io.ArchiveImportFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Disposable app external fixtures only. No selected library, profile, or game target. */
@RunWith(AndroidJUnit4::class)
class TemporaryArchiveSnapshotTest {
    @Test
    fun extractedInstallerCanFinalizeAfterSnapshotDeletion() = withFixture { root ->
        val source = File(root, "Example.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("textures/example.dds"))
            zip.write("installed content".toByteArray())
            zip.closeEntry()
        }
        val store = ArchiveImportFileStore({ root }, { error(it) })
        val snapshot = store.copyToTemporarySnapshot(source, source.name)
        val installer = PreparedArchiveInstaller(File(root, "temp"), File(root, "mods"))
        val prepared = installer.prepare(snapshot)
        store.releaseSnapshot(snapshot)
        assertFalse(snapshot.exists())
        assertTrue(File(prepared.installRootPath).isDirectory)
        val installed = installer.finalizeInstall(
            prepared, InstallerSelection(prepared.plan.defaultSelectedOptionIds)
        )
        assertEquals("installed content", File(installed, "textures/example.dds").readText())
        assertTrue(source.isFile)
        assertTrue(File(root, "downloads/import_staging").listFiles()!!.isEmpty())
    }

    @Test
    fun repeatedCopiesAndAbandonedCleanupPreserveOriginalAndLegacyFiles() = withFixture { root ->
        val legacy = File(root, "downloads/archive_library/Example (3).zip").apply {
            parentFile!!.mkdirs()
            writeText("legacy original")
        }
        val store = ArchiveImportFileStore({ root }, { error(it) })
        repeat(3) {
            val snapshot = store.copyToTemporarySnapshot(legacy, legacy.name)
            assertEquals(legacy.name, snapshot.name)
            store.cleanAbandonedSnapshots()
            assertTrue(snapshot.isFile) // Current owner is still active.
            store.releaseSnapshot(snapshot)
        }
        val abandoned = store.copyToTemporarySnapshot(legacy, legacy.name)
        // New owner simulates startup after the previous process ended, not a real process kill.
        ArchiveImportFileStore({ root }, { error(it) }).cleanAbandonedSnapshots()
        assertFalse(abandoned.exists())
        assertEquals("legacy original", legacy.readText())
        assertTrue(File(root, "downloads/import_staging").listFiles()!!.isEmpty())
    }

    private fun withFixture(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null))
        val root = Files.createTempDirectory(external.toPath(), "snapshot-fixture-").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
