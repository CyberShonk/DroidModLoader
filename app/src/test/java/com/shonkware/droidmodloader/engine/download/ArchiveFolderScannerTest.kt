package com.shonkware.droidmodloader.engine.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveFolderScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun supportedArchiveNamesAreCaseInsensitive() {
        assertTrue(isSupportedArchiveName("mod.zip"))
        assertTrue(isSupportedArchiveName("mod.7Z"))
        assertTrue(isSupportedArchiveName("mod.RAR"))
    }

    @Test
    fun unsupportedNamesAreIgnored() {
        assertFalse(isSupportedArchiveName("readme.txt"))
        assertFalse(isSupportedArchiveName("archive.zip.backup"))
        assertFalse(isSupportedArchiveName("folder"))
    }

    @Test
    fun scanUsesCanonicalDirectPathsAndFiltersUnsupportedFiles() {
        val directory = temporaryFolder.newFolder("archives")
        val zip = directory.resolve("Example.zip").apply { writeText("zip") }
        directory.resolve("readme.txt").writeText("text")

        val result = ArchiveFolderScanner().scan(directory.path)

        assertEquals(directory.name, result.folderName)
        assertEquals(1, result.entries.size)
        assertEquals(zip.canonicalPath, result.entries.single().sourcePath)
        assertEquals(zip.canonicalPath, result.entries.single().stableId)
    }
}
