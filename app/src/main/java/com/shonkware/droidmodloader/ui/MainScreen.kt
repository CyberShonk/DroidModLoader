package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.attention.AppAttention
import com.shonkware.droidmodloader.attention.AppAttentionAction
import com.shonkware.droidmodloader.attention.AppAttentionId
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryFact
import com.shonkware.droidmodloader.engine.flags.ModFlag
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.attention.AppAttentionPresentationMapper
import com.shonkware.droidmodloader.ui.theme.DmlMatteBackground
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState

data class DashboardUiState(
    val appName: String,
    val versionLabel: String,
    val developerModeEnabled: Boolean,
    val lastOperationStatus: String,
    val summaryText: String,
    val deploymentHealth: DeploymentHealth? = null,
    val mods: List<Mod>,
    val plugins: List<PluginEntry>,
    val gameOptions: List<String>,
    val selectedGameId: String,
    val selectedDataPathText: String,
    val selectedRootPathText: String,
    val realDeployEnabled: Boolean,
    val logText: String,
    val setupComplete: Boolean,
    val profileNameText: String,
    val setupGameId: String,
    val setupTargetPathText: String,
    val setupInstallationResolved: Boolean,
    val activeProfileName: String,
    val profileOptions: List<GameProfile>,
    val activeProfileId: String?,
    val newProfileNameText: String,
    val newProfileGameId: String,
    val newProfileInstallationResolved: Boolean,
    val showProfileDialog: Boolean,
    val newProfileDataPathText: String,
    val operationInProgress: Boolean,
    val integrityCheckInProgress: Boolean = false,
    val activeOperationText: String,
    val modContentIndexes: Map<String, ModContentIndex>,
    val modFlags: Map<String, Set<ModFlag>>,
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
    val deployRecoveryWarningText: String = "",
    val deployRecoveryAttentionRequired: Boolean = false,
    val appAttention: List<AppAttention> = emptyList(),
    val dismissedAttentionIds: Set<AppAttentionId> = emptySet(),
    val suppressedAttentionPromptIds: Set<AppAttentionId> = emptySet(),
    val showDeployRecoveryDialog: Boolean = false,
    val showForceFullRedeployConfirmDialog: Boolean = false,
    val showArchiveFolderSetupDialog: Boolean = false,
    val archiveBrowserState: ArchiveBrowserUiState = ArchiveBrowserUiState(),
    val allFilesAccessRequired: Boolean = false,
    val allFilesAccessGranted: Boolean = true,
    val showDirectFolderBrowser: Boolean = false,
    val directFolderBrowserTitle: String = "Choose Folder",
    val directFolderBrowserRequiresWritable: Boolean = false,
    val directFolderBrowserState: DirectFolderBrowserState = DirectFolderBrowserState(),
    val archiveImportInProgress: Boolean,
    val setupRootTargetPathText: String = "",
    val newProfileRootPathText: String = "",
    val dataTargetReady: Boolean = false,
    val rootTargetReady: Boolean = false,
    val operationHistory: OperationHistoryPresentation = OperationHistoryPresentation(),
)

