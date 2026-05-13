package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.model.GameProfile

data class DashboardUiState(
    val appName: String,
    val versionLabel: String,
    val developerModeEnabled: Boolean,
    val lastOperationStatus: String,
    val summaryText: String,
    val mods: List<Mod>,
    val plugins: List<PluginEntry>,
    val gameOptions: List<String>,
    val selectedGameId: String,
    val targetPathText: String,
    val selectedTreeUriText: String,
    val realDeployEnabled: Boolean,
    val logText: String,
    val setupComplete: Boolean,
    val profileNameText: String,
    val setupGameId: String,
    val setupTargetPathText: String,
    val setupRealDeployEnabled: Boolean,
    val activeProfileName: String,
    val profileOptions: List<GameProfile>
)

data class DashboardActions(
    val onVersionTap: () -> Unit,
    val onImportArchive: () -> Unit,
    val onDeployMods: () -> Unit,
    val onWriteLoadOrderFiles: () -> Unit,
    val onToggleMod: (String) -> Unit,
    val onMoveModUp: (String) -> Unit,
    val onMoveModDown: (String) -> Unit,
    val onDeleteMod: (Mod) -> Unit,
    val onTogglePlugin: (String) -> Unit,
    val onMovePluginUp: (String) -> Unit,
    val onMovePluginDown: (String) -> Unit,
    val onSelectGame: (String) -> Unit,
    val onTargetPathChanged: (String) -> Unit,
    val onRealDeployChanged: (Boolean) -> Unit,
    val onPickTargetFolder: () -> Unit,
    val onSaveSettings: () -> Unit,
    val onShareLogs: () -> Unit,
    val onProfileNameChanged: (String) -> Unit,
    val onSetupGameChanged: (String) -> Unit,
    val onSetupTargetPathChanged: (String) -> Unit,
    val onSetupRealDeployChanged: (Boolean) -> Unit,
    val onCompleteSetup: () -> Unit
)

@Composable
fun DroidModLoaderScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
    if (!state.setupComplete) {
        SetupScreen(
            state = state,
            actions = actions
        )
        return
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderCard(
                appName = state.appName,
                versionLabel = state.versionLabel,
                onVersionTap = actions.onVersionTap
            )

            StatusCard(
                activeProfileName = state.activeProfileName,
                lastOperationStatus = state.lastOperationStatus,
                summaryText = state.summaryText
            )

            QuickStartCard()

            MainActionsCard(
                onImportArchive = actions.onImportArchive,
                onDeployMods = actions.onDeployMods,
                onWriteLoadOrderFiles = actions.onWriteLoadOrderFiles
            )

            ModsCard(
                mods = state.mods,
                onToggleMod = actions.onToggleMod,
                onMoveModUp = actions.onMoveModUp,
                onMoveModDown = actions.onMoveModDown,
                onDeleteMod = actions.onDeleteMod
            )

            PluginsCard(
                plugins = state.plugins,
                onTogglePlugin = actions.onTogglePlugin,
                onMovePluginUp = actions.onMovePluginUp,
                onMovePluginDown = actions.onMovePluginDown
            )

            DeploymentSettingsCard(
                gameOptions = state.gameOptions,
                selectedGameId = state.selectedGameId,
                onSelectGame = actions.onSelectGame,
                targetPathText = state.targetPathText,
                onTargetPathChanged = actions.onTargetPathChanged,
                selectedTreeUriText = state.selectedTreeUriText,
                realDeployEnabled = state.realDeployEnabled,
                onRealDeployChanged = actions.onRealDeployChanged,
                onPickTargetFolder = actions.onPickTargetFolder,
                onSaveSettings = actions.onSaveSettings
            )

            ReportCard(
                logText = state.logText,
                onShareLogs = actions.onShareLogs
            )

            if (state.developerModeEnabled) {
                DeveloperToolsCard()
            }
        }
    }
}