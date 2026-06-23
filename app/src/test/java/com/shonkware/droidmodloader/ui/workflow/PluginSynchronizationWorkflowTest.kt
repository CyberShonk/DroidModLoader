package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSynchronizationWorkflowTest {

    @Test
    fun `sync preserves prior state and locks official plugins`() {
        val engine = FakeEngine(
            previous = listOf(
                plugin("managed.esp", enabled = false, priority = 7),
                plugin("unmanaged.esp", enabled = true, priority = 4)
            ),
            dataPlugins = listOf(
                plugin("falloutnv.esm", sourceType = "base_game", priority = 0),
                plugin("unmanaged.esp", sourceType = "unmanaged_data", priority = 9)
            ),
            managedPlugins = listOf(
                plugin("managed.esp", sourceType = "managed_mod", priority = 10)
            )
        )
        val logs = mutableListOf<String>()

        PluginSynchronizationWorkflow(logs::add).sync(engine, "fallout_nv")

        val saved = engine.saved
        assertEquals(listOf("falloutnv.esm", "unmanaged.esp", "managed.esp"), saved.map { it.normalizedPath })
        assertTrue(saved[0].enabled)
        assertTrue(saved[0].locked)
        assertTrue(saved[0].filePresentInDataFolder)
        assertTrue(saved[1].enabled)
        assertFalse(saved[1].locked)
        assertFalse(saved[2].enabled)
        assertFalse(saved[2].filePresentInDataFolder)
        assertTrue(logs.any { it == "Selected game: fallout_nv" })
    }

    private fun plugin(
        path: String,
        enabled: Boolean = true,
        priority: Int = 1,
        sourceType: String = "managed_mod"
    ): PluginEntry {
        return PluginEntry(
            pluginName = path,
            normalizedPath = path,
            enabled = enabled,
            priority = priority,
            sourceModId = "test-mod",
            sourceModName = "Test Mod",
            pluginType = if (path.endsWith(".esm", ignoreCase = true)) "ESM" else "ESP",
            sourceType = sourceType,
            locked = false,
            filePresentInDataFolder = false
        )
    }

    private class FakeEngine(
        private val previous: List<PluginEntry>,
        private val dataPlugins: List<PluginEntry>,
        private val managedPlugins: List<PluginEntry>
    ) : PluginSynchronizationEngine {
        var saved: List<PluginEntry> = emptyList()

        override fun loadPlugins(): List<PluginEntry> = previous
        override fun scanDataFolderPlugins(gameId: String): List<PluginEntry> = dataPlugins
        override fun discoverPluginsFromCurrentMods(): List<PluginEntry> = managedPlugins
        override fun getEnabledCurrentMods(): List<Mod> = emptyList()

        override fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> {
            return plugins.mapIndexed { index, plugin -> plugin.copy(priority = index + 1) }
        }

        override fun saveCurrentPlugins(plugins: List<PluginEntry>) {
            saved = plugins
        }
    }
}
