package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.FileRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDiscoveryTest {

    private val discovery = PluginDiscovery()

    @Test
    fun discoverPluginsFromWinningRecords_onlyReturnsPluginFiles() {
        val records = listOf(
            fileRecord("textures/armor/iron.dds"),
            fileRecord("skyrim.esm"),
            fileRecord("my mod.esp"),
            fileRecord("meshes/weapons/sword.nif")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals(2, plugins.size)
        assertTrue(plugins.any { it.pluginName == "skyrim.esm" })
        assertTrue(plugins.any { it.pluginName == "my mod.esp" })
    }

    @Test
    fun discoverPluginsFromWinningRecords_assignsPriorityStartingAtOne() {
        val records = listOf(
            fileRecord("zeta.esp"),
            fileRecord("alpha.esm"),
            fileRecord("middle.esl")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals("alpha.esm", plugins[0].pluginName)
        assertEquals(1, plugins[0].priority)

        assertEquals("middle.esl", plugins[1].pluginName)
        assertEquals(2, plugins[1].priority)

        assertEquals("zeta.esp", plugins[2].pluginName)
        assertEquals(3, plugins[2].priority)
    }

    @Test
    fun discoverPluginsFromWinningRecords_detectsPluginTypes() {
        val records = listOf(
            fileRecord("skyrim.esm"),
            fileRecord("update.esl"),
            fileRecord("some mod.esp")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals("ESM", plugins.first { it.pluginName == "skyrim.esm" }.pluginType)
        assertEquals("ESL", plugins.first { it.pluginName == "update.esl" }.pluginType)
        assertEquals("ESP", plugins.first { it.pluginName == "some mod.esp" }.pluginType)
    }

    private fun fileRecord(path: String): FileRecord {
        return FileRecord(
            normalizedPath = path,
            winningModId = "mod-1",
            winningModName = "Test Mod",
            sourceFilePath = "/fake/source/$path",
            hash = "fake-hash"
        )
    }
}