package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginOutputBuilderTest {
    private val builder = PluginOutputBuilder()
    private val rules = GamePluginLoadOrderRules()

    @Test
    fun skyrimWritesEnabledActivationListAndCompleteOrder() {
        val output = builder.build(
            plugins = listOf(
                plugin("Late.esp", priority = 30, enabled = true),
                plugin("Disabled.esp", priority = 20, enabled = false),
                plugin("Skyrim.esm", priority = 10, enabled = true)
            ),
            rule = rules.require("skyrim_le")
        )

        assertEquals("Skyrim.esm\nLate.esp", output.pluginsTxt)
        assertEquals("Skyrim.esm\nDisabled.esp\nLate.esp", output.loadorderTxt)
    }

    @Test
    fun timestampGamesWriteEnabledActivationListWithoutLoadorderFile() {
        listOf("oblivion", "fallout_3", "fallout_nv").forEach { gameId ->
            val output = builder.build(
                plugins = listOf(
                    plugin("Late.esp", priority = 30, enabled = true),
                    plugin("Disabled.esp", priority = 20, enabled = false),
                    plugin("Base.esm", priority = 10, enabled = true)
                ),
                rule = rules.require(gameId)
            )

            assertEquals("Base.esm\nLate.esp", output.pluginsTxt)
            assertNull(output.loadorderTxt)
        }
    }

    private fun plugin(
        name: String,
        priority: Int,
        enabled: Boolean
    ): PluginEntry {
        return PluginEntry(
            normalizedPath = name.lowercase(),
            pluginName = name,
            sourceModId = "mod",
            sourceModName = "Mod",
            enabled = enabled,
            priority = priority,
            pluginType = if (name.endsWith(".esm", ignoreCase = true)) "ESM" else "ESP"
        )
    }
}
