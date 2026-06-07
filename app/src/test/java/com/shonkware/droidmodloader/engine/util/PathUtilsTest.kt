package com.shonkware.droidmodloader.engine.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathUtilsTest {

    @Test
    fun normalize_removesLeadingDataFolderAndLowercasesPath() {
        val result = PathUtils.normalize("Data\\Textures\\Armor\\Iron.DDS")

        assertEquals("textures/armor/iron.dds", result)
    }

    @Test
    fun normalize_collapsesDuplicateSlashesAndTrimsTrailingSlash() {
        val result = PathUtils.normalize("./Data//Meshes///Armor/")

        assertEquals("meshes/armor", result)
    }

    @Test
    fun normalize_ignoresEmptyPath() {
        assertNull(PathUtils.normalize(""))
        assertNull(PathUtils.normalize("/"))
        assertNull(PathUtils.normalize("Data/"))
    }

    @Test
    fun normalize_ignoresMacOsMetadataFolder() {
        assertNull(PathUtils.normalize("__MACOSX/file.txt"))
    }

    @Test
    fun normalize_ignoresHiddenPathSegments() {
        assertNull(PathUtils.normalize("textures/.hidden/file.dds"))
        assertNull(PathUtils.normalize(".git/config"))
    }
}