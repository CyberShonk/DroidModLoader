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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.ui.DashboardActions
import com.shonkware.droidmodloader.ui.DashboardUiState
import com.shonkware.droidmodloader.ui.DroidModLoaderScreen
import com.shonkware.droidmodloader.engine.profile.ProfileRepositoryFactory
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.ui.SecondScreenController
import java.io.File
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import android.os.Looper
import com.shonkware.droidmodloader.ui.workflow.OperationReporter
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiState
import com.shonkware.droidmodloader.ui.workflow.GameCatalog
import com.shonkware.droidmodloader.ui.workflow.GameConfigurationEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.GameConfigurationInput
import com.shonkware.droidmodloader.ui.workflow.GameConfigurationWorkflow
import com.shonkware.droidmodloader.ui.workflow.ProfileConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.ProfileConfigUiState
import com.shonkware.droidmodloader.ui.workflow.ProfileStartupRepositoryAdapter
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
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.workflow.FolderPickMode
import com.shonkware.droidmodloader.ui.workflow.FolderPickerWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DirectFolderSelectionCoordinator
import com.shonkware.droidmodloader.ui.workflow.DeploymentActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DashboardRefreshEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.DashboardRefreshWorkflow
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
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessManager
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessPolicy
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import com.shonkware.droidmodloader.engine.storage.DirectStorageRootProvider
import com.shonkware.droidmodloader.engine.factory.ProfileScopedEngineFactory

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }
    private var secondScreenController: SecondScreenController? = null
    private var setupComplete by mutableStateOf(false)
    private var activeProfileId by mutableStateOf<String?>(null)
    private var profileNameText by mutableStateOf("Default")
    private var profileOptions by mutableStateOf<List<GameProfile>>(emptyList())
    private var activeProfileName by mutableStateOf("No profile")
    private var showProfileDialog by mutableStateOf(false)
    private var setupGameId by mutableStateOf("skyrim_le")
    private var setupGameDisplayName by mutableStateOf("Skyrim Legendary Edition")
    private var setupTargetPathText by mutableStateOf("")
    private var setupRealDeployEnabled by mutableStateOf(false)
    private var operationInProgress by mutableStateOf(false)
    private var activeOperationText by mutableStateOf("")
    private var newProfileNameText by mutableStateOf("")
    private var newProfileGameId by mutableStateOf("skyrim_le")
    private var newProfileGameDisplayName by mutableStateOf("Skyrim Legendary Edition")
    private var newProfileDataPathText by mutableStateOf("No folder selected")
    private var newProfileRealDeployEnabled by mutableStateOf(false)
    private var developerTapCount = 0
    private var developerModeEnabled by mutableStateOf(false)
    private var lastOperationStatus by mutableStateOf("Ready.")
    private var logText by mutableStateOf("")
    private var summaryText by mutableStateOf("Loading...")
    private var visibleMods by mutableStateOf<List<Mod>>(emptyList())
    private var visiblePlugins by mutableStateOf<List<PluginEntry>>(emptyList())
    private var gameOptions by mutableStateOf(listOf("skyrim_le", "fallout_nv"))
    private var selectedGameId by mutableStateOf("skyrim_le")
    private var targetPathText by mutableStateOf("")
    private var selectedDataPathText by mutableStateOf("No folder selected")
    private var selectedRootPathText by mutableStateOf("No root folder selected")
    private var rootTargetPathText by mutableStateOf("")
    private var dataPathReselectionRequired by mutableStateOf(false)
    private var rootPathReselectionRequired by mutableStateOf(false)
    private var realDeployEnabledState by mutableStateOf(false)
    private var pendingArchiveInstall by mutableStateOf<PreparedArchiveInstall?>(null)
    private var pendingInstallerArchiveRecordId by mutableStateOf<String?>(null)
    private var pendingInstallerSelectedOptionIds by mutableStateOf<Set<String>>(emptySet())
    private var showInstallerDialog by mutableStateOf(false)
    private var installerDialogFullscreen by mutableStateOf(false)
    private var visibleModContentIndexes by mutableStateOf<Map<String, ModContentIndex>>(emptyMap())
    private var selectedModFilePreview by mutableStateOf<ModFilePreview?>(null)
    private var showModFilePreviewDialog by mutableStateOf(false)
    private var modFilePreviewFullscreen by mutableStateOf(false)
    private var fullscreenPanel by mutableStateOf(FullscreenPanel.NONE)
    private var showArchiveFolderSetupDialog by mutableStateOf(false)
    private var archiveBrowserState by mutableStateOf(ArchiveBrowserUiState())
    private var overwriteEntries by mutableStateOf<List<OverwriteEntry>>(emptyList())
    private var showOverwriteDialog by mutableStateOf(false)
    private var overwriteBaselineExists by mutableStateOf(false)
    private var overwriteMessage by mutableStateOf("")
    private var deployRecoveryWarningText by mutableStateOf("")
    private var showDeployRecoveryDialog by mutableStateOf(false)
    private var showForceFullRedeployConfirmDialog by mutableStateOf(false)
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
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) }
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
        PluginSynchronizationWorkflow { message -> appendLog(message) }
    }
    private val pluginSyncWorkflowController = PluginSyncWorkflowController(
        createEngine = { profileScopedEngineFactory.create() },
        syncPluginsFromCurrentState = { engine -> syncPluginsFromCurrentState(engine) },
        refreshDashboard = { refreshDashboard() }
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
            beginOperation = { text -> beginOperation(text) },
            finishOperation = { text -> finishOperation(text) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            selectedGameIdProvider = { selectedGameId },
            refreshDashboard = { refreshDashboard() }
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
            beginOperation = { message -> beginOperation(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
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
                refreshDashboard()
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

                    applyProfileConfigUiState(
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
                    applyProfileConfigUiState(
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
                    applyProfileConfigUiState(
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
                        applyProfileConfigUiState(
                            ProfileConfigUiMapper.fromProfile(newActiveProfile)
                        )
                    } else {
                        applyProfileConfigUiState(
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
                        applyProfileConfigUiState(
                            ProfileConfigUiMapper.fromProfile(newActiveProfile)
                        )
                    } else {
                        applyProfileConfigUiState(
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
            saveSelectedGameConfigFromUi = { saveSelectedGameConfigFromUi() },
            loadSelectedGameConfigIntoUi = { loadSelectedGameConfigIntoUi() },
            syncPluginsFromCurrentState = {
                val engine = profileScopedEngineFactory.create()
                if (engine != null) {
                    syncPluginsFromCurrentState(engine)
                }
            },
            refreshDashboard = { refreshDashboard() },
            appendLog = { message -> appendLog(message) },
            appendError = { message -> appendError(message) },
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
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            refreshDashboard = { refreshDashboard() }
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
            appendLog = { message -> appendLog(message) },
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
            savePickedDataFolderToSelectedGameConfig = { path ->
                savePickedDataFolderToSelectedGameConfig(path)
            },
            savePickedRootFolderToSelectedGameConfig = { path ->
                savePickedRootFolderToSelectedGameConfig(path)
            },
            setNewProfileDataPathText = { path ->
                runOnUiThread {
                    newProfileDataPathText = path
                }
            },
            saveArchiveLibraryPath = { path ->
                archiveBrowserWorkflow.selectFolder(path)
            },
            appendLog = { message -> appendLog(message) }
        )
    }
    private val dashboardRefreshWorkflow = DashboardRefreshWorkflow()
    private val gameConfigurationWorkflow = GameConfigurationWorkflow()
    private val profileStartupWorkflow = ProfileStartupWorkflow()
    private val priorityNormalizationWorkflow by lazy {
        PriorityNormalizationWorkflow { message -> appendLog(message) }
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
                saveSelectedGameConfigFromUi()
            },
            createEngine = {
                profileScopedEngineFactory.create()?.let { engine ->
                    DeploymentExecutionEngineAdapter(
                        engine = engine,
                        syncPluginsAction = { syncPluginsFromCurrentState(engine) }
                    )
                }
            },
            beginOperation = { message -> beginOperation(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            refreshDashboard = { refreshDashboard() }
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
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            beginOperation = { message -> beginOperation(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            updateWarningState = { warning, showDetails ->
                runOnUiThread {
                    deployRecoveryWarningText = warning
                    showDeployRecoveryDialog = showDetails
                }
            },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            refreshDashboard = { refreshDashboard() }
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
                appendLog("Dismissed previous deploy warning for this session.")
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
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            beginOperation = { message -> beginOperation(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            refreshDashboard = { refreshDashboard() }
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
            appendError = { message -> appendError(message) }
        )
    }
    private val profileRepositoryFactory by lazy {
        ProfileRepositoryFactory(
            externalFilesDirProvider = { getExternalFilesDir(null) },
            appendError = { message -> appendError(message) }
        )
    }

    private val legacyProfileStorageMigrator by lazy {
        LegacyProfileStorageMigrator(
            filesDir = filesDir,
            externalFilesDirProvider = { getExternalFilesDir(null) },
            activeProfileIdProvider = { activeProfileId },
            profileStoragePaths = profileStoragePaths,
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status }
        )
    }

    private val archiveImportFileStore by lazy {
        ArchiveImportFileStore(
            externalFilesDirProvider = { getExternalFilesDir(null) },
            appendError = { message -> appendError(message) }
        )
    }

    private val archiveImportExecutionWorkflow: ArchiveImportExecutionWorkflow by lazy {
        ArchiveImportExecutionWorkflow(
            operationInProgressProvider = { operationInProgress },
            beginOperation = { message -> beginOperation(message) },
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
            appendLog = { message -> appendLog(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            syncPluginsFromCurrentState = { engine -> syncPluginsFromCurrentState(engine) },
            appendInstalledModRoutingSummary = { engine, mod ->
                developerDiagnosticsCoordinator.appendInstalledModRoutingSummary(DeveloperDiagnosticsEngineAdapter(engine), mod)
            },
            refreshDashboard = {
                refreshDashboard()
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
            appendLog = { message -> appendLog(message) }
        )
    }

    private val secondScreenPluginCoordinator by lazy {
        SecondScreenPluginCoordinator(
            controllerProvider = { secondScreenController },
            pluginsProvider = { visiblePlugins },
            activeProfileNameProvider = { activeProfileName },
            appendLog = { message -> appendLog(message) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            showToast = { message -> showToast(message) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directFolderSelectionCoordinator.refreshAccessState()

        setContent {
            DmlTheme {
                DroidModLoaderScreen(
                    state = buildUiState(),
                    actions = buildUiActions()
                )
            }
        }

        secondScreenController = SecondScreenController(this)


        initializeComposeUi()
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



    private fun buildUiState(): DashboardUiState {
        return DashboardUiState(
            appName = "Droid Mod Loader",
            versionLabel = "Version 0.6.0 Beta",
            developerModeEnabled = developerModeEnabled,
            lastOperationStatus = lastOperationStatus,
            summaryText = summaryText,
            mods = visibleMods,
            plugins = visiblePlugins,
            gameOptions = gameOptions,
            selectedGameId = selectedGameId,
            selectedDataPathText = selectedDataPathText,
            realDeployEnabled = realDeployEnabledState,
            logText = logText,
            setupComplete = setupComplete,
            profileNameText = profileNameText,
            setupGameId = setupGameId,
            setupTargetPathText = setupTargetPathText,
            setupRealDeployEnabled = setupRealDeployEnabled,
            activeProfileName = activeProfileName,
            profileOptions = profileOptions,
            activeProfileId = activeProfileId,
            newProfileNameText = newProfileNameText,
            newProfileGameId = newProfileGameId,
            newProfileRealDeployEnabled = newProfileRealDeployEnabled,
            showProfileDialog = showProfileDialog,
            newProfileDataPathText = newProfileDataPathText,
            operationInProgress = operationInProgress,
            activeOperationText = activeOperationText,
            modContentIndexes = visibleModContentIndexes,
            pendingArchiveInstall = pendingArchiveInstall,
            selectedInstallerOptionIds = pendingInstallerSelectedOptionIds,
            showInstallerDialog = showInstallerDialog,
            installerDialogFullscreen = installerDialogFullscreen,
            selectedModFilePreview = selectedModFilePreview,
            showModFilePreviewDialog = showModFilePreviewDialog,
            modFilePreviewFullscreen = modFilePreviewFullscreen,
            secondScreenEnabled = secondScreenPluginCoordinator.enabled,
            fullscreenPanel = fullscreenPanel,
            overwriteEntries = overwriteEntries,
            showOverwriteDialog = showOverwriteDialog,
            overwriteBaselineExists = overwriteBaselineExists,
            overwriteMessage = overwriteMessage,
            selectedRootPathText = selectedRootPathText,

            deployRecoveryWarningText = deployRecoveryWarningText,
            showDeployRecoveryDialog = showDeployRecoveryDialog,

            showForceFullRedeployConfirmDialog = showForceFullRedeployConfirmDialog,
            showArchiveFolderSetupDialog = showArchiveFolderSetupDialog,
            archiveBrowserState = archiveBrowserState,
            allFilesAccessRequired = android.os.Build.VERSION.SDK_INT >= AllFilesAccessPolicy.ANDROID_11_API_LEVEL,
            allFilesAccessGranted = directFolderSelectionCoordinator.allFilesAccessGranted,
            showDirectFolderBrowser = directFolderSelectionCoordinator.showBrowser,
            directFolderBrowserTitle = directFolderSelectionCoordinator.browserTitle,
            directFolderBrowserRequiresWritable = directFolderSelectionCoordinator.browserRequiresWritable,
            directFolderBrowserState = directFolderSelectionCoordinator.browserState
        )
    }

    private fun buildUiActions(): DashboardActions {
        return DashboardActions(
            onVersionTap = {
                developerTapCount++
                if (!developerModeEnabled && developerTapCount >= 5) {
                    developerModeEnabled = true
                    appendLog("Developer tools unlocked.")
                }
            },
            onInstallMod = {
                archiveBrowserWorkflow.openBrowser()
            },
            onChooseArchiveFolder = {
                showArchiveFolderSetupDialog = false
                directFolderSelectionCoordinator.open(FolderPickMode.ArchiveLibraryFolder)
            },
            onDismissArchiveFolderSetup = {
                showArchiveFolderSetupDialog = false
            },
            onRefreshArchiveFolder = {
                archiveBrowserWorkflow.refresh()
            },
            onChangeArchiveFolder = {
                directFolderSelectionCoordinator.open(FolderPickMode.ArchiveLibraryFolder)
            },
            onInstallArchiveFromFolder = { stableId ->
                archiveBrowserWorkflow.installArchive(stableId)
            },
            onDeployMods = {
                deploymentActionWorkflowController.deploy()
            },
            onWriteLoadOrderFiles = {
                pluginActionWorkflowController.writeLoadOrderFiles()
            },
            onToggleMod = { modId ->
                modActionWorkflowController.toggleMod(modId)
            },
            onMoveModUp = { modId ->
                modActionWorkflowController.moveModUp(modId)
            },
            onMoveModDown = { modId ->
                modActionWorkflowController.moveModDown(modId)
            },
            onDeleteMod = { mod ->
                modActionWorkflowController.requestDeleteMod(mod)
            },
            onTogglePlugin = { normalizedPath ->
                pluginActionWorkflowController.togglePlugin(normalizedPath)
            },
            onMovePluginUp = { normalizedPath ->
                pluginActionWorkflowController.movePluginUp(normalizedPath)
            },
            onMovePluginDown = { normalizedPath ->
                pluginActionWorkflowController.movePluginDown(normalizedPath)
            },
            onSelectGame = { gameId ->
                selectedGameId = gameId
                loadSelectedGameConfigIntoUi()
                activityThreadRunner.runInBackground {
                    profileContentInspectionCoordinator.ensureDataBaselineIfMissing("selected game changed")
                    pluginSyncWorkflowController.syncWithNewEngineThenRefresh()
                }
            },
            onRealDeployChanged = { enabled ->
                realDeployEnabledState = enabled
            },
            onPickTargetFolder = {
                directFolderSelectionCoordinator.open(
                    if (setupComplete) {
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
            onShareLogs = {
                shareLogs()
            },
            onProfileNameChanged = { profileNameText = it },
            onSetupGameChanged = { gameId ->
                setupGameId = gameId
                setupGameDisplayName = GameCatalog.displayName(gameId)
            },
            onSetupTargetPathChanged = { setupTargetPathText = it },
            onSetupRealDeployChanged = { setupRealDeployEnabled = it },
            onCompleteSetup = {
                profileWorkflowController.completeSetup()
            },
            onSelectProfile = { profileId ->
                profileWorkflowController.switchProfile(profileId)
            },
            onNewProfileNameChanged = { newProfileNameText = it },
            onNewProfileGameChanged = { gameId ->
                newProfileGameId = gameId
                newProfileGameDisplayName = GameCatalog.displayName(gameId)
            },
            onNewProfileRealDeployChanged = { newProfileRealDeployEnabled = it },
            onCreateAdditionalProfile = {
                profileWorkflowController.createProfile()
            },
            onOpenProfileDialog = {
                showProfileDialog = true
            },
            onCloseProfileDialog = {
                showProfileDialog = false
            },
            onPickNewProfileTargetFolder = {
                directFolderSelectionCoordinator.open(FolderPickMode.NewProfileDataFolder)
            },
            onDeleteProfile = { profileId ->
                profileWorkflowController.deleteProfile(profileId)
            },
            onToggleInstallerOption = { optionId ->
                installerWorkflowController.toggleOption(optionId)
            },
            onConfirmInstaller = {
                installerWorkflowController.finalizeInstall()
            },
            onCancelInstaller = {
                installerWorkflowController.cancelInstall()
            },
            onToggleInstallerFullscreen = {
                previewDialogActionWorkflowController.toggleInstallerFullscreen()
            },
            onViewModFiles = { modId ->
                modActionWorkflowController.viewModFiles(modId)
            },
            onCloseModFilePreview = {
                previewDialogActionWorkflowController.closeModFilePreview()
            },
            onToggleModFilePreviewFullscreen = {
                previewDialogActionWorkflowController.toggleModFilePreviewFullscreen()
            },
            onToggleSecondScreen = {
                secondScreenPluginCoordinator.toggle()
            },
            onOpenModsFullscreen = {
                fullscreenPanelActionWorkflowController.openModsFullscreen()
            },
            onOpenPluginsFullscreen = {
                fullscreenPanelActionWorkflowController.openPluginsFullscreen()
            },
            onCloseFullscreenPanel = {
                fullscreenPanelActionWorkflowController.closeFullscreenPanel()
            },
            onApplyModOrder = { orderedModIds ->
                fullscreenPanelActionWorkflowController.applyModOrder(orderedModIds)
            },
            onApplyPluginOrder = { orderedPluginPaths ->
                fullscreenPanelActionWorkflowController.applyPluginOrder(orderedPluginPaths)
            },
            onOpenOverwriteFolder = {
                overwriteActionWorkflowController.openOverwriteFolder()
            },
            onCloseOverwriteFolder = {
                overwriteActionWorkflowController.closeOverwriteFolder()
            },
            onBuildResolvedDataGraph = {
                developerToolsWorkflowController.buildResolvedDataGraph()
            },
            onBuildDeploymentPlan = {
                deploymentActionWorkflowController.buildDeployPlan()
            },
            onShowArchiveLibrarySummary = {
                developerToolsWorkflowController.showArchiveLibrarySummary()
            },
            onBuildFullRedeployPlan = {
                deploymentActionWorkflowController.buildFullRedeployPlan()
            },
            onViewLastDeployJournal = {
                deployRecoveryWorkflowController.viewLastJournal()
            },
            onOpenDeployRecoveryDetails = {
                deployRecoveryWorkflowController.openRecoveryDetails()
            },
            onCloseDeployRecoveryDetails = {
                deployRecoveryWorkflowController.closeRecoveryDetails()
            },
            onDismissDeployRecoveryWarning = {
                deployRecoveryWorkflowController.dismissWarning()
            },
            onMarkDeployRecoveryReviewed = {
                deployRecoveryWorkflowController.markReviewed()
            },
            onRequestForceFullRedeploy = {
                showForceFullRedeployConfirmDialog = true
            },
            onConfirmForceFullRedeploy = {
                showForceFullRedeployConfirmDialog = false
                deploymentActionWorkflowController.forceFullRedeploy()
            },
            onCancelForceFullRedeploy = {
                showForceFullRedeployConfirmDialog = false
            },
            onRequestAllFilesAccess = {
                requestAllFilesAccess()
            },
            onDirectFolderBrowserOpenPath = { path ->
                directFolderSelectionCoordinator.openPath(path)
            },
            onDirectFolderBrowserNavigateUp = {
                directFolderSelectionCoordinator.navigateUp()
            },
            onDirectFolderBrowserSelectCurrent = {
                directFolderSelectionCoordinator.selectCurrent()
            },
            onDirectFolderBrowserCancel = {
                directFolderSelectionCoordinator.cancel()
            }
        )

    }

    private fun initializeComposeUi() {
        activityThreadRunner.runInBackground {
            loadSetupState()
            legacyProfileStorageMigrator.migrateIfNeeded()
            refreshGameOptions()
            loadSelectedGameConfigIntoUi()
            migratePrioritySpacingIfNeeded()

            profileContentInspectionCoordinator.ensureDataBaselineIfMissing("startup")

            val engine = profileScopedEngineFactory.create()

            if (engine != null) {
                deployRecoveryWorkflow.checkStartup(DeployRecoveryEngineAdapter(engine))
            }

            pluginSyncWorkflowController.syncWithExistingEngineThenRefresh(engine)
        }

        appendLog("UI ready.")
    }




    //log stuff
    private fun appendLog(message: String) {
        operationReporter.appendLog(message)
    }

    private fun appendError(message: String, throwable: Throwable? = null) {
        operationReporter.appendError(message, throwable)
    }

    private fun beginOperation(text: String) {
        operationReporter.beginOperation(text)
    }

    private fun finishOperation(successText: String) {
        operationReporter.finishOperation(successText)
    }

    private fun failOperation(message: String, throwable: Throwable? = null) {
        operationReporter.failOperation(message, throwable)
    }



    private fun refreshDashboard() {
        val engine = profileScopedEngineFactory.create() ?: return
        val result = dashboardRefreshWorkflow.build(
            engine = DashboardRefreshEngineAdapter(engine),
            selectedGameId = selectedGameId
        )

        runOnUiThread {
            visibleMods = result.mods
            visiblePlugins = result.plugins
            visibleModContentIndexes = result.modContentIndexes
            summaryText = result.summaryText
            secondScreenPluginCoordinator.refresh()
        }

        appendLog("Dashboard refreshed.")
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
    private fun savePickedDataFolderToSelectedGameConfig(path: String) {
        activityThreadRunner.runOnUiThreadBlocking {
            targetPathText = path
            selectedDataPathText = path
            dataPathReselectionRequired = false
            realDeployEnabledState = true
        }

        saveSelectedGameConfigFromUi()
        profileManagementWorkflow.saveActiveProfileFromDashboard()

        profileContentInspectionCoordinator.ensureDataBaselineIfMissing("target folder selected")
        refreshDashboard()

        appendLog("Saved direct Data folder path for $selectedGameId: $path")
    }
    private fun savePickedRootFolderToSelectedGameConfig(path: String) {
        activityThreadRunner.runOnUiThreadBlocking {
            rootTargetPathText = path
            selectedRootPathText = path
            rootPathReselectionRequired = false
            realDeployEnabledState = true
        }

        saveSelectedGameConfigFromUi()
        profileManagementWorkflow.saveActiveProfileFromDashboard()
        refreshDashboard()

        appendLog("Saved direct Game Root path for $selectedGameId: $path")
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


    private fun refreshGameOptions() {
        runOnUiThread {
            gameOptions = GameCatalog.supportedGameIds
            selectedGameId = GameCatalog.supportedOrDefault(selectedGameId)
            setupGameId = GameCatalog.supportedOrDefault(setupGameId)
            setupGameDisplayName = GameCatalog.displayName(setupGameId)
            newProfileGameId = GameCatalog.supportedOrDefault(newProfileGameId)
            newProfileGameDisplayName = GameCatalog.displayName(newProfileGameId)
        }
    }

    private fun loadSelectedGameConfigIntoUi() {
        val engine = profileScopedEngineFactory.create() ?: return
        val activeProfile = profileOptions.firstOrNull { it.profileId == activeProfileId }
        val previousGameId = selectedGameId
        val result = gameConfigurationWorkflow.load(
            engine = GameConfigurationEngineAdapter(engine),
            activeProfile = activeProfile,
            selectedGameId = selectedGameId
        )

        if (previousGameId != result.selectedGameId) {
            appendLog(
                "Corrected selectedGameId from $previousGameId to active profile game ${result.selectedGameId}"
            )
        }
        runOnUiThread {
            selectedGameId = result.selectedGameId
            applyDeploymentConfigUiState(result.uiState)
        }
        appendLog(result.logMessage)
    }

    private fun saveSelectedGameConfigFromUi() {
        val engine = profileScopedEngineFactory.create() ?: return
        val updatedConfig = gameConfigurationWorkflow.save(
            engine = GameConfigurationEngineAdapter(engine),
            input = GameConfigurationInput(
                selectedGameId = selectedGameId,
                targetDataPath = targetPathText,
                realDeployEnabled = realDeployEnabledState,
                targetRootPath = rootTargetPathText,
                dataPathReselectionRequired = dataPathReselectionRequired,
                rootPathReselectionRequired = rootPathReselectionRequired
            )
        )
        appendLog("Saved updated config from Compose state: $updatedConfig")
    }


    private fun migratePrioritySpacingIfNeeded() {
        val engine = profileScopedEngineFactory.create() ?: return
        priorityNormalizationWorkflow.migrateIfNeeded(
            PriorityNormalizationEngineAdapter(engine)
        )
    }






    private fun loadSetupState() {
        val repository = profileRepositoryFactory.create() ?: return
        val result = profileStartupWorkflow.load(
            ProfileStartupRepositoryAdapter(repository)
        )
        result.recoveryLogMessage?.let(::appendLog)

        activityThreadRunner.runOnUiThreadBlocking {
            setupComplete = result.setupState.setupComplete
            activeProfileId = result.setupState.activeProfileId
            activeProfileName = ProfileConfigUiMapper.activeProfileName(result.activeProfile)
            profileOptions = result.profiles
            applyProfileConfigUiState(
                result.activeProfile
                    ?.takeIf { result.setupState.setupComplete }
                    ?.let(ProfileConfigUiMapper::fromProfile)
                    ?: ProfileConfigUiMapper.emptyState()
            )
            visibleMods = emptyList()
            visiblePlugins = emptyList()
            visibleModContentIndexes = emptyMap()
        }

        appendLog("Loaded setup state: ${result.setupState}")
        appendLog("Loaded profile count: ${result.profiles.size}")
        appendProfileContextLog()
    }


    private fun appendProfileContextLog() {
        appendLog(
            "Profile context: activeProfileId=$activeProfileId, " +
                    "activeProfileName=$activeProfileName, " +
                    "selectedGameId=$selectedGameId, " +
                    "targetDataPath=$targetPathText"
        )
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

























    private fun applyDeploymentConfigUiState(state: DeploymentConfigUiState) {
        targetPathText = state.targetDataPath
        realDeployEnabledState = state.realDeployEnabled
        dataPathReselectionRequired = state.dataPathReselectionRequired
        selectedDataPathText = DeploymentConfigUiMapper.dataPathDisplayText(
            state.targetDataPath,
            state.dataPathReselectionRequired
        )
        rootTargetPathText = state.targetRootPath
        rootPathReselectionRequired = state.rootPathReselectionRequired
        selectedRootPathText = DeploymentConfigUiMapper.rootPathDisplayText(
            state.targetRootPath,
            state.rootPathReselectionRequired
        )
    }

    private fun applyProfileConfigUiState(state: ProfileConfigUiState) {
        selectedGameId = state.selectedGameId
        targetPathText = state.targetDataPath
        dataPathReselectionRequired = state.dataPathReselectionRequired
        selectedDataPathText = DeploymentConfigUiMapper.dataPathDisplayText(
            state.targetDataPath,
            state.dataPathReselectionRequired
        )
        rootTargetPathText = state.targetRootPath
        rootPathReselectionRequired = state.rootPathReselectionRequired
        selectedRootPathText = DeploymentConfigUiMapper.rootPathDisplayText(
            state.targetRootPath,
            state.rootPathReselectionRequired
        )
        realDeployEnabledState = state.realDeployEnabled
    }






    private fun requestAllFilesAccess() {
        val primary = allFilesAccessManager.appSpecificSettingsIntent() ?: return

        try {
            allFilesAccessSettingsLauncher.launch(primary)
        } catch (_: ActivityNotFoundException) {
            val fallback = allFilesAccessManager.fallbackSettingsIntent()
            if (fallback == null) {
                appendLog("All-files access settings are unavailable on this device.")
                return
            }

            try {
                allFilesAccessSettingsLauncher.launch(fallback)
            } catch (e: ActivityNotFoundException) {
                appendError("All-files access settings are unavailable: ${e.message}", e)
            }
        }
    }

}