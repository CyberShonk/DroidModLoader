package com.shonkware.droidmodloader.engine.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFolderScannerTest {
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
}