data class DashboardActions(
    val onVersionTap: () -> Unit,
    val onInstallMod: () -> Unit,
    val onChooseArchiveFolder: () -> Unit,
    val onDismissArchiveFolderSetup: () -> Unit,
    val onRefreshArchiveFolder: () -> Unit,
    val onChangeArchiveFolder: () -> Unit,
    val onInstallArchiveFromFolder: (String) -> Unit,
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
    val onPickTargetFolder: () -> Unit,
    val onSaveSettings: () -> Unit,
    val onShareLogs: () -> Unit,

    val onProfileNameChanged: (String) -> Unit,
    val onSetupGameChanged: (String) -> Unit,
    val onSetupTargetPathChanged: (String) -> Unit,
    val onCompleteSetup: () -> Unit,

    val onSelectProfile: (String) -> Unit,
    val onNewProfileNameChanged: (String) -> Unit,
    val onNewProfileGameChanged: (String) -> Unit,
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

    val onBuildResolvedDataGraph: () -> Unit = {},
    val onBuildDeploymentPlan: () -> Unit = {},
    val onVerifyManagedContent: () -> Unit = {},
    val onRefreshDeploymentHealth: () -> Unit = {},
    val onInspectStorage: () -> Unit = {},
    val onInspectEnvironment: () -> Unit = {},
    val onCancelIntegrityCheck: () -> Unit = {},
    val onShowArchiveLibrarySummary: () -> Unit = {},
    val onCleanOldArchiveCopies: () -> Unit = {},
    val onViewLastDeployJournal: () -> Unit = {},

    val onOpenDeployRecoveryDetails: () -> Unit = {},
    val onCloseDeployRecoveryDetails: () -> Unit = {},
    val onAttentionAction: (AppAttentionAction) -> Unit = {},
    val onDismissAttentionForSession: (AppAttentionId) -> Unit = {},
    val onSuppressAttentionPromptForSession: (AppAttentionId) -> Unit = {},

    val onMarkDeployRecoveryReviewed: () -> Unit = {},
    val onBuildFullRedeployPlan: () -> Unit = {},

    val onRequestForceFullRedeploy: () -> Unit = {},
    val onConfirmForceFullRedeploy: () -> Unit = {},
    val onCancelForceFullRedeploy: () -> Unit = {},
    val onRequestAllFilesAccess: () -> Unit = {},
    val onDirectFolderBrowserOpenPath: (String) -> Unit = {},
    val onDirectFolderBrowserNavigateUp: () -> Unit = {},
    val onDirectFolderBrowserSelectCurrent: () -> Unit = {},
    val onDirectFolderBrowserCancel: () -> Unit = {},
    val onCancelArchiveImport: () -> Unit,

)

