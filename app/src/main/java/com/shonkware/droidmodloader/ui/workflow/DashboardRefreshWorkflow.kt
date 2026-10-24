package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.flags.ModFlag
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import com.shonkware.droidmodloader.engine.deploy.health.ScopedIntegrityObservation
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal interface DashboardRefreshEngine {
    fun getCurrentMods(): List<Mod>
    fun getCurrentPlugins(): List<PluginEntry>
    fun hasSavedState(): Boolean
    fun buildDeploymentHealth(profileId: String, gameId: String, observation: ScopedIntegrityObservation?,
        contentIndexes: Map<String, ModContentIndex>): DeploymentHealth
    fun indexModContent(mod: Mod): ModContentIndex
    fun evaluateModFlags(mod: Mod, contentIndex: ModContentIndex): Set<ModFlag>
}

internal class DashboardRefreshEngineAdapter(
    private val engine: ModEngine
) : DashboardRefreshEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()
    override fun getCurrentPlugins(): List<PluginEntry> = engine.getCurrentPlugins()
    override fun hasSavedState(): Boolean = engine.hasSavedState()
    override fun buildDeploymentHealth(profileId: String, gameId: String, observation: ScopedIntegrityObservation?,
        contentIndexes: Map<String, ModContentIndex>) = engine.buildDeploymentHealth(profileId, gameId, observation, contentIndexes)

    override fun indexModContent(mod: Mod): ModContentIndex = engine.indexModContent(mod)
    override fun evaluateModFlags(mod: Mod, contentIndex: ModContentIndex): Set<ModFlag> =
        engine.evaluateModFlags(mod, contentIndex)
}

internal data class DashboardRefreshResult(
    val mods: List<Mod>,
    val plugins: List<PluginEntry>,
    val modContentIndexes: Map<String, ModContentIndex>,
    val modFlags: Map<String, Set<ModFlag>>,
    val summaryText: String,
    val deploymentHealth: DeploymentHealth? = null
)

internal class DashboardRefreshWorkflow {
    fun build(
        engine: DashboardRefreshEngine,
        selectedGameId: String,
        profileId: String = "",
        observation: ScopedIntegrityObservation? = null
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
        val summary = buildString {
            appendLine("Installed mods: $installedCount")
            appendLine("Enabled mods: $enabledCount")
            appendLine("Plugins: ${plugins.size}")
            appendLine("State source: $stateSourceText")
            appendLine("Highest priority mod: $highestPriorityMod")
        }
        val contentIndexes = mods.associate { mod ->
            mod.id to engine.indexModContent(mod)
        }
        val modFlags = mods.associate { mod ->
            mod.id to engine.evaluateModFlags(
                mod = mod,
                contentIndex = contentIndexes.getValue(mod.id)
            )
        }

        return DashboardRefreshResult(
            mods = mods,
            plugins = plugins,
            modContentIndexes = contentIndexes,
            modFlags = modFlags,
            summaryText = summary,
            deploymentHealth = engine.buildDeploymentHealth(profileId, selectedGameId, observation, contentIndexes)
        )
    }
}
