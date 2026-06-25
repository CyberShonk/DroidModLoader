package com.shonkware.droidmodloader

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.ui.MainActivityUiState
import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import com.shonkware.droidmodloader.ui.DirectFolderSelectionUiState
import com.shonkware.droidmodloader.ui.DroidModLoaderScreen
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.profile.ProfileRepositoryFactory
import com.shonkware.droidmodloader.ui.SecondScreenController
import java.io.File
import android.os.Looper
import com.shonkware.droidmodloader.ui.workflow.OperationReporter
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.GameCatalog
import com.shonkware.droidmodloader.ui.workflow.GameConfigurationWorkflow
import com.shonkware.droidmodloader.ui.workflow.ProfileConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.ProfileStartupWorkflow
import com.shonkware.droidmodloader.ui.workflow.PriorityNormalizationEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.PriorityNormalizationWorkflow
import com.shonkware.droidmodloader.ui.workflow.PluginSyncWorkflowController
import com.shonkware.droidmodloader.ui.workflow.PluginSynchronizationEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.PluginSynchronizationWorkflow
import com.shonkware.droidmodloader.ui.workflow.PluginActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.PluginManagementEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.PluginManagementWorkflow
import com.shonkware.droidmodloader.ui.workflow.InstallerWorkflowController
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerSession
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerWorkflow
import com.shonkware.droidmodloader.engine.io.ArchiveImportFileStore
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.io.LegacyProfileStorageMigrator
import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.ui.workflow.ProfileWorkflowController
import com.shonkware.droidmodloader.ui.workflow.ProfileManagementWorkflow
import com.shonkware.droidmodloader.ui.workflow.FirstSetupInput
import com.shonkware.droidmodloader.ui.workflow.AdditionalProfileInput
import com.shonkware.droidmodloader.ui.workflow.DashboardProfileInput
import com.shonkware.droidmodloader.ui.workflow.ModActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.ModManagementEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.ModManagementWorkflow
import com.shonkware.droidmodloader.ui.workflow.ArchiveImportWorkflowController
import com.shonkware.droidmodloader.ui.workflow.ArchiveImportExecutionWorkflow
import com.shonkware.droidmodloader.engine.download.ArchiveFolderPreferences
import com.shonkware.droidmodloader.engine.download.ArchiveFolderScanner
import com.shonkware.droidmodloader.ui.workflow.ArchiveBrowserHistory
import com.shonkware.droidmodloader.ui.workflow.ArchiveBrowserWorkflow
import com.shonkware.droidmodloader.ui.workflow.FolderPickMode
import com.shonkware.droidmodloader.ui.workflow.FolderPickerWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DirectFolderSelectionCoordinator
import com.shonkware.droidmodloader.ui.workflow.DeploymentActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DashboardRefreshEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.DashboardRefreshWorkflow
import com.shonkware.droidmodloader.ui.workflow.DashboardRefreshCoordinator
import com.shonkware.droidmodloader.ui.workflow.DeploymentExecutionEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.DeploymentExecutionWorkflow
import com.shonkware.droidmodloader.ui.workflow.DeployRecoveryWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DeployRecoveryEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.DeployRecoveryWorkflow
import com.shonkware.droidmodloader.ui.workflow.DeveloperToolsWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DeveloperDiagnosticsCoordinator
import com.shonkware.droidmodloader.ui.workflow.DeveloperDiagnosticsEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.OverwriteActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.FullscreenPanelActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.PreviewDialogActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.AppDiagnosticInfo
import com.shonkware.droidmodloader.ui.workflow.SupportReportCoordinator
import com.shonkware.droidmodloader.ui.workflow.SupportReportEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.ProfileContentInspectionCoordinator
import com.shonkware.droidmodloader.ui.workflow.ProfileContentInspectionEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.SecondScreenPluginCoordinator
import com.shonkware.droidmodloader.ui.workflow.ActivityThreadRunner
import com.shonkware.droidmodloader.ui.workflow.AppStartupCoordinator
import com.shonkware.droidmodloader.ui.workflow.ProfileSessionCoordinator
import com.shonkware.droidmodloader.ui.workflow.SelectedFolderConfigurationCoordinator
import com.shonkware.droidmodloader.ui.workflow.DashboardActionBindings
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessManager
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessPolicy
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import com.shonkware.droidmodloader.engine.storage.DirectStorageRootProvider
import com.shonkware.droidmodloader.engine.factory.ProfileScopedEngineFactory
import com.shonkware.droidmodloader.ui.workflow.InstallReplacementRecoveryEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.InstallReplacementStartupWorkflow

class MainActivity : ComponentActivity(), MainActivityUiState by MutableMainActivityUiState() {

