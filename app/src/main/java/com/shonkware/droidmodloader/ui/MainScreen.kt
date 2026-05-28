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
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.index.ModFilePreview

import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry

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
    val selectedTreeUriText: String,
    val selectedRootTreeUriText: String,
    val realDeployEnabled: Boolean,
    val logText: String,
    val setupComplete: Boolean,
    val profileNameText: String,
    val setupGameId: String,
    val setupTargetPathText: String,
    val setupRealDeployEnabled: Boolean,
    val activeProfileName: String,
    val profileOptions: List<GameProfile>,
    val activeProfileId: String?,
    val newProfileNameText: String,
    val newProfileGameId: String,
    val newProfileRealDeployEnabled: Boolean,
    val showProfileDialog: Boolean,
    val newProfileTreeUriText: String,
    val operationInProgress: Boolean,
    val activeOperationText: String,
    val modContentIndexes: Map<String, ModContentIndex>,
    val pendingArchiveInstall: PreparedArchiveInstall?,
    val selectedInstallerOptionIds: Set<String>,
    val showInstallerDialog: Boolean,
    val installerDialogFullscreen: Boolean,
    val selectedModFilePreview: ModFilePreview?,
    val showModFilePreviewDialog: Boolean,
    val modFilePreviewFullscreen: Boolean,
    val secondScreenEnabled: Boolean,
    val fullscreenPanel: FullscreenPanel,
    val overwriteEntries: List<OverwriteEntry>,
    val showOverwriteDialog: Boolean,
    val overwriteBaselineExists: Boolean,
    val overwriteMessage: String,

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
    val onRealDeployChanged: (Boolean) -> Unit,
    val onPickTargetFolder: () -> Unit,
    val onPickRootTargetFolder: () -> Unit,
    val onSaveSettings: () -> Unit,
    val onShareLogs: () -> Unit,

    val onProfileNameChanged: (String) -> Unit,
    val onSetupGameChanged: (String) -> Unit,
    val onSetupTargetPathChanged: (String) -> Unit,
    val onSetupRealDeployChanged: (Boolean) -> Unit,
    val onCompleteSetup: () -> Unit,

    val onSelectProfile: (String) -> Unit,
    val onNewProfileNameChanged: (String) -> Unit,
    val onNewProfileGameChanged: (String) -> Unit,
    val onNewProfileRealDeployChanged: (Boolean) -> Unit,
    val onCreateAdditionalProfile: () -> Unit,

    val onOpenProfileDialog: () -> Unit,
    val onCloseProfileDialog: () -> Unit,
    val onPickNewProfileTargetFolder: () -> Unit,
    val onDeleteProfile: (String) -> Unit,

    val onToggleInstallerOption: (String) -> Unit,
    val onConfirmInstaller: () -> Unit,
    val onCancelInstaller: () -> Unit,
    val onToggleInstallerFullscreen: () -> Unit,

    val onViewModFiles: (String) -> Unit,
    val onCloseModFilePreview: () -> Unit,
    val onToggleModFilePreviewFullscreen: () -> Unit,

    val onToggleSecondScreen: () -> Unit,

    val onOpenModsFullscreen: () -> Unit,
    val onOpenPluginsFullscreen: () -> Unit,
    val onCloseFullscreenPanel: () -> Unit,

    val onApplyModOrder: (List<String>) -> Unit,
    val onApplyPluginOrder: (List<String>) -> Unit,

    val onOpenOverwriteFolder: () -> Unit,
    val onCloseOverwriteFolder: () -> Unit,
    val onRepairV050Artifacts: () -> Unit = {},

    val onBuildResolvedDataGraph: () -> Unit = {},

    val onBuildDeploymentPlan: () -> Unit = {},
)

