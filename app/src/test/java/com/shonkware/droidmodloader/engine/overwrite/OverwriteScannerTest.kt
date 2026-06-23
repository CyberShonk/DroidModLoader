package com.shonkware.droidmodloader.engine.overwrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverwriteScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scansDirectDataFolderWithNormalizedRelativePaths() {
        val dataDir = temporaryFolder.newFolder("Data")
        val file = dataDir.resolve("Textures/Armor/Test.DDS")
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        file.setLastModified(1_700_000_000_000L)

        val result = OverwriteScanner().scanLocalDataFolder(dataDir)

        assertEquals(1, result.size)
        assertEquals("textures/armor/test.dds", result.single().normalizedPath)
        assertEquals(4L, result.single().sizeBytes)
        assertTrue(result.single().modifiedEpochMillis != null)
    }

    @Test
    fun invalidDataFolderReturnsEmptyList() {
        val missing = temporaryFolder.root.resolve("missing")

        assertTrue(OverwriteScanner().scanLocalDataFolder(missing).isEmpty())
    }
}
