package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.DashboardActions
import com.shonkware.droidmodloader.ui.MainActivityUiState

/**
 * Binds dashboard callbacks to focused workflow controllers and mutable UI state.
 * Android platform launches remain callbacks supplied by MainActivity.
 */
internal class DashboardActionBindings(
    private val state: MainActivityUiState,
    private val archiveBrowserWorkflow: ArchiveBrowserWorkflow,
    private val directFolderSelectionCoordinator: DirectFolderSelectionCoordinator,
    private val deploymentActionWorkflowController: DeploymentActionWorkflowController,
    private val pluginActionWorkflowController: PluginActionWorkflowController,
    private val modActionWorkflowController: ModActionWorkflowController,
    private val profileWorkflowController: ProfileWorkflowController,
    private val installerWorkflowController: InstallerWorkflowController,
    private val previewDialogActionWorkflowController: PreviewDialogActionWorkflowController,
    private val secondScreenPluginCoordinator: SecondScreenPluginCoordinator,
    private val fullscreenPanelActionWorkflowController: FullscreenPanelActionWorkflowController,
    private val overwriteActionWorkflowController: OverwriteActionWorkflowController,
    private val developerToolsWorkflowController: DeveloperToolsWorkflowController,
    private val deployRecoveryWorkflowController: DeployRecoveryWorkflowController,
    private val activityThreadRunner: ActivityThreadRunner,
    private val profileContentInspectionCoordinator: ProfileContentInspectionCoordinator,
    private val pluginSyncWorkflowController: PluginSyncWorkflowController,
    private val loadSelectedGameConfigIntoUi: () -> Unit,
    private val shareLogs: () -> Unit,
    private val requestAllFilesAccess: () -> Unit,
    private val appendLog: (String) -> Unit,
    private val archiveImportExecutionWorkflow: ArchiveImportExecutionWorkflow,

) {
    fun build(): DashboardActions {
        return DashboardActions(
            onVersionTap = {
                state.developerTapCount++
                if (!state.developerModeEnabled && state.developerTapCount >= 5) {
                    state.developerModeEnabled = true
                    appendLog("Developer tools unlocked.")
                }
            },
            onInstallMod = archiveBrowserWorkflow::openBrowser,
            onChooseArchiveFolder = {
                state.showArchiveFolderSetupDialog = false
                directFolderSelectionCoordinator.open(FolderPickMode.ArchiveLibraryFolder)
            },
            onDismissArchiveFolderSetup = {
                state.showArchiveFolderSetupDialog = false
            },
            onRefreshArchiveFolder = archiveBrowserWorkflow::refresh,
            onChangeArchiveFolder = {
                directFolderSelectionCoordinator.open(FolderPickMode.ArchiveLibraryFolder)
            },
            onInstallArchiveFromFolder = archiveBrowserWorkflow::installArchive,
            onDeployMods = deploymentActionWorkflowController::deploy,
            onWriteLoadOrderFiles = pluginActionWorkflowController::writeLoadOrderFiles,
            onToggleMod = modActionWorkflowController::toggleMod,
            onMoveModUp = modActionWorkflowController::moveModUp,
            onMoveModDown = modActionWorkflowController::moveModDown,
            onDeleteMod = modActionWorkflowController::requestDeleteMod,
            onTogglePlugin = pluginActionWorkflowController::togglePlugin,
            onMovePluginUp = pluginActionWorkflowController::movePluginUp,
            onMovePluginDown = pluginActionWorkflowController::movePluginDown,
            onSelectGame = { gameId ->
                state.selectedGameId = gameId
                loadSelectedGameConfigIntoUi()
                activityThreadRunner.runInBackground {
                    profileContentInspectionCoordinator.ensureDataBaselineIfMissing("selected game changed")
                    pluginSyncWorkflowController.syncWithNewEngineThenRefresh()
                }
            },
            onRealDeployChanged = { enabled ->
                state.realDeployEnabledState = enabled
            },
            onPickTargetFolder = {
                directFolderSelectionCoordinator.open(
                    if (state.setupComplete) {
                        FolderPickMode.ActiveDataFolder
                    } else {
                        FolderPickMode.FirstSetupDataFolder
                    }
                )
            },
            onPickRootTargetFolder = {
                directFolderSelectionCoordinator.open(FolderPickMode.ActiveGameRootFolder)
            },
            onSaveSettings = {
                activityThreadRunner.runInBackground {
                    profileWorkflowController.saveSettings()
                }
            },
            onShareLogs = shareLogs,
            onProfileNameChanged = { state.profileNameText = it },
            onSetupGameChanged = { gameId ->
                state.setupGameId = gameId
                state.setupGameDisplayName = GameCatalog.displayName(gameId)
            },
            onSetupTargetPathChanged = { state.setupTargetPathText = it },
            onSetupRealDeployChanged = { state.setupRealDeployEnabled = it },
            onCompleteSetup = profileWorkflowController::completeSetup,
            onSelectProfile = profileWorkflowController::switchProfile,
            onNewProfileNameChanged = { state.newProfileNameText = it },
            onNewProfileGameChanged = { gameId ->
                state.newProfileGameId = gameId
                state.newProfileGameDisplayName = GameCatalog.displayName(gameId)
            },
            onNewProfileRealDeployChanged = { state.newProfileRealDeployEnabled = it },
            onCreateAdditionalProfile = profileWorkflowController::createProfile,
            onOpenProfileDialog = { state.showProfileDialog = true },
            onCloseProfileDialog = { state.showProfileDialog = false },
            onPickNewProfileTargetFolder = {
                directFolderSelectionCoordinator.open(FolderPickMode.NewProfileDataFolder)
            },
            onDeleteProfile = profileWorkflowController::deleteProfile,
            onToggleInstallerOption = installerWorkflowController::toggleOption,
            onConfirmInstaller = installerWorkflowController::finalizeInstall,
            onCancelInstaller = installerWorkflowController::cancelInstall,
            onToggleInstallerFullscreen = previewDialogActionWorkflowController::toggleInstallerFullscreen,
            onViewModFiles = modActionWorkflowController::viewModFiles,
            onCloseModFilePreview = previewDialogActionWorkflowController::closeModFilePreview,
            onToggleModFilePreviewFullscreen = previewDialogActionWorkflowController::toggleModFilePreviewFullscreen,
            onToggleSecondScreen = secondScreenPluginCoordinator::toggle,
            onOpenModsFullscreen = fullscreenPanelActionWorkflowController::openModsFullscreen,
            onOpenPluginsFullscreen = fullscreenPanelActionWorkflowController::openPluginsFullscreen,
            onCloseFullscreenPanel = fullscreenPanelActionWorkflowController::closeFullscreenPanel,
            onApplyModOrder = fullscreenPanelActionWorkflowController::applyModOrder,
            onApplyPluginOrder = fullscreenPanelActionWorkflowController::applyPluginOrder,
            onOpenOverwriteFolder = overwriteActionWorkflowController::openOverwriteFolder,
            onCloseOverwriteFolder = overwriteActionWorkflowController::closeOverwriteFolder,
            onBuildResolvedDataGraph = developerToolsWorkflowController::buildResolvedDataGraph,
            onBuildDeploymentPlan = deploymentActionWorkflowController::buildDeployPlan,
            onShowArchiveLibrarySummary = developerToolsWorkflowController::showArchiveLibrarySummary,
            onBuildFullRedeployPlan = deploymentActionWorkflowController::buildFullRedeployPlan,
            onViewLastDeployJournal = deployRecoveryWorkflowController::viewLastJournal,
            onOpenDeployRecoveryDetails = deployRecoveryWorkflowController::openRecoveryDetails,
            onCloseDeployRecoveryDetails = deployRecoveryWorkflowController::closeRecoveryDetails,
            onDismissDeployRecoveryWarning = deployRecoveryWorkflowController::dismissWarning,
            onMarkDeployRecoveryReviewed = deployRecoveryWorkflowController::markReviewed,
            onRequestForceFullRedeploy = {
                state.showForceFullRedeployConfirmDialog = true
            },
            onConfirmForceFullRedeploy = {
                state.showForceFullRedeployConfirmDialog = false
                deploymentActionWorkflowController.forceFullRedeploy()
            },
            onCancelForceFullRedeploy = {
                state.showForceFullRedeployConfirmDialog = false
            },
            onRequestAllFilesAccess = requestAllFilesAccess,
            onDirectFolderBrowserOpenPath = directFolderSelectionCoordinator::openPath,
            onDirectFolderBrowserNavigateUp = directFolderSelectionCoordinator::navigateUp,
            onDirectFolderBrowserSelectCurrent = directFolderSelectionCoordinator::selectCurrent,
            onDirectFolderBrowserCancel = directFolderSelectionCoordinator::cancel,
            onCancelArchiveImport =
                archiveImportExecutionWorkflow::cancelImport,
        )
    }
}
