package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.MainActivityUiState

internal class SelectedFolderConfigurationCoordinator(
    private val state: MainActivityUiState,
    private val runOnUiThreadBlocking: (() -> Unit) -> Unit,
    private val saveSelectedGameConfig: () -> Unit,
    private val saveActiveProfile: () -> Unit,
    private val ensureDataBaselineIfMissing: (String) -> Unit,
    private val refreshDashboard: () -> Unit,
    private val appendLog: (String) -> Unit
) {
    fun saveDataFolder(path: String) {
        runOnUiThreadBlocking {
            state.targetPathText = path
            state.selectedDataPathText = path
            state.dataPathReselectionRequired = false
            state.realDeployEnabledState = true
        }

        saveSelectedGameConfig()
        saveActiveProfile()
        ensureDataBaselineIfMissing("target folder selected")
        refreshDashboard()
        appendLog("Saved direct Data folder path for ${state.selectedGameId}: $path")
    }

    fun saveGameRoot(path: String) {
        runOnUiThreadBlocking {
            state.rootTargetPathText = path
            state.selectedRootPathText = path
            state.rootPathReselectionRequired = false
            state.realDeployEnabledState = true
        }

        saveSelectedGameConfig()
        saveActiveProfile()
        refreshDashboard()
        appendLog("Saved direct Game Root path for ${state.selectedGameId}: $path")
    }
}
