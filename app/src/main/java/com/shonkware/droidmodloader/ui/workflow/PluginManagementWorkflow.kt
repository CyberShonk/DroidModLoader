package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.plugins.PluginApplicationResult
import com.shonkware.droidmodloader.engine.plugins.PluginLoadOrderMechanism

internal interface PluginManagementEngine {
    fun getCurrentPlugins(): List<PluginEntry>
    fun loadPlugins(): List<PluginEntry>
    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry>
    fun saveCurrentPlugins(plugins: List<PluginEntry>)
    fun applyPluginPriorityOrder(orderedPluginPaths: List<String>)
    fun applySavedPluginConfiguration(gameId: String): PluginApplicationResult
    fun syncPlugins()
}

internal class PluginManagementEngineAdapter(
    private val engine: ModEngine,
    private val syncPlugins: () -> Unit
) : PluginManagementEngine {
    override fun getCurrentPlugins(): List<PluginEntry> = engine.getCurrentPlugins()

    override fun loadPlugins(): List<PluginEntry> = engine.loadPlugins()

    override fun normalizePluginPriorities(
        plugins: List<PluginEntry>
    ): List<PluginEntry> = engine.normalizePluginPriorities(plugins)

    override fun saveCurrentPlugins(plugins: List<PluginEntry>) {
        engine.saveCurrentPlugins(plugins)
    }

    override fun applyPluginPriorityOrder(orderedPluginPaths: List<String>) {
        engine.applyPluginPriorityOrder(orderedPluginPaths)
    }

    override fun applySavedPluginConfiguration(gameId: String): PluginApplicationResult {
        return engine.applySavedPluginConfiguration(gameId)
    }

    override fun syncPlugins() {
        syncPlugins.invoke()
    }
}

internal class PluginManagementWorkflow(
    private val createEngine: () -> PluginManagementEngine?,
    private val isOperationInProgress: () -> Boolean,
    private val beginOperation: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val selectedGameIdProvider: () -> String,
    private val refreshDashboard: () -> Unit
) {
    fun togglePluginEnabled(normalizedPath: String) {
        val engine = createEngine() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index == -1) {
            appendError("Could not find plugin: $normalizedPath", null)
            return
        }

        val updated = plugins[index].copy(enabled = !plugins[index].enabled)
        plugins[index] = updated

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Toggled plugin enabled state for ${updated.pluginName}")
        updateLastOperationStatus("Plugin updated: ${updated.pluginName}")
        refreshDashboard()
    }

    fun movePluginUp(normalizedPath: String) {
        val engine = createEngine() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index <= 0) {
            appendLog("Cannot move plugin up: $normalizedPath")
            return
        }

        val previous = plugins[index - 1]
        plugins[index - 1] = plugins[index]
        plugins[index] = previous

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin up: ${plugins[index - 1].pluginName}")
        updateLastOperationStatus("Plugin moved up.")
        refreshDashboard()
    }

    fun movePluginDown(normalizedPath: String) {
        val engine = createEngine() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index == -1 || index >= plugins.lastIndex) {
            appendLog("Cannot move plugin down: $normalizedPath")
            return
        }

        val next = plugins[index + 1]
        plugins[index + 1] = plugins[index]
        plugins[index] = next

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin down: ${plugins[index + 1].pluginName}")
        updateLastOperationStatus("Plugin moved down.")
        refreshDashboard()
    }

    fun applyPluginOrder(orderedPluginPaths: List<String>) {
        val engine = createEngine() ?: return

        try {
            engine.applyPluginPriorityOrder(orderedPluginPaths)
            appendLog("Applied dragged plugin order.")
            refreshDashboard()
        } catch (e: Exception) {
            appendError("Could not apply dragged plugin order: ${e.message}", e)
        }
    }

    fun writeLoadOrderFiles() {
        if (isOperationInProgress()) {
            appendLog("Ignoring plugin configuration request: operation already in progress.")
            return
        }

        beginOperation("Applying plugin configuration...")

        val engine = createEngine()
        if (engine == null) {
            failOperation("Applying plugin configuration failed: engine could not be created.", null)
            return
        }

        try {
            val savedPlugins = engine.loadPlugins().sortedBy { it.priority }

            if (savedPlugins.isEmpty()) {
                appendLog(
                    "No saved plugin list found. Refreshing plugin list once before applying configuration."
                )
                engine.syncPlugins()
            }

            val pluginsAfterFallback = engine.loadPlugins().sortedBy { it.priority }

            if (pluginsAfterFallback.isEmpty()) {
                appendLog("No plugins available to apply.")
                appendLog("RESULT: FAIL")
                failOperation("Applying plugin configuration failed: no plugins available.", null)
                return
            }

            val selectedGameId = selectedGameIdProvider()
            val result = engine.applySavedPluginConfiguration(selectedGameId)

            appendLog("Plugin configuration applied from saved plugin list.")
            appendLog("Selected game: ${result.gameId}")
            appendLog("Plugin order mechanism: ${result.mechanism.logLabel()}")
            appendLog("Plugin count: ${result.pluginCount}")
            appendLog("Enabled plugin count: ${result.enabledPluginCount}")
            appendLog("plugins.txt path: ${result.pluginsTxtPath}")
            appendLog(
                "plugins.txt line count: " +
                    result.pluginsTxtContent.lines().count { it.isNotBlank() }
            )

            if (result.loadorderTxtPath != null && result.loadorderTxtContent != null) {
                appendLog("loadorder.txt path: ${result.loadorderTxtPath}")
                appendLog(
                    "loadorder.txt line count: " +
                        result.loadorderTxtContent.lines().count { it.isNotBlank() }
                )
            } else {
                appendLog("loadorder.txt not written; plugin timestamps are authoritative.")
            }

            if (result.mechanism == PluginLoadOrderMechanism.FILE_TIMESTAMPS) {
                appendLog("Timestamped plugin count: ${result.timestampedPluginCount}")
                appendLog("Timestamp Data folder: ${result.timestampDataFolderPath.orEmpty()}")
            }

            appendLog("RESULT: PASS")
            finishOperation("Plugin configuration applied successfully.")
        } catch (e: Exception) {
            appendError("Apply plugin configuration workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Applying plugin configuration failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Apply Plugin Configuration Workflow End -----")
    }

    private fun PluginLoadOrderMechanism.logLabel(): String {
        return when (this) {
            PluginLoadOrderMechanism.TEXT_FILES -> "text files"
            PluginLoadOrderMechanism.FILE_TIMESTAMPS -> "file timestamps"
        }
    }

}
