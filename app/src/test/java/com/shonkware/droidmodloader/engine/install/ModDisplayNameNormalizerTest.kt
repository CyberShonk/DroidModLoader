package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Test

class ModDisplayNameNormalizerTest {

    @Test
    fun cleanDisplayName_removesArchiveExtension() {
        val result = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = "Enhanced Blood Textures.zip",
            fallbackFolderName = "fallback"
        )

        assertEquals("Enhanced Blood Textures", result)
    }

    @Test
    fun cleanDisplayName_removesLeadingTimestampPrefix() {
        val result = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = "1740001234_Enhanced_Blood_Textures.7z",
            fallbackFolderName = "fallback"
        )

        assertEquals("Enhanced Blood Textures", result)
    }

    @Test
    fun cleanDisplayName_removesCommonNexusSuffix() {
        val result = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = "Enhanced Blood Textures-60-4-0-1740001234.7z",
            fallbackFolderName = "fallback"
        )

        assertEquals("Enhanced Blood Textures", result)
    }

    @Test
    fun cleanDisplayName_fixesScriptExtenderVersionSpacing() {
        val result = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = "skse 1 07 03.zip",
            fallbackFolderName = "fallback"
        )

        assertEquals("SKSE 1.07.03", result)
    }

    @Test
    fun cleanDisplayName_usesFallbackWhenSourceNameIsBlank() {
        val result = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = "   ",
            fallbackFolderName = "My Mod Folder"
        )

        assertEquals("My Mod Folder", result)
    }
}