@Composable
private fun MainDashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    scrollState: ScrollState
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        DmlMatteBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
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
                    dataTargetReady = state.dataTargetReady,
                    rootTargetReady = state.rootTargetReady,
                    realDeployEnabled = state.realDeployEnabled,
                    lastOperationStatus = state.lastOperationStatus,
                    summaryText = state.summaryText,
                    onOpenProfileDialog = actions.onOpenProfileDialog
                )

                state.appAttention
                    .filterNot { it.id in state.dismissedAttentionIds }
                    .forEach { attention ->
                        AppAttentionCard(
                            attention = attention,
                            onAction = actions.onAttentionAction,
                            onDismissForSession = {
                                actions.onDismissAttentionForSession(attention.id)
                            }
                        )
                    }

                RecentOperationsCard(state.operationHistory)

                QuickStartCard()

                MainActionsCard(
                    operationInProgress = state.operationInProgress,
                    onInstallMod = actions.onInstallMod,
                    onDeployMods = actions.onDeployMods,
                    onWriteLoadOrderFiles = actions.onWriteLoadOrderFiles
                )

                ModsCard(
                    mods = state.mods,
                    modContentIndexes = state.modContentIndexes,
                    modFlags = state.modFlags,
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
                    selectedDataPathText = state.selectedDataPathText,
                    selectedRootPathText = state.selectedRootPathText,
                    secondScreenEnabled = state.secondScreenEnabled,
                    onPickTargetFolder = actions.onPickTargetFolder,
                    onSaveSettings = actions.onSaveSettings,
                    onToggleSecondScreen = actions.onToggleSecondScreen
                )

                ReportCard(
                    logText = state.logText,
                    onShareLogs = actions.onShareLogs,
                    operationInProgress = state.operationInProgress,
                    integrityCheckInProgress = state.integrityCheckInProgress,
                    deploymentHealth = state.deploymentHealth,
                    onRefreshDeploymentHealth = actions.onRefreshDeploymentHealth,
                    onInspectStorage = actions.onInspectStorage,
                    onInspectEnvironment = actions.onInspectEnvironment,
                    onVerifyManagedContent = actions.onVerifyManagedContent,
                    onCancelIntegrityCheck = actions.onCancelIntegrityCheck
                )

                if (state.developerModeEnabled) {
                    DeveloperToolsCard(
                        operationInProgress = state.operationInProgress,
                        onBuildResolvedDataGraph = actions.onBuildResolvedDataGraph,
                        onBuildDeploymentPlan = actions.onBuildDeploymentPlan,
                        onShowArchiveLibrarySummary = actions.onShowArchiveLibrarySummary
                    )
                }

                RecoveryToolsCard(
                    operationInProgress = state.operationInProgress,
                    deployRecoveryAttentionRequired = state.deployRecoveryAttentionRequired,
                    deploymentBlockedByRecovery = state.deploymentHealth?.let {
                        it.installRecoveryRequired || it.recovery != DeploymentRecoveryFact.NONE
                    } ?: true,
                    onViewLastDeployJournal = actions.onViewLastDeployJournal,
                    onBuildFullRedeployPlan = actions.onBuildFullRedeployPlan,
                    onRequestForceFullRedeploy = actions.onRequestForceFullRedeploy,
                    onMarkDeployRecoveryReviewed = actions.onMarkDeployRecoveryReviewed
                )

                if (state.showDeployRecoveryDialog) {
                    AlertDialog(
                        onDismissRequest = actions.onCloseDeployRecoveryDetails,
                        title = {
                            Text("Recovery Details")
                        },
                        text = {
                            Text(state.deployRecoveryWarningText, modifier = Modifier.verticalScroll(rememberScrollState()))
                        },
                        confirmButton = {
                            TextButton(onClick = actions.onCloseDeployRecoveryDetails) {
                                Text("Close")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DroidModLoaderScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
    val dashboardScrollState = rememberScrollState()
    val modsListState = rememberLazyListState()
    val pluginsListState = rememberLazyListState()
    val archiveListState = rememberLazyListState()
    var archiveSearchText by rememberSaveable { mutableStateOf("") }
    var lastArchiveFolderPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.archiveBrowserState.folderPath) {
        val folderPath = state.archiveBrowserState.folderPath
        if (folderPath != lastArchiveFolderPath) {
            archiveSearchText = ""
            archiveListState.scrollToItem(0)
            lastArchiveFolderPath = folderPath
        }
    }

    LaunchedEffect(archiveSearchText) {
        archiveListState.scrollToItem(0)
    }

    if (state.allFilesAccessRequired && !state.allFilesAccessGranted) {
        AllFilesAccessDialog(
            onOpenSettings = actions.onRequestAllFilesAccess
        )
        return
    }

    if (state.showDirectFolderBrowser) {
        DirectFolderBrowserDialog(
            title = state.directFolderBrowserTitle,
            state = state.directFolderBrowserState,
            requireWritable = state.directFolderBrowserRequiresWritable,
            onOpenPath = actions.onDirectFolderBrowserOpenPath,
            onNavigateUp = actions.onDirectFolderBrowserNavigateUp,
            onSelectCurrent = actions.onDirectFolderBrowserSelectCurrent,
            onCancel = actions.onDirectFolderBrowserCancel
        )
    }

    if (!state.setupComplete) {
        SetupScreen(
            state = state,
            actions = actions
        )
        return
    }

    val blockingDialogVisible =
        state.showDirectFolderBrowser ||
            state.showProfileDialog ||
            state.showInstallerDialog ||
            state.showModFilePreviewDialog ||
            state.showOverwriteDialog ||
            state.showDeployRecoveryDialog ||
            state.showForceFullRedeployConfirmDialog ||
            state.fullscreenPanel != FullscreenPanel.NONE

    if (!blockingDialogVisible) {
        state.appAttention
            .firstOrNull { attention ->
                attention.id !in state.dismissedAttentionIds &&
                    attention.id !in state.suppressedAttentionPromptIds &&
                    AppAttentionPresentationMapper.map(attention).automaticPrompt
            }
            ?.let { attention ->
                AppAttentionPromptDialog(
                    attention = attention,
                    onAction = { action ->
                        actions.onSuppressAttentionPromptForSession(attention.id)
                        actions.onAttentionAction(action)
                    },
                    onNotNow = {
                        actions.onSuppressAttentionPromptForSession(attention.id)
                    }
                )
            }
    }

    when (state.fullscreenPanel) {
        FullscreenPanel.MODS -> {
            ModsPanelDialog(
                mods = state.mods,
                modContentIndexes = state.modContentIndexes,
                modFlags = state.modFlags,
                onToggleMod = actions.onToggleMod,
                onMoveModUp = actions.onMoveModUp,
                onMoveModDown = actions.onMoveModDown,
                onDeleteMod = actions.onDeleteMod,
                onViewModFiles = actions.onViewModFiles,
                onApplyModOrder = actions.onApplyModOrder,
                onOpenOverwriteFolder = actions.onOpenOverwriteFolder,
                onClose = actions.onCloseFullscreenPanel,
                listState = modsListState
            )
        }

        FullscreenPanel.PLUGINS -> {
            PluginsPanelDialog(
                plugins = state.plugins,
                onTogglePlugin = actions.onTogglePlugin,
                onMovePluginUp = actions.onMovePluginUp,
                onMovePluginDown = actions.onMovePluginDown,
                onApplyPluginOrder = actions.onApplyPluginOrder,
                onClose = actions.onCloseFullscreenPanel,
                listState = pluginsListState
            )
        }

        FullscreenPanel.ARCHIVES -> {
            ArchiveLibraryPanelDialog(
                state = state.archiveBrowserState,
                operationInProgress =
                    state.operationInProgress,
                archiveImportInProgress =
                    state.archiveImportInProgress,
                searchText = archiveSearchText,
                listState = archiveListState,
                onSearchTextChanged = {
                    archiveSearchText = it
                },
                onRefresh =
                    actions.onRefreshArchiveFolder,
                onChangeFolder =
                    actions.onChangeArchiveFolder,
                onInstallArchive =
                    actions.onInstallArchiveFromFolder,
                onCancelArchiveImport =
                    actions.onCancelArchiveImport,
                onClose =
                    actions.onCloseFullscreenPanel,
                lastOperationStatus = state.lastOperationStatus,
                onCleanOldArchiveCopies = actions.onCleanOldArchiveCopies
            )
        }

        FullscreenPanel.NONE -> {
            MainDashboardScreen(
                state = state,
                actions = actions,
                scrollState = dashboardScrollState
            )
        }
    }

    if (state.showArchiveFolderSetupDialog) {
        ArchiveFolderSetupDialog(
            onChooseFolder = actions.onChooseArchiveFolder,
            onDismiss = actions.onDismissArchiveFolderSetup
        )
    }

    if (state.showProfileDialog) {
        ProfileManagerDialog(
            profiles = state.profileOptions,
            activeProfileId = state.activeProfileId,
            newProfileNameText = state.newProfileNameText,
            newProfileGameId = state.newProfileGameId,
            newProfileDataPathText = state.newProfileDataPathText,
            newProfileRootPathText = state.newProfileRootPathText,
            newProfileInstallationResolved = state.newProfileInstallationResolved,
            onSelectProfile = actions.onSelectProfile,
            onDeleteProfile = actions.onDeleteProfile,
            onNewProfileNameChanged = actions.onNewProfileNameChanged,
            onNewProfileGameChanged = actions.onNewProfileGameChanged,
            onPickNewProfileTargetFolder = actions.onPickNewProfileTargetFolder,
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
            onToggleFullscreen = {},
            lastOperationStatus = state.lastOperationStatus
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

    if (state.showForceFullRedeployConfirmDialog) {
        AlertDialog(
            onDismissRequest = actions.onCancelForceFullRedeploy,
            title = {
                Text("Force Full Redeploy")
            },
            text = {
                Text(
                    "This will rewrite every currently managed file for this profile.\n\n" +
                            "It will not intentionally delete unmanaged files, but it can replace files already managed by Droid Mod Loader.\n\n" +
                            "Use this after an interrupted deploy or when the deployed folder looks out of sync."
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmForceFullRedeploy) {
                    Text("Run Full Redeploy")
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelForceFullRedeploy) {
                    Text("Cancel")
                }
            }
        )
    }
}