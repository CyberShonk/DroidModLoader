package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal interface PluginManagementEngine {
    fun getCurrentPlugins(): List<PluginEntry>
    fun loadPlugins(): List<PluginEntry>
    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry>
    fun saveCurrentPlugins(plugins: List<PluginEntry>)
    fun applyPluginPriorityOrder(orderedPluginPaths: List<String>)
    fun exportSavedPluginOutputs(): Pair<String, String>
    fun getPluginOutputFilePaths(): Pair<String, String>
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

    override fun exportSavedPluginOutputs(): Pair<String, String> {
        return engine.exportSavedPluginOutputs()
    }

    override fun getPluginOutputFilePaths(): Pair<String, String> {
        return engine.getPluginOutputFilePaths()
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
            appendLog("Ignoring plugin file write request: operation already in progress.")
            return
        }

        beginOperation("Writing plugin files...")

        val engine = createEngine()
        if (engine == null) {
            failOperation("Writing plugin files failed: engine could not be created.", null)
            return
        }

        try {
            val savedPlugins = engine.loadPlugins().sortedBy { it.priority }

            if (savedPlugins.isEmpty()) {
                appendLog("No saved plugin list found. Refreshing plugin list once before writing files.")
                engine.syncPlugins()
            }

            val pluginsAfterFallback = engine.loadPlugins().sortedBy { it.priority }

            if (pluginsAfterFallback.isEmpty()) {
                appendLog("No plugins available to write.")
                appendLog("RESULT: FAIL")
                failOperation("Writing plugin files failed: no plugins available.", null)
                return
            }

            val (pluginsTxt, loadorderTxt) = engine.exportSavedPluginOutputs()
            val (pluginsTxtPath, loadorderTxtPath) = engine.getPluginOutputFilePaths()

            val enabledPluginCount = pluginsAfterFallback.count { it.enabled }

            appendLog("Plugin files written from saved plugin list.")
            appendLog("Plugin count: ${pluginsAfterFallback.size}")
            appendLog("Enabled plugin count: $enabledPluginCount")
            appendLog("plugins.txt path: $pluginsTxtPath")
            appendLog("loadorder.txt path: $loadorderTxtPath")
            appendLog("plugins.txt line count: ${pluginsTxt.lines().count { it.isNotBlank() }}")
            appendLog("loadorder.txt line count: ${loadorderTxt.lines().count { it.isNotBlank() }}")
            appendLog("RESULT: PASS")

            finishOperation("Plugin files written successfully.")
        } catch (e: Exception) {
            appendError("Write plugin files workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Writing plugin files failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Write Plugin Files Workflow End -----")
    }
}
