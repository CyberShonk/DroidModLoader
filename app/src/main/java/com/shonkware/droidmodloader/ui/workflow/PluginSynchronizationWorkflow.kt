package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal interface PluginSynchronizationEngine {
    fun loadPlugins(): List<PluginEntry>
    fun scanDataFolderPlugins(gameId: String): List<PluginEntry>
    fun discoverPluginsFromCurrentMods(): List<PluginEntry>
    fun getEnabledCurrentMods(): List<Mod>
    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry>
    fun saveCurrentPlugins(plugins: List<PluginEntry>)
}

internal class PluginSynchronizationEngineAdapter(
    private val engine: ModEngine
) : PluginSynchronizationEngine {
    override fun loadPlugins(): List<PluginEntry> = engine.loadPlugins()

    override fun scanDataFolderPlugins(gameId: String): List<PluginEntry> =
        engine.scanDataFolderPlugins(gameId)

    override fun discoverPluginsFromCurrentMods(): List<PluginEntry> =
        engine.discoverPluginsFromCurrentMods()

    override fun getEnabledCurrentMods(): List<Mod> = engine.getEnabledCurrentMods()

    override fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> =
        engine.normalizePluginPriorities(plugins)

    override fun saveCurrentPlugins(plugins: List<PluginEntry>) {
        engine.saveCurrentPlugins(plugins)
    }
}

internal class PluginSynchronizationWorkflow(
    private val appendLog: (String) -> Unit
) {
    fun sync(
        engine: PluginSynchronizationEngine,
        selectedGameId: String
    ) {
        appendLog("Scanning plugins from current mod state and target Data folder...")

        val previous = engine.loadPlugins().associateBy { it.normalizedPath }
        val dataFolderPlugins = engine.scanDataFolderPlugins(selectedGameId)
        val managedPlugins = engine.discoverPluginsFromCurrentMods()
        val enabledModCount = engine.getEnabledCurrentMods().size

        val officialDataPlugins = dataFolderPlugins.filter {
            it.sourceType == "base_game" || it.sourceType == "official_dlc"
        }
        val unmanagedDataPlugins = dataFolderPlugins.filter {
            it.sourceType == "unmanaged_data"
        }
        val dataPluginPaths = dataFolderPlugins.map { it.normalizedPath }.toSet()

        val managedMerged = managedPlugins.map { managed ->
            val existing = previous[managed.normalizedPath]
            managed.copy(
                enabled = existing?.enabled ?: true,
                priority = existing?.priority ?: Int.MAX_VALUE,
                sourceType = "managed_mod",
                locked = false,
                filePresentInDataFolder = managed.normalizedPath in dataPluginPaths
            )
        }

        val unmanagedMerged = unmanagedDataPlugins.map { unmanaged ->
            val existing = previous[unmanaged.normalizedPath]
            unmanaged.copy(
                enabled = existing?.enabled ?: false,
                priority = existing?.priority ?: Int.MAX_VALUE,
                locked = false,
                filePresentInDataFolder = true
            )
        }

        val officialMerged = officialDataPlugins
            .sortedBy { it.priority }
            .map { official ->
                official.copy(
                    enabled = true,
                    locked = true,
                    filePresentInDataFolder = true
                )
            }
        val officialPaths = officialMerged.map { it.normalizedPath }.toSet()

        val nonOfficial = (managedMerged + unmanagedMerged)
            .filterNot { it.normalizedPath in officialPaths }
            .distinctBy { it.normalizedPath }
            .sortedWith(
                compareBy<PluginEntry> { previous[it.normalizedPath]?.priority ?: it.priority }
                    .thenBy { it.pluginName.lowercase() }
            )

        val normalized = engine.normalizePluginPriorities(officialMerged + nonOfficial)
        engine.saveCurrentPlugins(normalized)

        appendLog("Selected game: $selectedGameId")
        appendLog("Enabled mod count scanned for plugins: $enabledModCount")
        appendLog("Data folder plugin count: ${dataFolderPlugins.size}")
        appendLog("Managed plugin count: ${managedPlugins.size}")
        appendLog("Plugin scan complete. Plugin count: ${normalized.size}")
    }
}