    companion object {
        private const val TAG = "DroidModLoader"
    }
    private var secondScreenController: SecondScreenController? = null
    private val allFilesAccessManager by lazy {
        AllFilesAccessManager(applicationContext)
    }
    private val directPathValidator by lazy { DirectPathValidator() }
    private val directFolderBrowser by lazy {
        DirectFolderBrowser(
            roots = DirectStorageRootProvider(applicationContext).roots(),
            pathValidator = directPathValidator
        )
    }
    private val directFolderSelectionCoordinator by lazy {
        DirectFolderSelectionCoordinator(
            accessGrantedProvider = { allFilesAccessManager.isGranted() },
            browser = directFolderBrowser,
            pathValidator = directPathValidator,
            currentPathProvider = { mode ->
                when (mode) {
                    FolderPickMode.FirstSetupDataFolder -> setupTargetPathText
                    FolderPickMode.ActiveDataFolder -> targetPathText
                    FolderPickMode.ActiveGameRootFolder -> rootTargetPathText
                    FolderPickMode.NewProfileDataFolder -> newProfileDataPathText
                        .takeUnless { it == DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED }
                        .orEmpty()
                    FolderPickMode.ArchiveLibraryFolder -> activeProfileId
                        ?.let(archiveFolderPreferences::getSelectedFolderPath)
                        .orEmpty()
                }
            },
            requestAllFilesAccess = { requestAllFilesAccess() },
            handlePickedFolder = { mode, path ->
                folderPickerWorkflowController.handlePickedFolder(mode, path)
            }
        )
    }
    private val allFilesAccessSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        directFolderSelectionCoordinator.refreshAccessState()
    }
    private val activityThreadRunner by lazy {
        ActivityThreadRunner(
            isOnUiThread = { Looper.myLooper() == Looper.getMainLooper() },
            postToUiThread = { action -> runOnUiThread(action) }
        )
    }
    private val sessionLogWriter by lazy {
        SessionLogWriter { getExternalFilesDir(null) }
    }
    private val operationReporter by lazy {
        OperationReporter(
            runOnUiThread = { action -> runOnUiThread(action) },
            currentLogText = { logText },
            updateLogText = { logText = it },
            updateOperationInProgress = { operationInProgress = it },
            updateActiveOperationText = { activeOperationText = it },
            updateLastOperationStatus = { lastOperationStatus = it },
            showToast = { message -> showToast(message) },
            debugLog = { line -> Log.d(TAG, line) },
            errorLog = { line, throwable ->
                if (throwable == null) {
                    Log.e(TAG, line)
                } else {
                    Log.e(TAG, line, throwable)
                }
            },
            appendLogFile = { line -> sessionLogWriter.append(line) }
        )
    }
    private val profileContentInspectionCoordinator by lazy {
        ProfileContentInspectionCoordinator(
            engineProvider = {
                profileScopedEngineFactory.create()?.let(::ProfileContentInspectionEngineAdapter)
            },
            selectedGameIdProvider = { selectedGameId },
            runOnUiThread = { action -> runOnUiThread(action) },
            showModPreview = { preview ->
                selectedModFilePreview = preview
                showModFilePreviewDialog = true
                modFilePreviewFullscreen = false
            },
            showOverwriteResult = { result ->
                overwriteEntries = result.entries
                overwriteBaselineExists = result.baselineExists
                overwriteMessage = result.message
                showOverwriteDialog = true
            },
            updateBaselineState = { exists, message ->
                overwriteBaselineExists = exists
                overwriteMessage = message
            },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) }
        )
    }
    private val supportReportCoordinator by lazy {
        SupportReportCoordinator(
            engineProvider = {
                profileScopedEngineFactory.create()?.let(::SupportReportEngineAdapter)
            },
            diagnosticInfoProvider = {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                AppDiagnosticInfo(
                    versionName = packageInfo.versionName ?: "unknown",
                    versionCode = packageInfo.longVersionCode,
                    packageName = packageName,
                    androidVersion = android.os.Build.VERSION.RELEASE,
                    deviceModel = android.os.Build.MODEL
                )
            },
            lastOperationStatusProvider = { lastOperationStatus },
            developerModeEnabledProvider = { developerModeEnabled },
            currentLogTextProvider = { logText },
            sessionLogWriter = sessionLogWriter
        )
    }
    private val pluginSynchronizationWorkflow by lazy {
        PluginSynchronizationWorkflow { message -> operationReporter.appendLog(message) }
    }
    private val pluginSyncWorkflowController = PluginSyncWorkflowController(
        createEngine = { profileScopedEngineFactory.create() },
        syncPluginsFromCurrentState = { engine -> syncPluginsFromCurrentState(engine) },
        refreshDashboard = { dashboardRefreshCoordinator.refresh() }
    )
    private val pluginManagementWorkflow by lazy {
        PluginManagementWorkflow(
            createEngine = {
                profileScopedEngineFactory.create()?.let { engine ->
                    PluginManagementEngineAdapter(
                        engine = engine,
                        syncPlugins = { syncPluginsFromCurrentState(engine) }
                    )
                }
            },
            isOperationInProgress = { operationInProgress },
            beginOperation = { text -> operationReporter.beginOperation(text) },
            finishOperation = { text -> operationReporter.finishOperation(text) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            selectedGameIdProvider = { selectedGameId },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }

    private val pluginActionWorkflowController by lazy {
        PluginActionWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            writeLoadOrderFiles = { pluginManagementWorkflow.writeLoadOrderFiles() },
            togglePluginEnabled = { normalizedPath ->
                pluginManagementWorkflow.togglePluginEnabled(normalizedPath)
            },
            movePluginUp = { normalizedPath ->
                pluginManagementWorkflow.movePluginUp(normalizedPath)
            },
            movePluginDown = { normalizedPath ->
                pluginManagementWorkflow.movePluginDown(normalizedPath)
            },
            applyPluginOrder = { orderedPluginPaths ->
                pluginManagementWorkflow.applyPluginOrder(orderedPluginPaths)
            }
        )
    }
    private val pendingInstallerWorkflow by lazy {
        PendingInstallerWorkflow(
            pendingSessionProvider = {
                pendingArchiveInstall?.let { prepared ->
                    PendingInstallerSession(
                        prepared = prepared,
                        archiveRecordId = pendingInstallerArchiveRecordId,
                        selectedOptionIds = pendingInstallerSelectedOptionIds
                    )
                }
            },
            isOperationInProgress = { operationInProgress },
            createEngine = {
                profileScopedEngineFactory.create()?.let { engine ->
                    PendingInstallerEngineAdapter(
                        engine = engine,
                        syncPlugins = { syncPluginsFromCurrentState(engine) },
                        appendRoutingSummary = { mod ->
                            developerDiagnosticsCoordinator.appendInstalledModRoutingSummary(DeveloperDiagnosticsEngineAdapter(engine), mod)
                        }
                    )
                }
            },
            beginOperation = { message -> operationReporter.beginOperation(message) },
            finishOperation = { message -> operationReporter.finishOperation(message) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            updateSelectedOptionIds = { selectedOptionIds ->
                pendingInstallerSelectedOptionIds = selectedOptionIds
            },
            clearPendingInstallerState = {
                runOnUiThread {
                    pendingArchiveInstall = null
                    pendingInstallerArchiveRecordId = null
                    pendingInstallerSelectedOptionIds = emptySet()
                    showInstallerDialog = false
                    installerDialogFullscreen = false
                }
            },
            refreshDashboard = {
                dashboardRefreshCoordinator.refresh()
                archiveBrowserWorkflow.refreshIfOpen()
            }
        )
    }
    private val installerWorkflowController by lazy {
        InstallerWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            finalizeInstallerInstall = {
                pendingInstallerWorkflow.finalizePendingInstall()
            },
            cancelInstallerInstall = {
                pendingInstallerWorkflow.cancelPendingInstall()
            },
            toggleInstallerOption = { optionId ->
                pendingInstallerWorkflow.toggleOption(optionId)
            }
        )
    }
    private val profileWorkflowController by lazy {
        ProfileWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            completeFirstSetup = { profileManagementWorkflow.completeFirstSetup() },
            createAdditionalProfile = { profileManagementWorkflow.createAdditionalProfile() },
            switchActiveProfile = { profileId ->
                profileManagementWorkflow.switchActiveProfile(profileId)
            },
            deleteProfile = { profileId -> profileManagementWorkflow.deleteProfile(profileId) },
            saveDashboardSettings = { profileManagementWorkflow.saveDashboardSettings() }
        )
    }
    private val profileManagementWorkflow by lazy {
        ProfileManagementWorkflow(
            repositoryProvider = { profileRepositoryFactory.create() },
            gameDisplayNameProvider = GameCatalog::displayName,
            firstSetupInputProvider = {
                FirstSetupInput(
                    profileNameText = profileNameText,
                    gameId = setupGameId,
                    targetDataPath = setupTargetPathText,
                    realDeployEnabled = setupRealDeployEnabled
                )
            },
            additionalProfileInputProvider = {
                AdditionalProfileInput(
                    profileNameText = newProfileNameText,
                    gameId = newProfileGameId,
                    targetDataPath = newProfileDataPathText
                        .takeUnless { it == DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED }
                        .orEmpty(),
                    realDeployEnabled = newProfileRealDeployEnabled
                )
            },
            activeProfileIdProvider = { activeProfileId },
            dashboardProfileInputProvider = {
                DashboardProfileInput(
                    targetPathText = targetPathText,
                    rootTargetPathText = rootTargetPathText,
                    realDeployEnabled = realDeployEnabledState,
                    dataPathReselectionRequired = dataPathReselectionRequired,
                    rootPathReselectionRequired = rootPathReselectionRequired
                )
            },
            applyFirstSetupUiState = { profiles, profile ->
                runOnUiThread {
                    setupComplete = true
                    activeProfileId = profile.profileId
                    activeProfileName = profile.profileName
                    profileOptions = profiles

                    profileSessionCoordinator.applyProfileConfigUiState(
                        ProfileConfigUiMapper.fromProfile(profile)
                    )
                    archiveBrowserWorkflow.onProfileChanged()
                }
            },
            applyCreatedProfileUiState = { profiles, profile ->
                activityThreadRunner.runOnUiThreadBlocking {
                    profileOptions = profiles
                    activeProfileId = profile.profileId
                    activeProfileName = profile.profileName
                    profileSessionCoordinator.applyProfileConfigUiState(
                        ProfileConfigUiMapper.fromProfile(profile)
                    )
                    visibleMods = emptyList()
                    visiblePlugins = emptyList()
                    visibleModContentIndexes = emptyMap()

                    newProfileNameText = ""
                    newProfileDataPathText = DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED
                    newProfileRealDeployEnabled = false
                    showProfileDialog = false
                    archiveBrowserWorkflow.onProfileChanged()
                }
            },
            applySwitchedProfileUiState = { profile ->
                activityThreadRunner.runOnUiThreadBlocking {
                    activeProfileId = profile.profileId
                    activeProfileName = profile.profileName
                    profileSessionCoordinator.applyProfileConfigUiState(
                        ProfileConfigUiMapper.fromProfile(profile)
                    )
                    visibleMods = emptyList()
                    visiblePlugins = emptyList()
                    visibleModContentIndexes = emptyMap()
                    archiveBrowserWorkflow.onProfileChanged()
                }
            },
            applySavedProfileUiState = { profiles, updatedProfile ->
                runOnUiThread {
                    profileOptions = profiles
                    activeProfileName = updatedProfile.profileName
                }
            },
            applyDeletedProfileUiStateAsync = { profiles, newActiveProfile ->
                runOnUiThread {
                    profileOptions = profiles
                    activeProfileId = newActiveProfile?.profileId
                    activeProfileName = newActiveProfile?.profileName ?: "No profile"
                    setupComplete = profiles.isNotEmpty()

                    if (newActiveProfile != null) {
                        profileSessionCoordinator.applyProfileConfigUiState(
                            ProfileConfigUiMapper.fromProfile(newActiveProfile)
                        )
                    } else {
                        profileSessionCoordinator.applyProfileConfigUiState(
                            ProfileConfigUiMapper.emptyState()
                        )
                        visiblePlugins = emptyList()
                    }
                    archiveBrowserWorkflow.onProfileChanged()
                }
            },
            applyDeletedProfileUiStateBlocking = { profiles, newActiveProfile ->
                activityThreadRunner.runOnUiThreadBlocking {
                    profileOptions = profiles
                    activeProfileId = newActiveProfile?.profileId
                    activeProfileName = newActiveProfile?.profileName ?: "No profile"
                    setupComplete = profiles.isNotEmpty()

                    if (newActiveProfile != null) {
                        profileSessionCoordinator.applyProfileConfigUiState(
                            ProfileConfigUiMapper.fromProfile(newActiveProfile)
                        )
                    } else {
                        profileSessionCoordinator.applyProfileConfigUiState(
                            ProfileConfigUiMapper.emptyState()
                        )
                        showProfileDialog = false
                    }

                    archiveBrowserWorkflow.onProfileChanged()
                    visibleMods = emptyList()
                    visiblePlugins = emptyList()
                    visibleModContentIndexes = emptyMap()
                }
            },
            saveSelectedGameConfigFromUi = { profileSessionCoordinator.saveSelectedGameConfigFromUi() },
            loadSelectedGameConfigIntoUi = { profileSessionCoordinator.loadSelectedGameConfigIntoUi() },
            syncPluginsFromCurrentState = {
                val engine = profileScopedEngineFactory.create()
                if (engine != null) {
                    syncPluginsFromCurrentState(engine)
                }
            },
            refreshDashboard = dashboardRefreshCoordinator::refresh,
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message -> operationReporter.appendError(message) },
            updateLastOperationStatus = { status -> lastOperationStatus = status }
        )
    }
    private val modManagementWorkflow by lazy {
        ModManagementWorkflow(
            withEngine = { action ->
                val engine = profileScopedEngineFactory.create()
                if (engine != null) {
                    action(
                        ModManagementEngineAdapter(
                            engine = engine,
                            syncPlugins = { syncPluginsFromCurrentState(engine) }
                        )
                    )
                }
            },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val modActionWorkflowController by lazy {
        ModActionWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            onToggleModEnabled = { modId -> modManagementWorkflow.toggleModEnabled(modId) },
            onMoveModUp = { modId -> modManagementWorkflow.moveModUp(modId) },
            onMoveModDown = { modId -> modManagementWorkflow.moveModDown(modId) },
            onRequestDeleteMod = { mod -> showDeleteConfirmDialog(mod) },
            onViewModFiles = { modId -> profileContentInspectionCoordinator.openModFilePreview(modId) },
            onApplyModOrder = { orderedModIds -> modManagementWorkflow.applyModOrder(orderedModIds) }
        )
    }
    private val archiveImportWorkflowController: ArchiveImportWorkflowController by lazy {
        ArchiveImportWorkflowController(
            appendLog = { message -> operationReporter.appendLog(message) },
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            handleImportedArchive = { uri -> archiveImportExecutionWorkflow.importArchive(uri) },
            showArchiveLibrarySummary = { developerDiagnosticsCoordinator.buildArchiveLibrarySummary() }
        )
    }
    private val folderPickerWorkflowController by lazy {
        FolderPickerWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            saveFirstSetupDataPath = { path ->
                runOnUiThread {
                    setupTargetPathText = path
                    selectedDataPathText = path
                    setupRealDeployEnabled = true
                }
            },
            savePickedDataFolderToSelectedGameConfig = selectedFolderConfigurationCoordinator::saveDataFolder,
            savePickedRootFolderToSelectedGameConfig = selectedFolderConfigurationCoordinator::saveGameRoot,
            setNewProfileDataPathText = { path ->
                runOnUiThread {
                    newProfileDataPathText = path
                }
            },
            saveArchiveLibraryPath = { path ->
                archiveBrowserWorkflow.selectFolder(path)
            },
            appendLog = { message -> operationReporter.appendLog(message) }
        )
    }
    private val dashboardRefreshWorkflow = DashboardRefreshWorkflow()
    private val gameConfigurationWorkflow = GameConfigurationWorkflow()
    private val profileStartupWorkflow = ProfileStartupWorkflow()
    private val priorityNormalizationWorkflow by lazy {
        PriorityNormalizationWorkflow { message -> operationReporter.appendLog(message) }
    }
    private val deploymentExecutionWorkflow by lazy {
        DeploymentExecutionWorkflow(
            isOperationInProgress = { operationInProgress },
            selectedGameIdProvider = { selectedGameId },
            simulatedDataTargetPathProvider = {
                File(
                    getExternalFilesDir(null),
                    "deploy_target/profiles/${profileStoragePaths.getActiveProfileStorageKey()}/$selectedGameId/Data"
                ).absolutePath
            },
            saveActiveProfile = {
                profileManagementWorkflow.saveActiveProfileFromDashboard()
            },
            saveSelectedGameConfig = {
                profileSessionCoordinator.saveSelectedGameConfigFromUi()
            },
            createEngine = {
                profileScopedEngineFactory.create()?.let { engine ->
                    DeploymentExecutionEngineAdapter(
                        engine = engine,
                        syncPluginsAction = { syncPluginsFromCurrentState(engine) }
                    )
                }
            },
            beginOperation = { message -> operationReporter.beginOperation(message) },
            finishOperation = { message -> operationReporter.finishOperation(message) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val deploymentActionWorkflowController by lazy {
        DeploymentActionWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            runDeploy = { deploymentExecutionWorkflow.deploy() },
            runForceFullRedeploy = { deploymentExecutionWorkflow.forceFullRedeploy() },
            buildDeploymentPlan = { developerDiagnosticsCoordinator.buildDeploymentPlanSummary() },
            buildFullRedeployPlan = { developerDiagnosticsCoordinator.buildFullRedeployPlanSummary() }
        )
    }
    private val deployRecoveryWorkflow by lazy {
        DeployRecoveryWorkflow(
            operationInProgressProvider = { operationInProgress },
            engineProvider = {
                profileScopedEngineFactory.create()?.let(::DeployRecoveryEngineAdapter)
            },
            selectedGameIdProvider = { selectedGameId },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            beginOperation = { message -> operationReporter.beginOperation(message) },
            finishOperation = { message -> operationReporter.finishOperation(message) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            updateWarningState = { warning, showDetails ->
                runOnUiThread {
                    deployRecoveryWarningText = warning
                    showDeployRecoveryDialog = showDetails
                }
            },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val deployRecoveryWorkflowController by lazy {
        DeployRecoveryWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            showRecoveryDetails = {
                showDeployRecoveryDialog = true
            },
            hideRecoveryDetails = {
                showDeployRecoveryDialog = false
            },
            dismissRecoveryWarning = {
                deployRecoveryWarningText = ""
                showDeployRecoveryDialog = false
                operationReporter.appendLog("Dismissed previous deploy warning for this session.")
            },
            viewLastDeployJournal = {
                deployRecoveryWorkflow.readLastJournalSummary()
            },
            markDeployRecoveryReviewed = {
                deployRecoveryWorkflow.markReviewed()
            }
        )
    }
    private val developerDiagnosticsCoordinator by lazy {
        DeveloperDiagnosticsCoordinator(
            operationInProgressProvider = { operationInProgress },
            engineProvider = {
                profileScopedEngineFactory.create()?.let(::DeveloperDiagnosticsEngineAdapter)
            },
            selectedGameIdProvider = { selectedGameId },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            beginOperation = { message -> operationReporter.beginOperation(message) },
            finishOperation = { message -> operationReporter.finishOperation(message) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val developerToolsWorkflowController by lazy {
        DeveloperToolsWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            buildResolvedDataGraph = {
                developerDiagnosticsCoordinator.buildResolvedDataGraphSummary()
            },
            showArchiveLibrarySummary = {
                archiveImportWorkflowController.requestArchiveLibrarySummary()
            }
        )
    }
    private val overwriteActionWorkflowController by lazy {
        OverwriteActionWorkflowController(
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            openOverwriteFolderPanel = {
                profileContentInspectionCoordinator.openOverwriteFolderPanel()
            },
            closeOverwriteFolderPanel = {
                showOverwriteDialog = false
            }
        )
    }
    private val fullscreenPanelActionWorkflowController by lazy {
        FullscreenPanelActionWorkflowController(
            openModsPanel = {
                fullscreenPanel = FullscreenPanel.MODS
            },
            openPluginsPanel = {
                fullscreenPanel = FullscreenPanel.PLUGINS
            },
            closePanel = {
                fullscreenPanel = FullscreenPanel.NONE
            },
            applyModOrder = { orderedModIds ->
                modActionWorkflowController.applyModOrder(orderedModIds)
            },
            applyPluginOrder = { orderedPluginPaths ->
                pluginActionWorkflowController.applyPluginOrder(orderedPluginPaths)
            }
        )
    }
    private val profileStoragePaths by lazy {
        ProfileStoragePaths(
            filesDir = filesDir,
            activeProfileIdProvider = { activeProfileId },
            selectedGameIdProvider = { selectedGameId }
        )
    }

    private val profileScopedEngineFactory by lazy {
        ProfileScopedEngineFactory(
            appContext = applicationContext,
            externalFilesDirProvider = { getExternalFilesDir(null) },
            profileStoragePaths = profileStoragePaths,
            selectedGameIdProvider = { selectedGameId },
            appendError = { message -> operationReporter.appendError(message) }
        )
    }
    private val profileRepositoryFactory by lazy {
        ProfileRepositoryFactory(
            externalFilesDirProvider = { getExternalFilesDir(null) },
            appendError = { message -> operationReporter.appendError(message) }
        )
    }

    private val profileSessionCoordinator by lazy {
        ProfileSessionCoordinator(
            state = this,
            profileRepositoryFactory = profileRepositoryFactory,
            profileScopedEngineFactory = profileScopedEngineFactory,
            profileStartupWorkflow = profileStartupWorkflow,
            gameConfigurationWorkflow = gameConfigurationWorkflow,
            runOnUiThreadBlocking = activityThreadRunner::runOnUiThreadBlocking,
            appendLog = operationReporter::appendLog
        )
    }

    private val selectedFolderConfigurationCoordinator by lazy {
        SelectedFolderConfigurationCoordinator(
            state = this,
            runOnUiThreadBlocking = activityThreadRunner::runOnUiThreadBlocking,
            saveSelectedGameConfig = profileSessionCoordinator::saveSelectedGameConfigFromUi,
            saveActiveProfile = profileManagementWorkflow::saveActiveProfileFromDashboard,
            ensureDataBaselineIfMissing = profileContentInspectionCoordinator::ensureDataBaselineIfMissing,
            refreshDashboard = dashboardRefreshCoordinator::refresh,
            appendLog = operationReporter::appendLog
        )
    }

    private val legacyProfileStorageMigrator by lazy {
        LegacyProfileStorageMigrator(
            filesDir = filesDir,
            externalFilesDirProvider = { getExternalFilesDir(null) },
            activeProfileIdProvider = { activeProfileId },
            profileStoragePaths = profileStoragePaths,
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status }
        )
    }

    private val archiveImportFileStore by lazy {
        ArchiveImportFileStore(
            externalFilesDirProvider = { getExternalFilesDir(null) },
            appendError = { message -> operationReporter.appendError(message) }
        )
    }

    private val archiveImportExecutionWorkflow: ArchiveImportExecutionWorkflow by lazy {
        ArchiveImportExecutionWorkflow(
            operationInProgressProvider = { operationInProgress },
            beginOperation = { message -> operationReporter.beginOperation(message) },
            createEngine = { profileScopedEngineFactory.create() },
            archiveImportFileStore = archiveImportFileStore,
            showInstallerChoices = { prepared, archiveRecordId ->
                runOnUiThread {
                    pendingArchiveInstall = prepared
                    pendingInstallerArchiveRecordId = archiveRecordId
                    pendingInstallerSelectedOptionIds = prepared.plan.defaultSelectedOptionIds
                    showInstallerDialog = true
                    installerDialogFullscreen = false
                }
            },
            appendLog = { message -> operationReporter.appendLog(message) },
            finishOperation = { message -> operationReporter.finishOperation(message) },
            failOperation = { message, throwable -> operationReporter.failOperation(message, throwable) },
            syncPluginsFromCurrentState = { engine -> syncPluginsFromCurrentState(engine) },
            appendInstalledModRoutingSummary = { engine, mod ->
                developerDiagnosticsCoordinator.appendInstalledModRoutingSummary(DeveloperDiagnosticsEngineAdapter(engine), mod)
            },
            refreshDashboard = {
                dashboardRefreshCoordinator.refresh()
                archiveBrowserWorkflow.refreshIfOpen()
            }
        )
    }

    private val archiveFolderPreferences by lazy {
        ArchiveFolderPreferences(
            getSharedPreferences(
                ArchiveFolderPreferences.PREFERENCES_NAME,
                MODE_PRIVATE
            )
        )
    }

    private val archiveFolderScanner by lazy {
        ArchiveFolderScanner()
    }

    private val archiveBrowserWorkflow: ArchiveBrowserWorkflow by lazy {
        ArchiveBrowserWorkflow(
            preferences = archiveFolderPreferences,
            activeProfileIdProvider = { activeProfileId },
            runInBackground = { task -> activityThreadRunner.runInBackground(task) },
            isOperationInProgress = { operationInProgress },
            isBrowserOpen = { fullscreenPanel == FullscreenPanel.ARCHIVES },
            scanFolder = { folderPath -> archiveFolderScanner.scan(folderPath) },
            loadHistory = {
                val engine = profileScopedEngineFactory.create()
                    ?: throw IllegalStateException("Archive browser is unavailable.")
                ArchiveBrowserHistory(
                    records = engine.getDownloadedArchives(),
                    currentMods = engine.getCurrentMods()
                )
            },
            canonicalIdentityForSourcePath = { sourcePath ->
                archiveFolderScanner.canonicalIdentityForPath(sourcePath)
            },
            showFolderSetup = {
                runOnUiThread {
                    showArchiveFolderSetupDialog = true
                }
            },
            showBrowser = {
                runOnUiThread {
                    showArchiveFolderSetupDialog = false
                    fullscreenPanel = FullscreenPanel.ARCHIVES
                }
            },
            updateState = { state ->
                runOnUiThread {
                    archiveBrowserState = state
                }
            },
            installArchivePath = { sourcePath ->
                archiveImportWorkflowController.handleArchivePath(sourcePath)
            },
            appendLog = { message -> operationReporter.appendLog(message) }
        )
    }

    private val secondScreenPluginCoordinator by lazy {
        SecondScreenPluginCoordinator(
            controllerProvider = { secondScreenController },
            pluginsProvider = { visiblePlugins },
            activeProfileNameProvider = { activeProfileName },
            appendLog = { message -> operationReporter.appendLog(message) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            showToast = { message -> showToast(message) }
        )
    }

    private val dashboardRefreshCoordinator by lazy {
        DashboardRefreshCoordinator(
            state = this,
            buildResult = {
                profileScopedEngineFactory.create()?.let { engine ->
                    dashboardRefreshWorkflow.build(
                        engine = DashboardRefreshEngineAdapter(engine),
                        selectedGameId = selectedGameId
                    )
                }
            },
            runOnUiThread = { action -> runOnUiThread(action) },
            refreshSecondScreen = secondScreenPluginCoordinator::refresh,
            appendLog = operationReporter::appendLog
        )
    }

    private val previewDialogActionWorkflowController by lazy {
        PreviewDialogActionWorkflowController(
            toggleInstallerFullscreen = {
                installerDialogFullscreen = !installerDialogFullscreen
            },
            closeModFilePreview = {
                selectedModFilePreview = null
                showModFilePreviewDialog = false
                modFilePreviewFullscreen = false
            },
            toggleModFilePreviewFullscreen = {
                modFilePreviewFullscreen = !modFilePreviewFullscreen
            }
        )
    }

    private val installReplacementStartupWorkflow by lazy {
        InstallReplacementStartupWorkflow(
            appendLog = operationReporter::appendLog,
            appendError = { message ->
                operationReporter.appendError(message)
            }
        )
    }

    private val appStartupCoordinator by lazy {
        AppStartupCoordinator(
            runInBackground = activityThreadRunner::runInBackground,
            loadSetupState = profileSessionCoordinator::loadSetupState,
            migrateLegacyProfileStorage = legacyProfileStorageMigrator::migrateIfNeeded,
            refreshGameOptions = profileSessionCoordinator::refreshGameOptions,
            loadSelectedGameConfig = profileSessionCoordinator::loadSelectedGameConfigIntoUi,
            migratePrioritySpacing = {
                profileScopedEngineFactory.create()?.let { engine ->
                    priorityNormalizationWorkflow.migrateIfNeeded(
                        PriorityNormalizationEngineAdapter(engine)
                    )
                }
            },
            ensureDataBaseline = {
                profileContentInspectionCoordinator.ensureDataBaselineIfMissing("startup")
            },
            createRuntime = profileScopedEngineFactory::create,
            checkRecovery = { engine ->
                installReplacementStartupWorkflow.checkStartup(
                    InstallReplacementRecoveryEngineAdapter(
                        engine
                    )
                )

                deployRecoveryWorkflow.checkStartup(
                    DeployRecoveryEngineAdapter(engine)
                )
            },
            synchronizePluginsAndRefresh = pluginSyncWorkflowController::syncWithExistingEngineThenRefresh,
            appendLog = operationReporter::appendLog
        )
    }

    private val dashboardActionBindings by lazy {
        DashboardActionBindings(
            state = this,
            archiveBrowserWorkflow = archiveBrowserWorkflow,
            directFolderSelectionCoordinator = directFolderSelectionCoordinator,
            deploymentActionWorkflowController = deploymentActionWorkflowController,
            pluginActionWorkflowController = pluginActionWorkflowController,
            modActionWorkflowController = modActionWorkflowController,
            profileWorkflowController = profileWorkflowController,
            installerWorkflowController = installerWorkflowController,
            previewDialogActionWorkflowController = previewDialogActionWorkflowController,
            secondScreenPluginCoordinator = secondScreenPluginCoordinator,
            fullscreenPanelActionWorkflowController = fullscreenPanelActionWorkflowController,
            overwriteActionWorkflowController = overwriteActionWorkflowController,
            developerToolsWorkflowController = developerToolsWorkflowController,
            deployRecoveryWorkflowController = deployRecoveryWorkflowController,
            activityThreadRunner = activityThreadRunner,
            profileContentInspectionCoordinator = profileContentInspectionCoordinator,
            pluginSyncWorkflowController = pluginSyncWorkflowController,
            loadSelectedGameConfigIntoUi = profileSessionCoordinator::loadSelectedGameConfigIntoUi,
            shareLogs = ::shareLogs,
            requestAllFilesAccess = ::requestAllFilesAccess,
            appendLog = operationReporter::appendLog
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directFolderSelectionCoordinator.refreshAccessState()

        setContent {
            DmlTheme {
                DroidModLoaderScreen(
                    state = toDashboardUiState(
                        secondScreenEnabled = secondScreenPluginCoordinator.enabled,
                        allFilesAccessRequired = android.os.Build.VERSION.SDK_INT >= AllFilesAccessPolicy.ANDROID_11_API_LEVEL,
                        directFolderState = DirectFolderSelectionUiState(
                            allFilesAccessGranted = directFolderSelectionCoordinator.allFilesAccessGranted,
                            showBrowser = directFolderSelectionCoordinator.showBrowser,
                            browserTitle = directFolderSelectionCoordinator.browserTitle,
                            browserRequiresWritable = directFolderSelectionCoordinator.browserRequiresWritable,
                            browserState = directFolderSelectionCoordinator.browserState
                        )
                    ),
                    actions = dashboardActionBindings.build()
                )
            }
        }

        secondScreenController = SecondScreenController(this)


        appStartupCoordinator.initialize()
    }

    override fun onResume() {
        super.onResume()
        directFolderSelectionCoordinator.refreshAccessState()

        secondScreenPluginCoordinator.onResume()

        archiveBrowserWorkflow.refreshIfOpen()
    }

    override fun onPause() {
        secondScreenPluginCoordinator.onPause()
        super.onPause()
    }


    private fun showDeleteConfirmDialog(mod: Mod) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Delete Mod")
                .setMessage(
                    "Are you sure you want to delete '${mod.name}'?\n\n" +
                            "This will permanently remove this installed mod folder from Droid Mod Loader.\n\n" +
                            "Run Deploy afterward to remove its deployed files from the selected game Data folder."
                )
                .setPositiveButton("Delete") { _, _ ->
                    activityThreadRunner.runInBackground { modManagementWorkflow.deleteInstalledMod(mod.id) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    private fun shareLogs() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, supportReportCoordinator.buildShareText())
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Logs"))
    }


    private fun syncPluginsFromCurrentState(engine: ModEngine) {
        pluginSynchronizationWorkflow.sync(
            engine = PluginSynchronizationEngineAdapter(engine),
            selectedGameId = selectedGameId
        )
    }


    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }


    private fun requestAllFilesAccess() {
        val primary = allFilesAccessManager.appSpecificSettingsIntent() ?: return

        try {
            allFilesAccessSettingsLauncher.launch(primary)
        } catch (_: ActivityNotFoundException) {
            val fallback = allFilesAccessManager.fallbackSettingsIntent()
            if (fallback == null) {
                operationReporter.appendLog("All-files access settings are unavailable on this device.")
                return
            }

            try {
                allFilesAccessSettingsLauncher.launch(fallback)
            } catch (e: ActivityNotFoundException) {
                operationReporter.appendError("All-files access settings are unavailable: ${e.message}", e)
            }
        }
    }

}