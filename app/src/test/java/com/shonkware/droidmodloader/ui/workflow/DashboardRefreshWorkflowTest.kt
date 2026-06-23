package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRefreshWorkflowTest {

    @Test
    fun `build sorts state and reports direct target`() {
        val low = mod("low", priority = 1, enabled = true)
        val high = mod("high", priority = 2, enabled = false)
        val engine = FakeEngine(
            mods = listOf(high, low),
            plugins = listOf(plugin("late.esp", 2), plugin("early.esm", 1)),
            config = GameDeploymentConfig(
                gameId = "fallout_nv",
                displayName = "Fallout New Vegas",
                targetDataPath = "/games/fnv/Data",
                realDeployEnabled = true
            )
        )

        val result = DashboardRefreshWorkflow().build(engine, "fallout_nv")

        assertEquals(listOf("low", "high"), result.mods.map { it.id })
        assertEquals(listOf("early.esm", "late.esp"), result.plugins.map { it.normalizedPath })
        assertEquals(setOf("low", "high"), result.modContentIndexes.keys)
        assertTrue(result.summaryText.contains("Installed mods: 2"))
        assertTrue(result.summaryText.contains("Enabled mods: 1"))
        assertTrue(result.summaryText.contains("Deploy mode: Direct Path"))
        assertTrue(result.summaryText.contains("Target: /games/fnv/Data"))
    }

    private fun mod(id: String, priority: Int, enabled: Boolean): Mod {
        return Mod(
            id = id,
            name = id,
            installPath = "/mods/$id",
            enabled = enabled,
            priority = priority,
            modType = ModType.LOOSE
        )
    }

    private fun plugin(path: String, priority: Int): PluginEntry {
        return PluginEntry(
            pluginName = path,
            normalizedPath = path,
            enabled = true,
            priority = priority,
            sourceModId = "test-mod",
            sourceModName = "Test Mod",
            pluginType = if (path.endsWith(".esm", ignoreCase = true)) "ESM" else "ESP",
            sourceType = "managed_mod",
            locked = false,
            filePresentInDataFolder = true
        )
    }

    private class FakeEngine(
        private val mods: List<Mod>,
        private val plugins: List<PluginEntry>,
        private val config: GameDeploymentConfig?
    ) : DashboardRefreshEngine {
        override fun getCurrentMods(): List<Mod> = mods
        override fun getCurrentPlugins(): List<PluginEntry> = plugins
        override fun hasSavedState(): Boolean = true
        override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? = config
        override fun validateTargetDataPath(path: String): Boolean = true
        override fun indexModContent(mod: Mod): ModContentIndex = ModContentIndex(
            modId = mod.id,
            modName = mod.name,
            entries = emptyList()
        )
    }
}