@Composable
private fun MainDashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
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
                selectedGameId = state.selectedGameId,
                selectedTreeUriText = state.selectedTreeUriText,
                selectedRootTreeUriText = state.selectedRootTreeUriText,
                realDeployEnabled = state.realDeployEnabled,
                lastOperationStatus = state.lastOperationStatus,
                summaryText = state.summaryText,
                onOpenProfileDialog = actions.onOpenProfileDialog
            )

            QuickStartCard()

            MainActionsCard(
                operationInProgress = state.operationInProgress,
                onImportArchive = actions.onImportArchive,
                onDeployMods = actions.onDeployMods,
                onWriteLoadOrderFiles = actions.onWriteLoadOrderFiles
            )

            ModsCard(
                mods = state.mods,
                modContentIndexes = state.modContentIndexes,
                onToggleMod = actions.onToggleMod,
                onMoveModUp = actions.onMoveModUp,
                onMoveModDown = actions.onMoveModDown,
                onDeleteMod = actions.onDeleteMod,
                onViewModFiles = actions.onViewModFiles,
                onOpenFullscreen = actions.onOpenModsFullscreen,
                onOpenOverwriteFolder = actions.onOpenOverwriteFolder
            )

            PluginsCard(
                plugins = state.plugins,
                onTogglePlugin = actions.onTogglePlugin,
                onMovePluginUp = actions.onMovePluginUp,
                onMovePluginDown = actions.onMovePluginDown,
                onOpenFullscreen = actions.onOpenPluginsFullscreen
            )

            DeploymentSettingsCard(
                selectedTreeUriText = state.selectedTreeUriText,
                selectedRootTreeUriText = state.selectedRootTreeUriText,
                realDeployEnabled = state.realDeployEnabled,
                secondScreenEnabled = state.secondScreenEnabled,
                onRealDeployChanged = actions.onRealDeployChanged,
                onPickTargetFolder = actions.onPickTargetFolder,
                onPickRootTargetFolder = actions.onPickRootTargetFolder,
                onSaveSettings = actions.onSaveSettings,
                onToggleSecondScreen = actions.onToggleSecondScreen,
            )

            ReportCard(
                logText = state.logText,
                onShareLogs = actions.onShareLogs
            )

            DeveloperToolsCard(
                operationInProgress = state.operationInProgress,
                onBuildResolvedDataGraph = actions.onBuildResolvedDataGraph,
                onBuildDeploymentPlan = actions.onBuildDeploymentPlan,
                onRepairV050Artifacts = actions.onRepairV050Artifacts,
            )
        }
    }
}

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

    when (state.fullscreenPanel) {
        FullscreenPanel.MODS -> {
            ModsPanelDialog(
                mods = state.mods,
                modContentIndexes = state.modContentIndexes,
                onToggleMod = actions.onToggleMod,
                onMoveModUp = actions.onMoveModUp,
                onMoveModDown = actions.onMoveModDown,
                onDeleteMod = actions.onDeleteMod,
                onViewModFiles = actions.onViewModFiles,
                onApplyModOrder = actions.onApplyModOrder,
                onOpenOverwriteFolder = actions.onOpenOverwriteFolder,
                onClose = actions.onCloseFullscreenPanel
            )
        }

        FullscreenPanel.PLUGINS -> {
            PluginsPanelDialog(
                plugins = state.plugins,
                onTogglePlugin = actions.onTogglePlugin,
                onMovePluginUp = actions.onMovePluginUp,
                onMovePluginDown = actions.onMovePluginDown,
                onApplyPluginOrder = actions.onApplyPluginOrder,
                onClose = actions.onCloseFullscreenPanel
            )
        }

        FullscreenPanel.NONE -> {
            MainDashboardScreen(
                state = state,
                actions = actions
            )
        }
    }

    if (state.showProfileDialog) {
        ProfileManagerDialog(
            profiles = state.profileOptions,
            activeProfileId = state.activeProfileId,
            newProfileNameText = state.newProfileNameText,
            newProfileGameId = state.newProfileGameId,
            newProfileTreeUriText = state.newProfileTreeUriText,
            newProfileRealDeployEnabled = state.newProfileRealDeployEnabled,
            onSelectProfile = actions.onSelectProfile,
            onDeleteProfile = actions.onDeleteProfile,
            onNewProfileNameChanged = actions.onNewProfileNameChanged,
            onNewProfileGameChanged = actions.onNewProfileGameChanged,
            onPickNewProfileTargetFolder = actions.onPickNewProfileTargetFolder,
            onNewProfileRealDeployChanged = actions.onNewProfileRealDeployChanged,
            onCreateAdditionalProfile = actions.onCreateAdditionalProfile,
            onClose = actions.onCloseProfileDialog,
            gameOptions = state.gameOptions
        )
    }

    if (state.showInstallerDialog && state.pendingArchiveInstall != null) {
        InstallerChoiceDialog(
            prepared = state.pendingArchiveInstall,
            selectedOptionIds = state.selectedInstallerOptionIds,
            fullscreen = false,
            onToggleOption = actions.onToggleInstallerOption,
            onConfirm = actions.onConfirmInstaller,
            onCancel = actions.onCancelInstaller,
            onToggleFullscreen = {}
        )
    }

    if (state.showModFilePreviewDialog && state.selectedModFilePreview != null) {
        ModFilePreviewDialog(
            preview = state.selectedModFilePreview,
            fullscreen = false,
            onClose = actions.onCloseModFilePreview,
            onToggleFullscreen = {}
        )
    }

    if (state.showOverwriteDialog) {
        OverwriteDialog(
            entries = state.overwriteEntries,
            baselineExists = state.overwriteBaselineExists,
            message = state.overwriteMessage,
            onClose = actions.onCloseOverwriteFolder
        )
    }
}