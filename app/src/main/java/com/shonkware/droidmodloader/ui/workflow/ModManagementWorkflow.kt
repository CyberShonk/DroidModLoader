package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.UninstallResult
import com.shonkware.droidmodloader.engine.model.Mod

internal interface ModManagementEngine {
    fun getCurrentMods(): List<Mod>
    fun saveCurrentMods(mods: List<Mod>)
    fun uninstallModAndApplyDiff(modId: String): UninstallResult
    fun applyModPriorityOrder(orderedModIds: List<String>)
    fun syncPlugins()
}

internal class ModManagementEngineAdapter(
    private val engine: ModEngine,
    private val syncPlugins: () -> Unit
) : ModManagementEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()

    override fun saveCurrentMods(mods: List<Mod>) {
        engine.saveCurrentMods(mods)
    }

    override fun uninstallModAndApplyDiff(modId: String): UninstallResult {
        return engine.uninstallModAndApplyDiff(modId)
    }

    override fun applyModPriorityOrder(orderedModIds: List<String>) {
        engine.applyModPriorityOrder(orderedModIds)
    }

    override fun syncPlugins() {
        syncPlugins.invoke()
    }
}

internal class ModManagementWorkflow(
    private val withEngine: ((ModManagementEngine) -> Unit) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val refreshDashboard: () -> Unit
) {
    fun toggleModEnabled(modId: String) {
        withEngine { engine ->
            val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()
            val index = mods.indexOfFirst { it.id == modId }

            if (index == -1) {
                appendError("Could not find mod: $modId", null)
                return@withEngine
            }

            mods[index] = mods[index].copy(enabled = !mods[index].enabled)
            engine.saveCurrentMods(normalizePriorities(mods))
            appendLog("Toggled enabled state for $modId")
            engine.syncPlugins()
            refreshDashboard()
        }
    }

    fun moveModUp(modId: String) {
        withEngine { engine ->
            val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()
            val index = mods.indexOfFirst { it.id == modId }

            if (index <= 0) {
                appendLog("Cannot move up: $modId")
                return@withEngine
            }

            val previous = mods[index - 1]
            mods[index - 1] = mods[index]
            mods[index] = previous

            engine.saveCurrentMods(normalizePriorities(mods))
            appendLog("Moved up: $modId")
            engine.syncPlugins()
            refreshDashboard()
        }
    }

    fun moveModDown(modId: String) {
        withEngine { engine ->
            val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()
            val index = mods.indexOfFirst { it.id == modId }

            if (index == -1 || index >= mods.lastIndex) {
                appendLog("Cannot move down: $modId")
                return@withEngine
            }

            val next = mods[index + 1]
            mods[index + 1] = mods[index]
            mods[index] = next

            engine.saveCurrentMods(normalizePriorities(mods))
            appendLog("Moved down: $modId")
            engine.syncPlugins()
            refreshDashboard()
        }
    }

    fun deleteInstalledMod(modId: String) {
        appendLog("----- Delete Installed Mod Workflow Start -----")
        appendLog("Requested delete for mod: $modId")

        withEngine { engine ->
            try {
                val result = engine.uninstallModAndApplyDiff(modId)

                if (!result.removed) {
                    appendError("Could not remove mod: $modId", null)
                    appendLog("RESULT: FAIL")
                    updateLastOperationStatus("Delete mod failed: could not remove $modId.")
                    appendLog("----- Delete Installed Mod Workflow End -----")
                    return@withEngine
                }

                appendLog("Deleted mod: ${result.removedModId}")
                appendLog("Deleted installed mod files: ${result.deletedFileCount}")
                appendLog("Deploy again to remove this mod's files from the selected game Data folder.")

                engine.syncPlugins()

                appendLog("RESULT: PASS")
                updateLastOperationStatus("Delete mod succeeded: ${result.removedModId}")
                refreshDashboard()
            } catch (e: Exception) {
                appendError("Delete installed mod workflow failed: ${e.message}", e)
                appendLog("RESULT: FAIL")
                updateLastOperationStatus("Delete mod failed: ${e.message}")
            }

            appendLog("----- Delete Installed Mod Workflow End -----")
        }
    }

    fun applyModOrder(orderedModIds: List<String>) {
        withEngine { engine ->
            try {
                engine.applyModPriorityOrder(orderedModIds)
                engine.syncPlugins()
                appendLog("Applied dragged mod order.")
                refreshDashboard()
            } catch (e: Exception) {
                appendError("Could not apply dragged mod order: ${e.message}", e)
            }
        }
    }

    private fun normalizePriorities(mods: List<Mod>): List<Mod> {
        return mods.mapIndexed { index, mod ->
            mod.copy(priority = index + 1)
        }
    }
}
