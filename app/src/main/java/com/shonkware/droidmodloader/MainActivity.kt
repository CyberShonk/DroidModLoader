package com.shonkware.droidmodloader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.shonkware.droidmodloader.engine.profile.ProfileRepository
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.AppSetupState
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.ui.SecondScreenController
import java.io.File
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import android.os.Looper
import java.util.concurrent.CountDownLatch
import com.shonkware.droidmodloader.engine.repair.V050ArtifactRepairTool
import com.shonkware.droidmodloader.ui.workflow.OperationLogFormatter
import com.shonkware.droidmodloader.ui.workflow.OperationStatusController
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiState
import com.shonkware.droidmodloader.ui.workflow.ProfileConfigUiMapper
import com.shonkware.droidmodloader.ui.workflow.ProfileConfigUiState
import com.shonkware.droidmodloader.ui.workflow.PluginSyncWorkflowController
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
import com.shonkware.droidmodloader.ui.workflow.FolderPickMode
import com.shonkware.droidmodloader.ui.workflow.FolderPickerWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DeploymentActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DeploymentExecutionEngineAdapter
import com.shonkware.droidmodloader.ui.workflow.DeploymentExecutionWorkflow
import com.shonkware.droidmodloader.ui.workflow.DeployRecoveryWorkflowController
import com.shonkware.droidmodloader.ui.workflow.DeveloperToolsWorkflowController
import com.shonkware.droidmodloader.ui.workflow.OverwriteActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.FullscreenPanelActionWorkflowController
import com.shonkware.droidmodloader.ui.workflow.PreviewDialogActionWorkflowController

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }
    private var secondScreenController: SecondScreenController? = null
    private var secondScreenEnabled by mutableStateOf(false)
    private var folderPickMode by mutableStateOf(FolderPickMode.ActiveDataFolder)
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
    private var newProfileTreeUriText by mutableStateOf("No folder selected")
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
    private var selectedTreeUriText by mutableStateOf("No folder selected")
    private var selectedRootTreeUriText by mutableStateOf("No root folder selected")
    private var rootTargetPathText by mutableStateOf("")
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
    private var overwriteEntries by mutableStateOf<List<OverwriteEntry>>(emptyList())
    private var showOverwriteDialog by mutableStateOf(false)
    private var overwriteBaselineExists by mutableStateOf(false)
    private var overwriteMessage by mutableStateOf("")
    private var deployRecoveryWarningText by mutableStateOf("")
    private var showDeployRecoveryDialog by mutableStateOf(false)
    private var showForceFullRedeployConfirmDialog by mutableStateOf(false)
    private val pickTargetFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            appendLog("No target folder selected.")
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            folderPickerWorkflowController.handlePickedFolder(
                mode = folderPickMode,
                treeUri = uri.toString()
            )
        } catch (e: Exception) {
            appendError("Failed to persist folder permission: ${e.message}", e)
        }
    }
    private val operationStatusController = OperationStatusController()
    private val pluginSyncWorkflowController = PluginSyncWorkflowController(
        createEngine = { createModEngineForWorkflows() },
        syncPluginsFromCurrentState = { engine -> syncPluginsFromCurrentState(engine) },
        refreshDashboard = { refreshDashboard() }
    )
    private val pluginManagementWorkflow by lazy {
        PluginManagementWorkflow(
            createEngine = {
                createModEngineForWorkflows()?.let { engine ->
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
            updateLastOperationStatus = { status -> updateLastOperationStatus(status) },
            refreshDashboard = { refreshDashboard() }
        )
    }

    private val pluginActionWorkflowController by lazy {
        PluginActionWorkflowController(
            runInBackground = { task -> runInBackground(task) },
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
                createModEngineForWorkflows()?.let { engine ->
                    PendingInstallerEngineAdapter(
                        engine = engine,
                        syncPlugins = { syncPluginsFromCurrentState(engine) },
                        appendRoutingSummary = { mod ->
                            appendInstalledModRoutingSummary(engine, mod)
                        }
                    )
                }
            },
            beginOperation = { message -> beginOperation(message) },
            finishOperation = { message -> finishOperation(message) },
            failOperation = { message, throwable -> failOperation(message, throwable) },
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            updateLastOperationStatus = { status -> updateLastOperationStatus(status) },
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
            refreshDashboard = { refreshDashboard() }
        )
    }
    private val installerWorkflowController by lazy {
        InstallerWorkflowController(
            runInBackground = { task -> runInBackground(task) },
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
            runInBackground = { task -> runInBackground(task) },
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
            repositoryProvider = { createProfileRepository() },
            gameDisplayNameProvider = { gameId -> getGameDisplayName(gameId) },
            firstSetupInputProvider = {
                FirstSetupInput(
                    profileNameText = profileNameText,
                    gameId = setupGameId,
                    realDeployEnabled = setupRealDeployEnabled
                )
            },
            additionalProfileInputProvider = {
                AdditionalProfileInput(
                    profileNameText = newProfileNameText,
                    gameId = newProfileGameId,
                    targetTreeUriText = newProfileTreeUriText,
                    realDeployEnabled = newProfileRealDeployEnabled
                )
            },
            activeProfileIdProvider = { activeProfileId },
            dashboardProfileInputProvider = {
                DashboardProfileInput(
                    targetPathText = targetPathText,
                    selectedTreeUriText = selectedTreeUriText,
                    rootTargetPathText = rootTargetPathText,
                    selectedRootTreeUriText = selectedRootTreeUriText,
                    realDeployEnabled = realDeployEnabledState
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
                }
            },
            applyCreatedProfileUiState = { profiles, profile ->
                runOnUiThreadBlocking {
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
                    newProfileTreeUriText = DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED
                    newProfileRealDeployEnabled = false
                    showProfileDialog = false
                }
            },
            applySwitchedProfileUiState = { profile ->
                runOnUiThreadBlocking {
                    activeProfileId = profile.profileId
                    activeProfileName = profile.profileName
                    applyProfileConfigUiState(
                        ProfileConfigUiMapper.fromProfile(profile)
                    )
                    visibleMods = emptyList()
                    visiblePlugins = emptyList()
                    visibleModContentIndexes = emptyMap()
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
                }
            },
            applyDeletedProfileUiStateBlocking = { profiles, newActiveProfile ->
                runOnUiThreadBlocking {
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

                    visibleMods = emptyList()
                    visiblePlugins = emptyList()
                    visibleModContentIndexes = emptyMap()
                }
            },
            saveSelectedGameConfigFromUi = { saveSelectedGameConfigFromUi() },
            loadSelectedGameConfigIntoUi = { loadSelectedGameConfigIntoUi() },
            syncPluginsFromCurrentState = {
                val engine = createModEngineForWorkflows()
                if (engine != null) {
                    syncPluginsFromCurrentState(engine)
                }
            },
            refreshDashboard = { refreshDashboard() },
            appendLog = { message -> appendLog(message) },
            appendError = { message -> appendError(message) },
            updateLastOperationStatus = { status -> updateLastOperationStatus(status) }
        )
    }
    private val modManagementWorkflow by lazy {
        ModManagementWorkflow(
            withEngine = { action ->
                val engine = createModEngineForWorkflows()
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
            updateLastOperationStatus = { status -> updateLastOperationStatus(status) },
            refreshDashboard = { refreshDashboard() }
        )
    }
    private val modActionWorkflowController by lazy {
        ModActionWorkflowController(
            runInBackground = { task -> runInBackground(task) },
            onToggleModEnabled = { modId -> modManagementWorkflow.toggleModEnabled(modId) },
            onMoveModUp = { modId -> modManagementWorkflow.moveModUp(modId) },
            onMoveModDown = { modId -> modManagementWorkflow.moveModDown(modId) },
            onRequestDeleteMod = { mod -> showDeleteConfirmDialog(mod) },
            onViewModFiles = { modId -> openModFilePreview(modId) },
            onApplyModOrder = { orderedModIds -> modManagementWorkflow.applyModOrder(orderedModIds) }
        )
    }
    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        archiveImportWorkflowController.handleArchivePickerResult(uri)
    }
    private val archiveImportWorkflowController by lazy {
        ArchiveImportWorkflowController(
            appendLog = { message -> appendLog(message) },
            runInBackground = { task -> runInBackground(task) },
            handleImportedArchive = { uri -> archiveImportExecutionWorkflow.importArchive(uri) },
            showArchiveLibrarySummary = { runArchiveLibraryDebugSummary() }
        )
    }
    private val folderPickerWorkflowController by lazy {
        FolderPickerWorkflowController(
            runInBackground = { task -> runInBackground(task) },
            savePickedDataFolderToSelectedGameConfig = { treeUri ->
                savePickedDataFolderToSelectedGameConfig(treeUri)
            },
            savePickedRootFolderToSelectedGameConfig = { treeUri ->
                savePickedRootFolderToSelectedGameConfig(treeUri)
            },
            setNewProfileTreeUriText = { treeUri ->
                runOnUiThread {
                    newProfileTreeUriText = treeUri
                }
            },
            appendLog = { message -> appendLog(message) }
        )
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
                createModEngineForWorkflows()?.let { engine ->
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
            runInBackground = { task -> runInBackground(task) },
            runDeploy = { deploymentExecutionWorkflow.deploy() },
            runForceFullRedeploy = { deploymentExecutionWorkflow.forceFullRedeploy() },
            buildDeploymentPlan = { runDeploymentPlanDebugSummary() },
            buildFullRedeployPlan = { runFullRedeployPlanDebugSummary() }
        )
    }
    private val deployRecoveryWorkflowController by lazy {
        DeployRecoveryWorkflowController(
            runInBackground = { task -> runInBackground(task) },
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
                runLastDeployJournalDebugSummary()
            },
            markDeployRecoveryReviewed = {
                markLastDeployJournalReviewed()
            }
        )
    }
    private val developerToolsWorkflowController by lazy {
        DeveloperToolsWorkflowController(
            runInBackground = { task -> runInBackground(task) },
            repairV050Artifacts = {
                runV050ArtifactRepairTool()
            },
            buildResolvedDataGraph = {
                runResolvedDataGraphDebugSummary()
            },
            showArchiveLibrarySummary = {
                archiveImportWorkflowController.requestArchiveLibrarySummary()
            }
        )
    }
    private val overwriteActionWorkflowController by lazy {
        OverwriteActionWorkflowController(
            runInBackground = { task -> runInBackground(task) },
            openOverwriteFolderPanel = {
                openOverwriteFolderPanel()
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

    private val legacyProfileStorageMigrator by lazy {
        LegacyProfileStorageMigrator(
            filesDir = filesDir,
            externalFilesDirProvider = { getExternalFilesDir(null) },
            activeProfileIdProvider = { activeProfileId },
            profileStoragePaths = profileStoragePaths,
            appendLog = { message -> appendLog(message) },
            appendError = { message, throwable -> appendError(message, throwable) },
            updateLastOperationStatus = { status -> updateLastOperationStatus(status) }
        )
    }

    private val archiveImportFileStore by lazy {
        ArchiveImportFileStore(
            contentResolver = contentResolver,
            externalFilesDirProvider = { getExternalFilesDir(null) },
            profileInternalDirProvider = { profileStoragePaths.getProfileInternalDir() },
            appendError = { message -> appendError(message) }
        )
    }

    private val archiveImportExecutionWorkflow by lazy {
        ArchiveImportExecutionWorkflow(
            operationInProgressProvider = { operationInProgress },
            beginOperation = { message -> beginOperation(message) },
            createEngine = { createModEngineForWorkflows() },
            queryDisplayName = { uri -> queryDisplayName(uri) },
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
                appendInstalledModRoutingSummary(engine, mod)
            },
            refreshDashboard = { refreshDashboard() }
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

        if (secondScreenEnabled) {
            secondScreenController?.start()
            updateSecondScreen()
        }
    }

    override fun onPause() {
        secondScreenController?.stop()
        super.onPause()
    }

    private fun updateSecondScreen() {
        if (!secondScreenEnabled) return

        secondScreenController?.update(
            plugins = visiblePlugins,
            activeProfileName = activeProfileName
        )
    }

    private fun buildUiState(): DashboardUiState {
        return DashboardUiState(
            appName = "Droid Mod Loader",
            versionLabel = "Version 0.5.5 Beta",
            developerModeEnabled = developerModeEnabled,
            lastOperationStatus = lastOperationStatus,
            summaryText = summaryText,
            mods = visibleMods,
            plugins = visiblePlugins,
            gameOptions = gameOptions,
            selectedGameId = selectedGameId,
            selectedTreeUriText = selectedTreeUriText,
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
            newProfileTreeUriText = newProfileTreeUriText,
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
            secondScreenEnabled = secondScreenEnabled,
            fullscreenPanel = fullscreenPanel,
            overwriteEntries = overwriteEntries,
            showOverwriteDialog = showOverwriteDialog,
            overwriteBaselineExists = overwriteBaselineExists,
            overwriteMessage = overwriteMessage,
            selectedRootTreeUriText = selectedRootTreeUriText,

            deployRecoveryWarningText = deployRecoveryWarningText,
            showDeployRecoveryDialog = showDeployRecoveryDialog,

            showForceFullRedeployConfirmDialog = showForceFullRedeployConfirmDialog,

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
            onImportArchive = {
                appendLog("Opening document picker...")
                importZipLauncher.launch(arrayOf("*/*"))
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
                runInBackground {
                    ensureDataBaselineIfMissing("selected game changed")
                    pluginSyncWorkflowController.syncWithNewEngineThenRefresh()
                }
            },
            onRealDeployChanged = { enabled ->
                realDeployEnabledState = enabled
            },
            onPickTargetFolder = {
                folderPickMode = FolderPickMode.ActiveDataFolder
                pickTargetFolderLauncher.launch(null)
            },
            onPickRootTargetFolder = {
                folderPickMode = FolderPickMode.ActiveGameRootFolder
                pickTargetFolderLauncher.launch(null)
            },
            onSaveSettings = {
                runInBackground {
                    profileWorkflowController.saveSettings()
                }
            },
            onShareLogs = {
                shareLogs()
            },
            onProfileNameChanged = { profileNameText = it },
            onSetupGameChanged = { gameId ->
                setupGameId = gameId
                setupGameDisplayName = getGameDisplayName(gameId)
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
                newProfileGameDisplayName = getGameDisplayName(gameId)
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
                folderPickMode = FolderPickMode.NewProfileDataFolder
                pickTargetFolderLauncher.launch(null)
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
                toggleSecondScreenPluginDisplay()
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
            onRepairV050Artifacts = {
                developerToolsWorkflowController.repairV050Artifacts()
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

            )

    }

    private fun initializeComposeUi() {
        runInBackground {
            loadSetupState()
            legacyProfileStorageMigrator.migrateIfNeeded()
            refreshGameOptions()
            loadSelectedGameConfigIntoUi()
            migratePrioritySpacingIfNeeded()

            ensureDataBaselineIfMissing("startup")

            val engine = createModEngineForWorkflows()

            if (engine != null) {
                checkLastDeployJournalOnStartup(engine)
            }

            pluginSyncWorkflowController.syncWithExistingEngineThenRefresh(engine)
        }

        appendLog("UI ready.")
    }
    private fun runInBackground(block: () -> Unit) {
        Thread {
            block()
        }.start()
    }

    private fun checkLastDeployJournalOnStartup(engine: ModEngine) {
        try {
            val warning = engine.getDeploymentJournalStartupWarning(selectedGameId)

            if (warning.isNullOrBlank()) {
                runOnUiThread {
                    deployRecoveryWarningText = ""
                    showDeployRecoveryDialog = false
                }
                return
            }

            appendLog("----- Previous Deploy Journal Warning -----")
            warning.lineSequence().forEach { line ->
                appendLog(line)
            }
            appendLog("----- Previous Deploy Journal Warning End -----")

            runOnUiThread {
                deployRecoveryWarningText = warning
                showDeployRecoveryDialog = false
            }

            updateLastOperationStatus("Previous deploy may need review.")

        } catch (e: Exception) {
            appendError("Failed to check previous deploy journal: ${e.message}", e)
        }
    }

    //log stuff
    private fun appendLog(message: String) {
        val line = OperationLogFormatter.formatLogLine(message)

        Log.d(TAG, line)
        appendLogToFile(line)

        runOnUiThread {
            logText = if (logText.isBlank()) {
                line
            } else {
                logText + "\n" + line
            }
        }
    }
    private fun appendError(message: String, throwable: Throwable? = null) {
        val line = OperationLogFormatter.formatLogLine("ERROR: $message")

        if (throwable != null) {
            Log.e(TAG, line, throwable)
            appendLogToFile(line + "\n" + Log.getStackTraceString(throwable))
        } else {
            Log.e(TAG, line)
            appendLogToFile(line)
        }

        runOnUiThread {
            logText = if (logText.isBlank()) {
                line
            } else {
                logText + "\n" + line
            }
        }
    }
    private fun beginOperation(text: String) {
        val status = operationStatusController.begin(text)

        runOnUiThread {
            operationInProgress = true
            activeOperationText = status.activeText
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
        appendLog(status.logText)
    }
    private fun finishOperation(successText: String) {
        val status = operationStatusController.finish(successText)

        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
        appendLog(status.logText)
    }
    private fun failOperation(message: String, throwable: Throwable? = null) {
        val status = operationStatusController.fail(message)

        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
        appendError(status.logText, throwable)
    }

    private fun createModEngineForWorkflows(): ModEngine? {
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val profileInternalDir = profileStoragePaths.getProfileInternalDir()
        val profileStateDir = profileStoragePaths.getProfileStateDir(externalBaseDir)

        val tempDir = File(profileInternalDir, "temp")
        val modsDir = File(profileInternalDir, "mods")

        val stateFile = File(profileStateDir, "installed_mods.json")
        val pluginListFile = File(profileStateDir, "plugins.json")
        val pluginsTxtFile = File(profileStateDir, "plugins.txt")
        val loadorderTxtFile = File(profileStateDir, "loadorder.txt")
        val deploymentManifestFile = File(profileStateDir, "deployment_manifest.json")
        val gameConfigFile = File(profileStateDir, "game_deployment_configs.json")
        val archiveLibraryDir = File(externalBaseDir, "downloads/archive_library")
        val downloadedArchiveListFile = File(profileStateDir, "downloaded_archives.json")

        val deployDir = File(
            externalBaseDir,
            "deploy_target/profiles/${profileStoragePaths.getActiveProfileStorageKey()}/$selectedGameId/Data"
        )

        tempDir.mkdirs()
        modsDir.mkdirs()
        profileStateDir.mkdirs()
        archiveLibraryDir.mkdirs()
        deployDir.mkdirs()

        return ModEngine(
            appContext = applicationContext,
            tempDir = tempDir,
            modsDir = modsDir,
            stateFile = stateFile,
            deploymentManifestFile = deploymentManifestFile,
            deployRootDir = deployDir,
            gameConfigFile = gameConfigFile,
            pluginListFile = pluginListFile,
            pluginsTxtFile = pluginsTxtFile,
            loadorderTxtFile = loadorderTxtFile,
            archiveLibraryDir = archiveLibraryDir,
            downloadedArchiveListFile = downloadedArchiveListFile
        )
    }
    private fun refreshDashboard() {
        val engine = createModEngineForWorkflows() ?: return

        val mods = engine.getCurrentMods().sortedBy { it.priority }
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }


        val installedCount = mods.size
        val enabledCount = mods.count { it.enabled }
        val savedStateExists = engine.hasSavedState()

        val stateSourceText = when {
            savedStateExists -> "Saved state present"
            installedCount > 0 -> "Using folder-discovered state"
            else -> "No current mod state"
        }

        val highestPriorityMod = mods.lastOrNull()?.name ?: "None"

        val config = engine.getGameDeploymentConfig(selectedGameId)
        val deployMode = when {
            config == null -> "Simulated"
            config.realDeployEnabled && !config.targetTreeUri.isNullOrBlank() -> "Tree URI"
            config.realDeployEnabled && engine.validateTargetDataPath(config.targetDataPath) -> "Real Path"
            else -> "Simulated"
        }

        val targetStatus = when {
            config == null -> "Not configured"
            !config.targetTreeUri.isNullOrBlank() -> "Folder selected"
            config.targetDataPath.isNotBlank() -> config.targetDataPath
            else -> "Not configured"
        }

        val newSummary = buildString {
            appendLine("Installed mods: $installedCount")
            appendLine("Enabled mods: $enabledCount")
            appendLine("Plugins: ${plugins.size}")
            appendLine("State source: $stateSourceText")
            appendLine("Highest priority mod: $highestPriorityMod")
            appendLine("Deploy mode: $deployMode")
            appendLine("Target: $targetStatus")
        }

        val contentIndexes = mods.associate { mod ->
            mod.id to engine.indexModContent(mod)
        }

        runOnUiThread {
            visibleMods = mods
            visiblePlugins = plugins
            visibleModContentIndexes = contentIndexes
            summaryText = newSummary
            updateSecondScreen()
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
                    runInBackground { modManagementWorkflow.deleteInstalledMod(mod.id) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    private fun savePickedDataFolderToSelectedGameConfig(treeUri: String) {
        runOnUiThreadBlocking {
            selectedTreeUriText = treeUri
            realDeployEnabledState = true
        }

        saveSelectedGameConfigFromUi()
        profileManagementWorkflow.saveActiveProfileFromDashboard()

        ensureDataBaselineIfMissing("target folder selected")
        refreshDashboard()

        appendLog("Saved picked Data folder URI for $selectedGameId")
    }
    private fun savePickedRootFolderToSelectedGameConfig(treeUri: String) {
        runOnUiThreadBlocking {
            selectedRootTreeUriText = treeUri
            realDeployEnabledState = true
        }

        saveSelectedGameConfigFromUi()
        profileManagementWorkflow.saveActiveProfileFromDashboard()
        refreshDashboard()

        appendLog("Saved picked game root folder URI for $selectedGameId")
    }
    private fun buildDiagnosticSummary(): String {
        val engine = createModEngineForWorkflows()

        val mods = engine?.getCurrentMods() ?: emptyList()
        val plugins = engine?.getCurrentPlugins() ?: emptyList()

        val enabledMods = mods.count { it.enabled }
        val enabledPlugins = plugins.count { it.enabled }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = packageInfo.longVersionCode

        return buildString {
            appendLine("=== Droid Mod Loader Diagnostic Summary ===")
            appendLine()
            appendLine("App Version: $versionName ($versionCode)")
            appendLine("Display Version: $versionName")
            appendLine("Package: $packageName")
            appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("Device: ${android.os.Build.MODEL}")
            appendLine()
            appendLine("Installed Mods: ${mods.size}")
            appendLine("Enabled Mods: $enabledMods")
            appendLine("Plugins: ${plugins.size}")
            appendLine("Enabled Plugins: $enabledPlugins")
            appendLine("Last Operation Status: $lastOperationStatus")
            appendLine()
            appendLine("Developer Mode Enabled: $developerModeEnabled")
            appendLine()
            appendLine("Current Logs:")
            appendLine(logText)
        }
    }
    private fun shareLogs() {
        val summary = buildDiagnosticSummary()
        appendLogToFile("=== Diagnostic Snapshot ===\n$summary")

        val externalBaseDir = getExternalFilesDir(null)
        val logFileText = if (externalBaseDir != null) {
            val logFile = File(File(externalBaseDir, "logs"), "session_log.txt")
            if (logFile.exists()) logFile.readText() else "(no persistent log file)"
        } else {
            "(external files dir unavailable)"
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                summary + "\n\n=== Persistent Log File ===\n" + logFileText
            )
            type = "text/plain"
        }

        startActivity(Intent.createChooser(sendIntent, "Share Logs"))
    }
    private fun updateLastOperationStatus(status: String) {
        lastOperationStatus = status
    }
    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }

        return null
    }
    private fun appendLogToFile(message: String) {
        try {
            val externalBaseDir = getExternalFilesDir(null) ?: return
            val logDir = File(externalBaseDir, "logs")
            val logFile = File(logDir, "session_log.txt")

            logDir.mkdirs()
            logFile.appendText(message + "\n")
        } catch (_: Exception) {
        }
    }
    private fun syncPluginsFromCurrentState(engine: ModEngine) {
        appendLog("Scanning plugins from current mod state and target Data folder...")

        val previous = engine.loadPlugins().associateBy { it.normalizedPath }

        val dataFolderPlugins = engine.scanDataFolderPlugins(selectedGameId)
        val managedPlugins = engine.discoverPluginsFromCurrentMods()
        val enabledModCount = engine.getEnabledCurrentMods().size

        val officialDataPlugins = dataFolderPlugins.filter {
            it.sourceType == "base_game" || it.sourceType == "official_dlc"
        }

        val unmanagedDataPlugins = dataFolderPlugins.filter {
            it.sourceType == "unmanaged_data"
        }

        val dataPluginPaths = dataFolderPlugins.map { it.normalizedPath }.toSet()

        val managedMerged = managedPlugins
            .map { managed ->
                val existing = previous[managed.normalizedPath]

                managed.copy(
                    enabled = existing?.enabled ?: true,
                    priority = existing?.priority ?: Int.MAX_VALUE,
                    sourceType = "managed_mod",
                    locked = false,
                    filePresentInDataFolder = managed.normalizedPath in dataPluginPaths
                )
            }

        val unmanagedMerged = unmanagedDataPlugins.map { unmanaged ->
            val existing = previous[unmanaged.normalizedPath]

            unmanaged.copy(
                enabled = existing?.enabled ?: false,
                priority = existing?.priority ?: Int.MAX_VALUE,
                locked = false,
                filePresentInDataFolder = true
            )
        }

        val officialMerged = officialDataPlugins
            .sortedBy { it.priority }
            .map { official ->
                official.copy(
                    enabled = true,
                    locked = true,
                    filePresentInDataFolder = true
                )
            }

        val officialPaths = officialMerged.map { it.normalizedPath }.toSet()

        val nonOfficial = (managedMerged + unmanagedMerged)
            .filterNot { it.normalizedPath in officialPaths }
            .distinctBy { it.normalizedPath }
            .sortedWith(
                compareBy<PluginEntry> { previous[it.normalizedPath]?.priority ?: it.priority }
                    .thenBy { it.pluginName.lowercase() }
            )

        val merged = officialMerged + nonOfficial

        val normalized = engine.normalizePluginPriorities(merged)
        engine.saveCurrentPlugins(normalized)
        appendLog("Selected game: $selectedGameId")
        appendLog("Enabled mod count scanned for plugins: $enabledModCount")
        appendLog("Data folder plugin count: ${dataFolderPlugins.size}")
        appendLog("Managed plugin count: ${managedPlugins.size}")
        appendLog("Plugin scan complete. Plugin count: ${normalized.size}")
    }

    private fun refreshGameOptions() {
        runOnUiThread {
            gameOptions = getSupportedGameIds()

            if (selectedGameId !in gameOptions) {
                selectedGameId = "skyrim_le"
            }

            if (setupGameId !in gameOptions) {
                setupGameId = "skyrim_le"
                setupGameDisplayName = getGameDisplayName(setupGameId)
            }

            if (newProfileGameId !in gameOptions) {
                newProfileGameId = "skyrim_le"
                newProfileGameDisplayName = getGameDisplayName(newProfileGameId)
            }
        }
    }
    private fun loadSelectedGameConfigIntoUi() {
        val activeProfile = profileOptions.firstOrNull { it.profileId == activeProfileId }

        if (activeProfile != null && selectedGameId != activeProfile.gameId) {
            appendLog(
                "Corrected selectedGameId from $selectedGameId to active profile game ${activeProfile.gameId}"
            )
            selectedGameId = activeProfile.gameId
        }

        val engine = createModEngineForWorkflows() ?: return
        val config = engine.getGameDeploymentConfig(selectedGameId)
        if (config == null) {
            val fallbackProfile = activeProfile?.takeIf { it.gameId == selectedGameId }

            if (fallbackProfile != null) {
                val recoveredConfig = DeploymentConfigUiMapper.configFromProfile(fallbackProfile)

                val configs = engine.loadGameDeploymentConfigs().toMutableList()
                val index = configs.indexOfFirst { it.gameId == recoveredConfig.gameId }

                if (index >= 0) {
                    configs[index] = recoveredConfig
                } else {
                    configs.add(recoveredConfig)
                }

                engine.saveGameDeploymentConfigs(configs)

                runOnUiThread {
                    applyDeploymentConfigUiState(
                        DeploymentConfigUiMapper.fromConfig(recoveredConfig)
                    )
                }

                appendLog("Recovered missing config from active profile: $recoveredConfig")
                return
            }

            runOnUiThread {
                applyDeploymentConfigUiState(
                    DeploymentConfigUiMapper.emptyState()
                )
            }

            appendLog("No config found for gameId=$selectedGameId")
            return
        }

        runOnUiThread {
            applyDeploymentConfigUiState(
                DeploymentConfigUiMapper.fromConfig(config)
            )
        }

        appendLog("Loaded config into Compose state: $config")
    }
    private fun saveSelectedGameConfigFromUi() {
        val engine = createModEngineForWorkflows() ?: return

        val existingConfigs = engine.loadGameDeploymentConfigs().toMutableList()

        val updatedConfig = DeploymentConfigUiMapper.configFromUi(
            selectedGameId = selectedGameId,
            displayName = getGameDisplayName(selectedGameId),
            targetPathText = targetPathText,
            realDeployEnabled = realDeployEnabledState,
            selectedTreeUriText = selectedTreeUriText,
            rootTargetPathText = rootTargetPathText,
            selectedRootTreeUriText = selectedRootTreeUriText
        )

        val index = existingConfigs.indexOfFirst { it.gameId == selectedGameId }
        if (index >= 0) {
            existingConfigs[index] = updatedConfig
        } else {
            existingConfigs.add(updatedConfig)
        }

        engine.saveGameDeploymentConfigs(existingConfigs)
        appendLog("Saved updated config from Compose state: $updatedConfig")
    }

    private fun migratePrioritySpacingIfNeeded() {
        val engine = createModEngineForWorkflows() ?: return

        val mods = engine.getCurrentMods().sortedBy { it.priority }
        val normalizedMods = engine.normalizeModPriorities(mods)

        if (mods != normalizedMods) {
            engine.saveCurrentMods(normalizedMods)
            appendLog("Migrated mod priorities to sequential 1-based ordering.")
        }

        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }
        val normalizedPlugins = engine.normalizePluginPriorities(plugins)

        if (plugins != normalizedPlugins) {
            engine.saveCurrentPlugins(normalizedPlugins)
            appendLog("Migrated plugin priorities to sequential 1-based ordering.")
        }
    }

    private fun createProfileRepository(): ProfileRepository? {
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val stateDir = File(externalBaseDir, "state")
        val profilesFile = File(stateDir, "profiles.json")
        val setupStateFile = File(stateDir, "app_setup.json")

        return ProfileRepository(
            profilesFile = profilesFile,
            setupStateFile = setupStateFile
        )
    }

    private fun getGameDisplayName(gameId: String): String {
        return when (gameId) {
            "skyrim_le" -> "Skyrim Legendary Edition"
            "oblivion" -> "Oblivion"
            "fallout_3" -> "Fallout 3"
            "fallout_nv" -> "Fallout New Vegas"
            "fallout_4" -> "Fallout 4"
            else -> gameId
        }
    }

    private fun loadSetupState() {
        val repo = createProfileRepository() ?: return

        val loadedState = repo.loadSetupState()
        val profiles = repo.loadProfiles()

        var resolvedState = loadedState
        var activeProfile = profiles.firstOrNull { it.profileId == loadedState.activeProfileId }

        if (activeProfile == null && profiles.isNotEmpty()) {
            val fallback = profiles.first()
            activeProfile = fallback

            resolvedState = AppSetupState(
                setupComplete = true,
                activeProfileId = fallback.profileId
            )

            repo.saveSetupState(resolvedState)
            appendLog("Recovered missing active profile using: ${fallback.profileName}")
        }

        runOnUiThreadBlocking {
            setupComplete = resolvedState.setupComplete
            activeProfileId = resolvedState.activeProfileId
            profileOptions = profiles

            if (resolvedState.setupComplete && activeProfile != null) {
                applyProfileConfigUiState(
                    ProfileConfigUiMapper.fromProfile(activeProfile)
                )
                visibleMods = emptyList()
                visiblePlugins = emptyList()
                visibleModContentIndexes = emptyMap()
            } else {
                applyProfileConfigUiState(
                    ProfileConfigUiMapper.emptyState()
                )

                visibleMods = emptyList()
                visiblePlugins = emptyList()
                visibleModContentIndexes = emptyMap()
            }
        }

        appendLog("Loaded setup state: $resolvedState")
        appendLog("Loaded profile count: ${profiles.size}")
        appendProfileContextLog()
    }

    private fun appendProfileContextLog() {
        appendLog(
            "Profile context: activeProfileId=$activeProfileId, " +
                    "activeProfileName=$activeProfileName, " +
                    "selectedGameId=$selectedGameId, " +
                    "targetTreeUri=$selectedTreeUriText"
        )
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    private fun openModFilePreview(modId: String) {
        val engine = createModEngineForWorkflows() ?: return

        val mod = engine.getCurrentMods().firstOrNull { it.id == modId }
        if (mod == null) {
            appendError("Could not open file preview. Mod not found: $modId")
            return
        }

        try {
            val preview = engine.buildModFilePreview(mod)

            runOnUiThread {
                selectedModFilePreview = preview
                showModFilePreviewDialog = true
                modFilePreviewFullscreen = false
            }

            appendLog("Opened file preview for mod: ${mod.name}")
        } catch (e: Exception) {
            appendError("Failed to build file preview for $modId: ${e.message}", e)
        }
    }

    private fun toggleSecondScreenPluginDisplay() {
        secondScreenEnabled = !secondScreenEnabled

        if (secondScreenEnabled) {
            secondScreenController?.start()
            updateSecondScreen()
            appendLog("Second screen plugin display enabled.")
            updateLastOperationStatus("Second screen plugin display enabled.")
            showToast("Second screen plugin display enabled.")
        } else {
            secondScreenController?.stop()
            appendLog("Second screen plugin display disabled.")
            updateLastOperationStatus("Second screen plugin display disabled.")
            showToast("Second screen plugin display disabled.")
        }
    }

    private fun openOverwriteFolderPanel() {
        val engine = createModEngineForWorkflows() ?: return

        try {
            ensureDataBaselineIfMissing("opening overwrite folder")

            val result = engine.scanOverwriteFiles(selectedGameId)

            runOnUiThread {
                overwriteEntries = result.entries
                overwriteBaselineExists = result.baselineExists
                overwriteMessage = result.message
                showOverwriteDialog = true
            }

            appendLog("Opened overwrite folder panel. ${result.message}")
        } catch (e: Exception) {
            appendError("Failed to scan overwrite files: ${e.message}", e)
        }
    }
    private fun ensureDataBaselineIfMissing(reason: String) {
        val engine = createModEngineForWorkflows() ?: return

        try {
            appendLog(engine.getDeploymentTargetDebugSummary(selectedGameId))

            if (engine.hasDataBaseline(selectedGameId)) {
                appendLog("Data baseline already exists for $selectedGameId.")
                return
            }

            val snapshot = engine.rebuildDataBaseline(selectedGameId)

            runOnUiThread {
                overwriteBaselineExists = true
                overwriteMessage = "Indexed ${snapshot.files.size} existing Data folder files."
            }

            appendLog("Created Data baseline automatically for $selectedGameId.")
            appendLog("Baseline reason: $reason")
            appendLog("Baseline file count: ${snapshot.files.size}")

            updateLastOperationStatus("Indexed existing Data folder automatically.")
        } catch (e: Exception) {
            appendError("Automatic Data baseline failed: ${e.message}", e)
        }
    }

    private fun getSupportedGameIds(): List<String> {
        return listOf(
            "skyrim_le",
            "oblivion",
            "fallout_3",
            "fallout_nv"
        )
    }

    private fun runOnUiThreadBlocking(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }

        val latch = CountDownLatch(1)

        runOnUiThread {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }

        latch.await()
    }

    private fun appendInstalledModRoutingSummary(
        engine: ModEngine,
        mod: Mod
    ) {
        try {
            val index = engine.indexModContent(mod)

            appendLog("Installed mod routing summary for ${mod.name}:")
            appendLog("  Data files: ${index.dataFiles.size}")
            appendLog("  Game root files: ${index.gameRootFiles.size}")
            appendLog("  Manager-only files: ${index.managerOnlyFiles.size}")
            appendLog("  Unknown files: ${index.unknownFiles.size}")

            index.gameRootFiles.take(10).forEach { entry ->
                appendLog("  ROOT: ${entry.normalizedPath}")
            }

            index.dataFiles.take(10).forEach { entry ->
                appendLog("  DATA: ${entry.normalizedPath}")
            }
        } catch (e: Exception) {
            appendError("Failed to build installed mod routing summary: ${e.message}", e)
        }
    }

    private fun runV050ArtifactRepairTool() {
        if (operationInProgress) {
            appendLog("Ignoring repair request: operation already in progress.")
            return
        }

        beginOperation("Repairing v0.5.0-beta artifacts...")

        try {
            val externalBaseDir = getExternalFilesDir(null)
                ?: throw IllegalStateException("External files directory is null.")

            val profileInternalDir = profileStoragePaths.getProfileInternalDir()
            val modsDir = File(profileInternalDir, "mods")
            val profileStateDir = profileStoragePaths.getProfileStateDir(externalBaseDir)

            val backupRootDir = File(profileStateDir, "repair_backups/v050_artifacts")
            val reportDir = File(profileStateDir, "repair_reports")

            val dataTreeUri = selectedTreeUriText
                .takeIf { it != "No folder selected" && it.isNotBlank() }

            val rootTreeUri = selectedRootTreeUriText
                .takeIf { it != "No root folder selected" && it.isNotBlank() }

            val dataPath = targetPathText
                .trim()
                .takeIf { it.isNotBlank() }

            val rootPath = rootTargetPathText
                .trim()
                .takeIf { it.isNotBlank() }

            val tool = V050ArtifactRepairTool(
                context = applicationContext,
                backupRootDir = backupRootDir,
                reportDir = reportDir
            )

            val result = tool.repair(
                modsDir = modsDir,
                dataTreeUri = dataTreeUri,
                dataPath = dataPath,
                rootTreeUri = rootTreeUri,
                rootPath = rootPath
            )

            val engine = createModEngineForWorkflows()
            if (engine != null) {
                val mods = engine.getCurrentMods()
                var rebuiltCount = 0

                for (mod in mods) {
                    if (engine.rebuildModFileIndex(mod.id)) {
                        rebuiltCount++
                    }
                }

                appendLog("Rebuilt mod file indexes after repair: $rebuiltCount")

                syncPluginsFromCurrentState(engine)
            }

            appendLog("v0.5.0-beta artifact repair complete.")
            appendLog("Installed mod files renamed: ${result.installedModFilesRenamed}")
            appendLog("Installed mod folders unwrapped: ${result.installedModFoldersUnwrapped}")
            appendLog("Target files renamed: ${result.targetFilesRenamed}")
            appendLog("Duplicate folders merged: ${result.duplicateFoldersMerged}")
            appendLog("Conflicts quarantined: ${result.conflictsQuarantined}")
            appendLog("Skipped: ${result.skippedCount}")
            appendLog("Repair report: ${result.reportFile.absolutePath}")
            appendLog("RESULT: PASS")

            finishOperation("Repair complete.")
        } catch (e: Exception) {
            appendError("v0.5.0-beta artifact repair failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Repair failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- v0.5.0-beta Artifact Repair End -----")
    }

    private fun runResolvedDataGraphDebugSummary() {
        if (operationInProgress) {
            appendLog("Ignoring resolved graph request: operation already in progress.")
            return
        }

        beginOperation("Building resolved data graph...")

        try {
            val engine = createModEngineForWorkflows()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            val summary = engine.buildResolvedDataGraphDebugSummary()

            appendLog("----- Resolved Data Graph Summary -----")
            summary.lineSequence().forEach { line ->
                appendLog(line)
            }
            appendLog("----- Resolved Data Graph Summary End -----")
            appendLog("RESULT: PASS")

            finishOperation("Resolved data graph built.")
        } catch (e: Exception) {
            appendError("Resolved data graph failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Resolved data graph failed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun runDeploymentPlanDebugSummary() {
        if (operationInProgress) {
            appendLog("Ignoring deploy plan request: operation already in progress.")
            return
        }

        beginOperation("Building deploy plan...")

        try {
            val engine = createModEngineForWorkflows()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            val summary = engine.buildDeploymentPlanDebugSummary(selectedGameId)

            appendLog("----- Deploy Plan Summary -----")
            summary.lineSequence().forEach { line ->
                appendLog(line)
            }
            appendLog("----- Deploy Plan Summary End -----")
            appendLog("No files were changed.")
            appendLog("RESULT: PASS")

            finishOperation("Deploy plan built.")
        } catch (e: Exception) {
            appendError("Deploy plan failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Deploy plan failed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun runArchiveLibraryDebugSummary() {
        if (operationInProgress) {
            appendLog("Ignoring archive library summary request: operation already in progress.")
            return
        }

        beginOperation("Building archive library summary...")

        try {
            val engine = createModEngineForWorkflows()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            val summary = engine.buildDownloadedArchiveSummary()

            appendLog("----- Archive Library Summary -----")
            summary.lineSequence().forEach { line ->
                appendLog(line)
            }
            appendLog("----- Archive Library Summary End -----")
            appendLog("No files were changed.")
            appendLog("RESULT: PASS")

            finishOperation("Archive library summary built.")
        } catch (e: Exception) {
            appendError("Archive library summary failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Archive library summary failed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun markLastDeployJournalReviewed() {
        val engine = createModEngineForWorkflows()
        if (engine == null) {
            appendError("Could not mark deploy journal reviewed: engine unavailable.")
            return
        }

        try {
            val changed = engine.markDeploymentJournalReviewed(selectedGameId)

            if (changed) {
                appendLog("Marked unfinished deploy journal as reviewed.")
                updateLastOperationStatus("Previous deploy warning reviewed.")
            } else {
                appendLog("No unfinished deploy journal needed review.")
            }

            runOnUiThread {
                deployRecoveryWarningText = ""
                showDeployRecoveryDialog = false
            }
        } catch (e: Exception) {
            appendError("Failed to mark deploy journal reviewed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun runLastDeployJournalDebugSummary() {
        if (operationInProgress) {
            appendLog("Ignoring deploy journal request: operation already in progress.")
            return
        }

        beginOperation("Reading last deploy journal...")

        try {
            val engine = createModEngineForWorkflows()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            appendLog("----- Last Deploy Journal -----")
            engine.getDeploymentJournalDebugSummary(selectedGameId)
                .lineSequence()
                .forEach { line ->
                    appendLog(line)
                }
            appendLog("----- Last Deploy Journal End -----")
            appendLog("No files were changed.")
            appendLog("RESULT: PASS")

            finishOperation("Deploy journal read.")
        } catch (e: Exception) {
            appendError("Failed to read deploy journal: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Deploy journal read failed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun runFullRedeployPlanDebugSummary() {
        if (operationInProgress) {
            appendLog("Ignoring full redeploy plan request: operation already in progress.")
            return
        }

        beginOperation("Building full redeploy plan...")

        try {
            val engine = createModEngineForWorkflows()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            val summary = engine.buildFullRedeployPlanDebugSummary(selectedGameId)

            appendLog("----- Full Redeploy Plan Summary -----")
            summary.lineSequence().forEach { line ->
                appendLog(line)
            }
            appendLog("----- Full Redeploy Plan Summary End -----")
            appendLog("No files were changed.")
            appendLog("RESULT: PASS")

            finishOperation("Full redeploy plan built.")
        } catch (e: Exception) {
            appendError("Full redeploy plan failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Full redeploy plan failed: ${e.message}", e)
        }

        refreshDashboard()
    }

    private fun applyDeploymentConfigUiState(state: DeploymentConfigUiState) {
        targetPathText = state.targetDataPath
        realDeployEnabledState = state.realDeployEnabled
        selectedTreeUriText = state.targetTreeUriText
        rootTargetPathText = state.targetRootPath
        selectedRootTreeUriText = state.targetRootTreeUriText
    }

    private fun applyProfileConfigUiState(state: ProfileConfigUiState) {
        selectedGameId = state.selectedGameId
        targetPathText = state.targetDataPath
        selectedTreeUriText = state.targetTreeUriText
        rootTargetPathText = state.targetRootPath
        selectedRootTreeUriText = state.targetRootTreeUriText
        realDeployEnabledState = state.realDeployEnabled
    }
}