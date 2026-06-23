package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal interface DashboardRefreshEngine {
    fun getCurrentMods(): List<Mod>
    fun getCurrentPlugins(): List<PluginEntry>
    fun hasSavedState(): Boolean
    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig?
    fun validateTargetDataPath(path: String): Boolean
    fun indexModContent(mod: Mod): ModContentIndex
}

internal class DashboardRefreshEngineAdapter(
    private val engine: ModEngine
) : DashboardRefreshEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()
    override fun getCurrentPlugins(): List<PluginEntry> = engine.getCurrentPlugins()
    override fun hasSavedState(): Boolean = engine.hasSavedState()
    override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? =
        engine.getGameDeploymentConfig(gameId)

    override fun validateTargetDataPath(path: String): Boolean =
        engine.validateTargetDataPath(path)

    override fun indexModContent(mod: Mod): ModContentIndex = engine.indexModContent(mod)
}

internal data class DashboardRefreshResult(
    val mods: List<Mod>,
    val plugins: List<PluginEntry>,
    val modContentIndexes: Map<String, ModContentIndex>,
    val summaryText: String
)

internal class DashboardRefreshWorkflow {
    fun build(
        engine: DashboardRefreshEngine,
        selectedGameId: String
    ): DashboardRefreshResult {
        val mods = engine.getCurrentMods().sortedBy { it.priority }
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }
        val installedCount = mods.size
        val enabledCount = mods.count { it.enabled }
        val stateSourceText = when {
            engine.hasSavedState() -> "Saved state present"
            installedCount > 0 -> "Using folder-discovered state"
            else -> "No current mod state"
        }
        val highestPriorityMod = mods.lastOrNull()?.name ?: "None"
        val config = engine.getGameDeploymentConfig(selectedGameId)
        val deployMode = when {
            config == null -> "Simulated"
            config.realDeployEnabled && engine.validateTargetDataPath(config.targetDataPath) ->
                "Direct Path"
            else -> "Simulated"
        }
        val targetStatus = config
            ?.targetDataPath
            ?.takeIf { it.isNotBlank() }
            ?: "Not configured"
        val summary = buildString {
            appendLine("Installed mods: $installedCount")
            appendLine("Enabled mods: $enabledCount")
            appendLine("Plugins: ${plugins.size}")
            appendLine("State source: $stateSourceText")
            appendLine("Highest priority mod: $highestPriorityMod")
            appendLine("Deploy mode: $deployMode")
            appendLine("Target: $targetStatus")
        }
        val contentIndexes = mods.associate { mod ->
            mod.id to engine.indexModContent(mod)
        }

        return DashboardRefreshResult(
            mods = mods,
            plugins = plugins,
            modContentIndexes = contentIndexes,
            summaryText = summary
        )
    }
}
