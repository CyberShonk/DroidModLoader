package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal interface PriorityNormalizationEngine {
    fun getCurrentMods(): List<Mod>
    fun normalizeModPriorities(mods: List<Mod>): List<Mod>
    fun saveCurrentMods(mods: List<Mod>)
    fun getCurrentPlugins(): List<PluginEntry>
    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry>
    fun saveCurrentPlugins(plugins: List<PluginEntry>)
}

internal class PriorityNormalizationEngineAdapter(
    private val engine: ModEngine
) : PriorityNormalizationEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()
    override fun normalizeModPriorities(mods: List<Mod>): List<Mod> =
        engine.normalizeModPriorities(mods)

    override fun saveCurrentMods(mods: List<Mod>) = engine.saveCurrentMods(mods)
    override fun getCurrentPlugins(): List<PluginEntry> = engine.getCurrentPlugins()
    override fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> =
        engine.normalizePluginPriorities(plugins)

    override fun saveCurrentPlugins(plugins: List<PluginEntry>) =
        engine.saveCurrentPlugins(plugins)
}

internal class PriorityNormalizationWorkflow(
    private val appendLog: (String) -> Unit
) {
    fun migrateIfNeeded(engine: PriorityNormalizationEngine) {
        val mods = engine.getCurrentMods().sortedBy { it.priority }
        val normalizedMods = engine.normalizeModPriorities(mods)
        if (mods != normalizedMods) {
            engine.saveCurrentMods(normalizedMods)
            appendLog("Migrated mod priorities to sequential 1-based ordering.")
        }

        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }
        val normalizedPlugins = engine.normalizePluginPriorities(plugins)
        if (plugins != normalizedPlugins) {
            engine.saveCurrentPlugins(normalizedPlugins)
            appendLog("Migrated plugin priorities to sequential 1-based ordering.")
        }
    }
}
