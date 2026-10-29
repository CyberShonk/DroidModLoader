package com.shonkware.droidmodloader

import android.app.Application
import android.content.Context
import android.os.Handler
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.ui.DashboardActions
import com.shonkware.droidmodloader.ui.InstallerDialogAction
import com.shonkware.droidmodloader.ui.InstallerDialogState
import com.shonkware.droidmodloader.ui.InstallerPage
import com.shonkware.droidmodloader.engine.install.ArchiveInstallConfirmation
import com.shonkware.droidmodloader.engine.install.ExistingModAction
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import com.shonkware.droidmodloader.engine.install.requireValidModName
import com.shonkware.droidmodloader.ui.workflow.editManualInstallerChoices
import android.os.Bundle
import android.util.Log
import com.shonkware.droidmodloader.attention.AppAttentionId
import com.shonkware.droidmodloader.attention.AppAttentionIds
import com.shonkware.droidmodloader.attention.AppAttentionKind
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.deploy.GameInstallationResolver
import com.shonkware.droidmodloader.ui.MainActivityUiState
import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import com.shonkware.droidmodloader.ui.updateInstallRecoveryAttention
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.profile.ProfileRepositoryFactory
import java.io.File
import android.os.Looper
import com.shonkware.droidmodloader.ui.workflow.OperationReporter
import com.shonkware.droidmodloader.ui.workflow.OperationAttempt
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryRepository
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationKind
import com.shonkware.droidmodloader.ui.OperationHistoryPresentationMapper
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
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerEngine
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
import com.shonkware.droidmodloader.engine.download.LegacyArchiveCleanup
import com.shonkware.droidmodloader.ui.workflow.ArchiveBrowserHistory
import com.shonkware.droidmodloader.ui.workflow.ArchiveBrowserWorkflow
import com.shonkware.droidmodloader.ui.workflow.GameConfigurationInput
import com.shonkware.droidmodloader.ui.workflow.FolderSelectionScope
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
import com.shonkware.droidmodloader.ui.workflow.EnvironmentDiagnostics
import com.shonkware.droidmodloader.ui.workflow.SupportReportCoordinator
import com.shonkware.droidmodloader.ui.workflow.SupportDiagnostics
import com.shonkware.droidmodloader.ui.workflow.SupportStorageObservation
import com.shonkware.droidmodloader.ui.workflow.SupportReportEngineSnapshot
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
import com.shonkware.droidmodloader.ui.workflow.ArchiveImportEngineAdapter
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthProjector
import com.shonkware.droidmodloader.engine.deploy.health.ScopedIntegrityObservation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Retains the existing UI/workflow graph, never an Activity or native window. */
internal class SessionViewModel @JvmOverloads constructor(
    application: Application,
    // Optional application-independent engine boundary for controlled lifecycle tests.
    private val pendingInstallerEngineFactory: (() -> PendingInstallerEngine?)? = null
) : AndroidViewModel(application),
    MainActivityUiState by MutableMainActivityUiState() {

    companion object {
        private const val TAG = "DroidModLoader"
        private const val STATE_DISMISSED_ATTENTION_IDS =
            "dml.dismissed_attention_ids"
        private const val STATE_SUPPRESSED_ATTENTION_PROMPT_IDS =
            "dml.suppressed_attention_prompt_ids"
    }
    private val applicationContext: Context = application
    private val filesDir = application.filesDir
    private val packageManager = application.packageManager
    private val packageName = application.packageName
    private val mainHandler = Handler(Looper.getMainLooper())

    var settingsRequested by mutableStateOf(false)
        private set
    var pendingDeleteMod by mutableStateOf<Mod?>(null)
        private set
    private var pendingDeleteContext: Pair<String?, GameConfigurationInput>? = null
    var pendingSupportReportFile by mutableStateOf<File?>(null)
        private set
    private var pendingSupportReportContext: Pair<String?, GameConfigurationInput>? = null

    private fun getExternalFilesDir(type: String?) = applicationContext.getExternalFilesDir(type)
    private fun getSharedPreferences(name: String, mode: Int) = applicationContext.getSharedPreferences(name, mode)

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
    private val allFilesAccessManager by lazy {
        AllFilesAccessManager(applicationContext)
    }
    private val directPathValidator by lazy { DirectPathValidator() }
    private val gameInstallationResolver by lazy { GameInstallationResolver() }
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
                    FolderPickMode.FirstSetupGameFolder -> setupRootTargetPathText
                    FolderPickMode.ActiveGameFolder -> rootTargetPathText
                    FolderPickMode.NewProfileGameFolder -> newProfileRootPathText
                    FolderPickMode.FirstSetupDataFolder -> setupTargetPathText
                    FolderPickMode.ActiveDataFolder -> targetPathText
                    FolderPickMode.ActiveGameRootFolder -> rootTargetPathText
                    FolderPickMode.NewProfileDataFolder -> newProfileDataPathText
                    FolderPickMode.ArchiveLibraryFolder -> activeProfileId
                        ?.let(archiveFolderPreferences::getSelectedFolderPath)
                        .orEmpty()
                }
            },
            requestAllFilesAccess = { settingsRequested = true },
            handlePickedFolder = { mode, path ->
                folderPickerWorkflowController.handlePickedFolder(mode, path)
            },
            gameInstallationResolver = gameInstallationResolver,
            selectedGameIdProvider = { mode ->
                when (mode) {
                    FolderPickMode.FirstSetupGameFolder -> setupGameId
                    FolderPickMode.ActiveGameFolder -> selectedGameId
                    FolderPickMode.NewProfileGameFolder -> newProfileGameId
                    else -> null
                }
            },
            handlePickedGameInstallation = { mode, installation ->
                folderPickerWorkflowController.handlePickedGameInstallation(mode, installation)
            },
            selectionScopeProvider = { mode ->
                FolderSelectionScope(
                    activeProfileId,
                    when (mode) {
                        FolderPickMode.FirstSetupGameFolder, FolderPickMode.FirstSetupDataFolder -> setupGameId
                        FolderPickMode.NewProfileGameFolder, FolderPickMode.NewProfileDataFolder -> newProfileGameId
                        else -> selectedGameId
                    }
                )
            }
        )
    }
    private val activityThreadRunner by lazy {
        ActivityThreadRunner(
            isOnUiThread = { Looper.myLooper() == Looper.getMainLooper() },
            postToUiThread = { action -> runOnUiThread(action) }
        )
    }
    val isOperationAdmitted: Boolean
        get() = activityThreadRunner.isOperationAdmitted

    private val admittedOperationContext = ThreadLocal<OperationContext>()
    private var pendingInstallerOperation: OperationAttempt? = null
    private var manualChoicesCancellation: InstallCancellationController? = null

    private fun handleInstallerAction(action: InstallerDialogAction) {
        if (isOperationAdmitted || operationInProgress) return
        val prepared = pendingArchiveInstall ?: return
        val before = installerChoices
        when (action) {
            is InstallerDialogAction.Name -> installerChoices = before.copy(
                modName = action.value, error = null, showExisting = false, existingAction = ExistingModAction.ASK)
            InstallerDialogAction.Back -> installerChoices = before.copy(page = InstallerPage.NAME, error = null)
            is InstallerDialogAction.SelectFolder -> installerChoices = before.copy(selectedFolder = action.path)
            InstallerDialogAction.DismissConfirmation -> installerChoices = before.copy(
                showExisting = false, showUnrecognized = false,
                allowUnrecognized = false, existingAction = ExistingModAction.ASK)
            InstallerDialogAction.InstallAnyway -> {
                installerChoices = before.copy(showUnrecognized = false, allowUnrecognized = true)
                requestInstallerConfirmation()
            }
            is InstallerDialogAction.Existing -> {
                installerChoices = before.copy(showExisting = false, existingAction = action.action)
                requestInstallerConfirmation()
            }
            InstallerDialogAction.Install -> {
                if (before.page == InstallerPage.NAME && before.proposal == null &&
                    prepared.plan.installerType == InstallerType.MANUAL_DATA_FOLDER) {
                    handleInstallerAction(InstallerDialogAction.ChooseFiles)
                } else if (before.page == InstallerPage.NAME && before.proposal == null && prepared.plan.requiresUserChoice) {
                    installerChoices = before.copy(page = InstallerPage.OPTIONS)
                } else requestInstallerConfirmation()
            }
            else -> {
                val selectedIds = pendingInstallerSelectedOptionIds.toSet()
                val cancellation = InstallCancellationController()
                val busy = before.copy(busy = true)
                installerChoices = busy
                manualChoicesCancellation = cancellation
                val accepted = runSessionOperation(refreshArchives = false) {
                    try {
                        val result = runCatching { editManualInstallerChoices(prepared, before, selectedIds, action, cancellation.signal) }
                        if (result.exceptionOrNull() is InstallCancelledException) {
                            pendingInstallerWorkflow.cancelPendingInstall()
                        } else activityThreadRunner.runOnUiThreadBlocking {
                            if (pendingArchiveInstall === prepared && installerChoices === busy) {
                                result.fold(onSuccess = { (updated, choices) ->
                                    pendingArchiveInstall = updated
                                    installerChoices = choices
                                }, onFailure = { failure ->
                                    installerChoices = before.copy(error = failure.message ?: "Could not read prepared files.")
                                })
                            }
                        }
                    } finally {
                        activityThreadRunner.runOnUiThreadBlocking { manualChoicesCancellation = null }
                    }
                }
                if (!accepted) {
                    installerChoices = before
                    manualChoicesCancellation = null
                }
            }
        }
    }

    private fun requestInstallerConfirmation() {
        val choices = installerChoices
        try {
            requireValidModName(choices.modName)
        } catch (failure: Exception) {
            installerChoices = choices.copy(error = failure.message)
            return
        }
        if (choices.layoutError != null) return
        if (choices.proposal != null && !choices.recognized && !choices.allowUnrecognized) {
            installerChoices = choices.copy(showUnrecognized = true)
            return
        }
        installerWorkflowController.finalizeInstall()
    }

    private fun runSessionOperation(
        requireNoPendingInstaller: Boolean = false,
        refreshArchives: Boolean = true,
        preserveHealthEvidence: Boolean = false,
        prepare: () -> Boolean = { true },
        task: () -> Unit
    ): Boolean {
        var context: OperationContext? = null
        val accepted = activityThreadRunner.runExclusiveInBackground(
            beforeSchedule = {
                val allowed = !requireNoPendingInstaller || captureOnUiThread { pendingArchiveInstall == null }
                if (allowed && prepare()) {
                    captureOnUiThread {
                        context = captureOperationContext()
                        if (!preserveHealthEvidence) {
                            integrityObservation.set(null)
                            deploymentHealth = deploymentHealth?.let(DeploymentHealthProjector::invalidate)
                        }
                        pendingSupportReportFile = null
                        pendingSupportReportContext = null
                    }
                    true
                } else {
                    false
                }
            },
            onFinished = {
                if (refreshArchives) runOnUiThread { archiveBrowserWorkflow.refreshIfOpen() }
            }
        ) {
            admittedOperationContext.set(checkNotNull(context))
            try {
                loadAndReportOperationHistory()
                task()
            } catch (failure: Throwable) {
                operationReporter.failOperation("Operation failed: ${failure.message}", failure)
            } finally {
                admittedOperationContext.remove()
                // Publish queued state (including pending choices) before another request can own it.
                activityThreadRunner.runOnUiThreadBlocking {}
            }
        }
        if (!accepted) {
            operationReporter.appendLog("Ignoring request: session operation was not admitted.")
        }
        return accepted
    }

    private fun captureOperationContext() = OperationContext(
        activeProfileId, selectedGameId, realDeployEnabledState, targetPathText, rootTargetPathText
    )

    private fun <T> captureOnUiThread(capture: () -> T): T {
        var result: Result<T>? = null
        activityThreadRunner.runOnUiThreadBlocking { result = runCatching(capture) }
        return checkNotNull(result).getOrThrow()
    }

    private val sessionLogWriter by lazy {
        SessionLogWriter { getExternalFilesDir(null) }
    }
    private val operationHistory by lazy { OperationHistoryRepository(File(filesDir, "operation-history.json")) }
    private var historyReported = false

    private fun loadAndReportOperationHistory() {
        if (historyReported) return
        historyReported = true
        val history = operationHistory.load()
        val presentation = OperationHistoryPresentationMapper.present(history)
        runOnUiThread { operationHistoryPresentation = presentation }
        if (history.availability != OperationHistoryAvailability.AVAILABLE) {
            operationReporter.appendLog(checkNotNull(history.reason))
            return
        }
        operationReporter.appendLog("Recent operation history: ${history.records.size} recorded attempts.")
        history.records.takeLast(3).reversed().forEach { record ->
            val outcome = if (record.interrupted) "Interrupted; outcome unknown" else record.outcome?.name ?: "Outcome unknown"
            val context = if (record.context.unavailable) "context unavailable" else
                "profile=${record.context.profileId ?: "unassigned"}, game=${record.context.gameId}"
            operationReporter.appendLog(
                "RECENT OPERATION [${record.id}]: $outcome; last phase=${record.phase}; $context; " +
                    "updated=${record.updatedAtMillis}; ${record.reason}"
            )
        }
    }

    private val operationReporter: OperationReporter by lazy {
        OperationReporter(
            runOnUiThread = { action -> runOnUiThread(action) },
            currentLogText = { logText },
            updateLogText = { logText = it },
            updateOperationInProgress = { operationInProgress = it },
            updateActiveOperationText = { activeOperationText = it },
            updateLastOperationStatus = { lastOperationStatus = it },
            debugLog = { line -> Log.d(TAG, line) },
            errorLog = { line, throwable ->
                if (throwable == null) {
                    Log.e(TAG, line)
                } else {
                    Log.e(TAG, line, throwable)
                }
            },
            appendLogFile = { line -> sessionLogWriter.append(line) },
            recordAttempt = { record ->
                val before = operationHistory.snapshot
                val after = operationHistory.record(record)
                val presentation = OperationHistoryPresentationMapper.present(after)
                runOnUiThread { operationHistoryPresentation = presentation }
                if (after.availability != OperationHistoryAvailability.AVAILABLE &&
                    (before.availability != after.availability || before.reason != after.reason)) {
                    operationReporter.appendLog(checkNotNull(after.reason))
                }
            }
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
    private fun appDiagnosticInfo(): AppDiagnosticInfo {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return AppDiagnosticInfo(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            packageName = packageName,
            androidVersion = android.os.Build.VERSION.RELEASE,
            deviceModel = android.os.Build.MODEL,
            sdkInt = android.os.Build.VERSION.SDK_INT
        )
    }

    @Volatile private var supportEnvironmentObservation: EnvironmentDiagnostics? = null
    @Volatile private var supportStorageObservation: SupportStorageObservation? = null

    private val supportReportCoordinator by lazy {
        SupportReportCoordinator(
            engineProvider = {
                captureOnUiThread {
                    if (activeProfileId == null) null else SupportReportEngineSnapshot(visibleMods, visiblePlugins)
                }
            },
            diagnosticInfoProvider = ::appDiagnosticInfo,
            lastOperationStatusProvider = { lastOperationStatus },
            developerModeEnabledProvider = { developerModeEnabled },
            currentLogTextProvider = { logText },
            sessionLogWriter = sessionLogWriter,
            deploymentHealthProvider = { captureOnUiThread { currentDeploymentHealth() } },
            diagnosticsProvider = {
                val provenance = runCatching {
                    applicationContext.assets.open("dml-build-provenance.json").bufferedReader().use {
                        SupportDiagnostics.readBuildProvenance(it.readText())
                    }
                }.getOrDefault(emptyMap())
                captureOnUiThread {
                    SupportDiagnostics(
                        context = captureOperationContext(), profileName = activeProfileName,
                        history = operationHistory.snapshot, environment = supportEnvironmentObservation,
                        storage = supportStorageObservation, archive = archiveBrowserState.inspection,
                        allFilesAccess = allFilesAccessManager.state(), buildProvenance = provenance
                    )
                }
            }
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
            beginOperation = { text ->
                operationReporter.beginRecordedOperation(OperationKind.PLUGIN_OUTPUT, checkNotNull(admittedOperationContext.get()), text)
            },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            selectedGameIdProvider = { selectedGameId },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }

    private val pluginActionWorkflowController by lazy {
        PluginActionWorkflowController(
            runInBackground = { task -> runSessionOperation(task = task) },
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
    private val pendingInstallerWorkflowOwner: Lazy<PendingInstallerWorkflow> = lazy {
        PendingInstallerWorkflow(
            pendingSessionProvider = {
                pendingArchiveInstall?.let { prepared ->
                    PendingInstallerSession(
                        prepared = prepared.copy(confirmation = ArchiveInstallConfirmation(
                            modName = installerChoices.modName.ifBlank { prepared.modName },
                            manualLayout = installerChoices.proposal,
                            allowUnrecognizedLayout = installerChoices.allowUnrecognized,
                            existingModAction = installerChoices.existingAction
                        )),
                        archiveRecordId = pendingInstallerArchiveRecordId,
                        selectedOptionIds = pendingInstallerSelectedOptionIds,
                        operation = pendingInstallerOperation
                    )
                }
            },
            isOperationInProgress = { operationInProgress },
            createEngine = pendingInstallerEngineFactory ?: {
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
            beginOperation = { previous, message ->
                operationReporter.continueArchiveInstall(previous, checkNotNull(admittedOperationContext.get()), message)
            },
            updatePendingOperation = { operation ->
                activityThreadRunner.runOnUiThreadBlocking { pendingInstallerOperation = operation }
            },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            updateSelectedOptionIds = { selectedOptionIds ->
                pendingInstallerSelectedOptionIds = selectedOptionIds
            },
            clearPendingInstallerState = {
                runOnUiThread {
                    pendingArchiveInstall = null
                    pendingInstallerOperation = null
                    pendingInstallerArchiveRecordId = null
                    pendingInstallerSelectedOptionIds = emptySet()
                    installerChoices = InstallerDialogState()
                    showInstallerDialog = false
                    installerDialogFullscreen = false
                }
            },
            refreshDashboard = {
                dashboardRefreshCoordinator.refresh()
                archiveBrowserWorkflow.refreshIfOpen()
            },
            onExistingInstall = {
                runOnUiThread { installerChoices = installerChoices.copy(showExisting = true) }
            },
            onFinalizationFailure = { message ->
                runOnUiThread { installerChoices = installerChoices.copy(error = message) }
            }
        )
    }
    private val pendingInstallerWorkflow by pendingInstallerWorkflowOwner

    private val installerWorkflowController by lazy {
        InstallerWorkflowController(
            finalizeInstallerInstall = {
                var finalization: PendingInstallerWorkflow.Finalization? = null
                try {
                    runSessionOperation(
                        prepare = {
                            if (pendingInstallerOperation?.snapshot()?.context?.let { it != captureOperationContext() } == true) {
                                installerChoices = installerChoices.copy(error = "The profile or game target changed. Cancel and install again.")
                                false
                            } else {
                                finalization = pendingInstallerWorkflow.prepareFinalization()
                                finalization != null
                            }
                        }
                    ) { checkNotNull(finalization).run() }
                } catch (failure: Throwable) {
                    finalization?.abandon()
                    throw failure
                }
            },
            cancelInstallerInstall = {
                val editing = manualChoicesCancellation
                if (editing != null) {
                    editing.cancel()
                    lastOperationStatus = "Cancelling installer..."
                } else if (!pendingInstallerWorkflow.cancelActiveInstall()) {
                    runSessionOperation { pendingInstallerWorkflow.cancelPendingInstall() }
                }
            },
            toggleInstallerOption = { optionId ->
                if (!isOperationAdmitted && !operationInProgress) pendingInstallerWorkflow.toggleOption(optionId)
            },
            handleInstallerAction = ::handleInstallerAction
        )
    }
    private val profileWorkflowController by lazy {
        ProfileWorkflowController(
            runInBackground = { task -> runSessionOperation(requireNoPendingInstaller = true, task = task) },
            prepareFirstSetup = {
                captureFirstSetupInput().let { input -> { profileManagementWorkflow.completeFirstSetup(input) } }
            },
            prepareAdditionalProfile = {
                captureAdditionalProfileInput().let { input -> { profileManagementWorkflow.createAdditionalProfile(input) } }
            },
            switchActiveProfile = { profileId ->
                profileManagementWorkflow.switchActiveProfile(profileId)
            },
            deleteProfileAction = { profileId -> profileManagementWorkflow.deleteProfile(profileId) },
            saveDashboardSettings = { profileManagementWorkflow.saveDashboardSettings() }
        )
    }
    private fun captureFirstSetupInput(): FirstSetupInput = captureOnUiThread {
        FirstSetupInput(
            profileNameText = profileNameText,
            gameId = setupGameId,
            targetDataPath = setupTargetPathText,
            realDeployEnabled = DeploymentConfigUiMapper.isGameInstallationResolved(
                setupRootTargetPathText, setupTargetPathText
            ),
            targetRootPath = setupRootTargetPathText
        )
    }

    private fun captureAdditionalProfileInput(): AdditionalProfileInput = captureOnUiThread {
        AdditionalProfileInput(
            profileNameText = newProfileNameText,
            gameId = newProfileGameId,
            targetDataPath = newProfileDataPathText,
            realDeployEnabled = DeploymentConfigUiMapper.isGameInstallationResolved(
                newProfileRootPathText, newProfileDataPathText
            ),
            targetRootPath = newProfileRootPathText
        )
    }

    private val profileManagementWorkflow by lazy {
        ProfileManagementWorkflow(
            repositoryProvider = { profileRepositoryFactory.create() },
            gameDisplayNameProvider = GameCatalog::displayName,
            firstSetupInputProvider = ::captureFirstSetupInput,
            additionalProfileInputProvider = ::captureAdditionalProfileInput,
            activeProfileIdProvider = { activeProfileId },
            dashboardProfileInputProvider = { captureOnUiThread {
                DashboardProfileInput(
                    targetPathText = targetPathText,
                    rootTargetPathText = rootTargetPathText,
                    realDeployEnabled = realDeployEnabledState,
                    dataPathReselectionRequired = dataPathReselectionRequired,
                    rootPathReselectionRequired = rootPathReselectionRequired
                )
            } },
            applyFirstSetupUiState = { profiles, profile ->
                activityThreadRunner.runOnUiThreadBlocking {
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
                    visibleModFlags = emptyMap()

                    newProfileNameText = ""
                    newProfileDataPathText = ""
                    newProfileRootPathText = ""
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
                    visibleModFlags = emptyMap()
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
                    visibleModFlags = emptyMap()
                }
            },
            saveSelectedGameConfigFromUi = { profileSessionCoordinator.saveSelectedGameConfigFromUi() },
            loadSelectedGameConfigIntoUi = { profileSessionCoordinator.loadSelectedGameConfigIntoUi() },
            recoverActiveProfile = {
                createInstallReplacementRecoveryEngine()?.let { recoveryEngine ->
                    installReplacementStartupWorkflow.checkStartup(
                        recoveryEngine,
                        profileId = recoveryEngine.profileId,
                        gameId = recoveryEngine.gameId
                    )
                }
            },
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
            runInBackground = { task -> runSessionOperation(task = task) },
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
            runInBackground = { task -> runSessionOperation(requireNoPendingInstaller = true, task = task) },
            handleImportedArchive = { uri -> archiveImportExecutionWorkflow.importArchive(uri) },
            showArchiveLibrarySummary = { developerDiagnosticsCoordinator.buildArchiveLibrarySummary() }
        )
    }
    private val folderPickerWorkflowController by lazy {
        FolderPickerWorkflowController(
            runInBackground = { task -> runSessionOperation(requireNoPendingInstaller = true, task = task) },
            saveFirstSetupDataPath = { path ->
                runOnUiThread {
                    setupTargetPathText = path
                    selectedDataPathText = path
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
            appendLog = { message -> operationReporter.appendLog(message) },
            saveFirstSetupGameInstallation = { installation ->
                runOnUiThread {
                    if (setupGameId == installation.gameId) {
                        setupRootTargetPathText = installation.gameRootPath
                        setupTargetPathText = installation.dataPath
                    }
                }
            },
            saveActiveGameInstallation = selectedFolderConfigurationCoordinator::saveGameInstallation,
            setNewProfileGameInstallation = { installation ->
                runOnUiThread {
                    if (newProfileGameId == installation.gameId) {
                        newProfileRootPathText = installation.gameRootPath
                        newProfileDataPathText = installation.dataPath
                    }
                }
            }
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
            beginOperation = { kind, message ->
                operationReporter.beginRecordedOperation(kind, checkNotNull(admittedOperationContext.get()), message)
            },
            appendLog = { message -> operationReporter.appendLog(message) },
            appendError = { message, throwable -> operationReporter.appendError(message, throwable) },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val deploymentActionWorkflowController by lazy {
        DeploymentActionWorkflowController(
            runInBackground = { task -> runSessionOperation(task = task) },
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
            updateRecoveryState = { warning, attentionRequired, showDetails ->
                runOnUiThread {
                    if (!attentionRequired) {
                        clearAttentionSessionState(AppAttentionKind.DEPLOY_RECOVERY)
                    }
                    deployRecoveryWarningText = warning
                    deployRecoveryAttentionRequired = attentionRequired
                    deployRecoveryAttentionProfileId =
                        activeProfileId.takeIf { attentionRequired }
                    deployRecoveryAttentionGameId =
                        selectedGameId.takeIf { attentionRequired }
                    showDeployRecoveryDialog = showDetails
                }
            },
            updateLastOperationStatus = { status -> lastOperationStatus = status },
            refreshDashboard = dashboardRefreshCoordinator::refresh
        )
    }
    private val deployRecoveryWorkflowController by lazy {
        DeployRecoveryWorkflowController(
            runInBackground = { task -> runSessionOperation(task = task) },
            showRecoveryDetails = {
                showDeployRecoveryDialog = true
            },
            hideRecoveryDetails = {
                showDeployRecoveryDialog = false
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
            runInBackground = { task -> runSessionOperation(task = task) },
            buildResolvedDataGraph = {
                developerDiagnosticsCoordinator.buildResolvedDataGraphSummary()
            },
            showArchiveLibrarySummary = {
                developerDiagnosticsCoordinator.buildArchiveLibrarySummary()
            }
        )
    }
    private val overwriteActionWorkflowController by lazy {
        OverwriteActionWorkflowController(
            runInBackground = { task -> runSessionOperation(task = task) },
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

    private fun captureProfileContext(): Pair<String?, GameConfigurationInput> = captureOnUiThread {
        activeProfileId to GameConfigurationInput(
            selectedGameId = selectedGameId,
            targetDataPath = targetPathText,
            realDeployEnabled = realDeployEnabledState,
            targetRootPath = rootTargetPathText,
            dataPathReselectionRequired = dataPathReselectionRequired,
            rootPathReselectionRequired = rootPathReselectionRequired
        )
    }

    private val profileScopedEngineFactory: ProfileScopedEngineFactory
        get() {
            val (profileId, configuration) = captureProfileContext()
            val gameId = configuration.selectedGameId
            return ProfileScopedEngineFactory(
                appContext = applicationContext,
                externalFilesDirProvider = { getExternalFilesDir(null) },
                profileStoragePaths = ProfileStoragePaths(
                    filesDir = filesDir,
                    activeProfileIdProvider = { profileId },
                    selectedGameIdProvider = { gameId }
                ),
                selectedGameIdProvider = { gameId },
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
            profileScopedEngineFactoryProvider = { profileScopedEngineFactory },
            configurationInputProvider = { captureProfileContext().second },
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
            appendLog = operationReporter::appendLog,
            onGameInstallationResolved = {
                runOnUiThread {
                    clearAttentionSessionState(
                        AppAttentionKind.GAME_INSTALLATION_RESELECTION
                    )
                }
            }
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

    private val archiveImportExecutionWorkflowOwner: Lazy<ArchiveImportExecutionWorkflow> = lazy {
        ArchiveImportExecutionWorkflow(
            operationInProgressProvider = {
                operationInProgress
            },
            beginOperation = { message ->
                operationReporter.beginArchiveInstall(checkNotNull(admittedOperationContext.get()), message)
            },
            createEngine = {
                profileScopedEngineFactory
                    .create()
                    ?.let { engine ->
                        ArchiveImportEngineAdapter(
                            engine = engine,
                            syncPluginsFromCurrentState = {
                                syncPluginsFromCurrentState(
                                    engine
                                )
                            },
                            appendRoutingSummary = { mod ->
                                developerDiagnosticsCoordinator
                                    .appendInstalledModRoutingSummary(
                                        DeveloperDiagnosticsEngineAdapter(
                                            engine
                                        ),
                                        mod
                                    )
                            }
                        )
                    }
            },
            archiveImportFileStore =
                archiveImportFileStore,
            showInstallerChoices = {
                    prepared,
                    archiveRecordId,
                    operation ->
                runOnUiThread {
                    pendingArchiveInstall = prepared
                    installerChoices = InstallerDialogState(modName = prepared.modName)
                    pendingInstallerOperation = operation
                    pendingInstallerArchiveRecordId =
                        archiveRecordId
                    pendingInstallerSelectedOptionIds =
                        prepared.plan
                            .defaultSelectedOptionIds
                    showInstallerDialog = true
                    installerDialogFullscreen = false
                }
            },
            appendLog = { message ->
                operationReporter.appendLog(message)
            },
            appendError = {
                    message,
                    throwable ->
                operationReporter.appendError(
                    message,
                    throwable
                )
            },
            updateLastOperationStatus = { status ->
                runOnUiThread {
                    lastOperationStatus = status
                }
            },
            updateArchiveImportInProgress = { active ->
                runOnUiThread {
                    archiveImportInProgress = active
                }
            },
            refreshDashboard = {
                dashboardRefreshCoordinator.refresh()
                archiveBrowserWorkflow.refreshIfOpen()
            },
            inspectionPublisher = { sourcePath ->
                val context = admittedOperationContext.get()
                archiveBrowserWorkflow.captureInspectionPublisher(
                    context?.profileId, sourcePath,
                    isContextCurrent = { context != null && context == captureOperationContext() }
                )
            }
        )
    }

    private val archiveImportExecutionWorkflow by archiveImportExecutionWorkflowOwner

    private val archiveFolderPreferences by lazy {
        ArchiveFolderPreferences(
            getSharedPreferences(
                ArchiveFolderPreferences.PREFERENCES_NAME,
                Context.MODE_PRIVATE
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
            runInBackground = { task -> runSessionOperation(refreshArchives = false, task = task) },
            isOperationInProgress = { activityThreadRunner.isOperationAdmitted },
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
            appendLog = { message -> operationReporter.appendLog(message) },
            postToUiThread = { action -> runOnUiThread(action) }
        )
    }

    val secondScreenPluginCoordinator by lazy {
        SecondScreenPluginCoordinator(
            appendLog = { message -> operationReporter.appendLog(message) },
            updateLastOperationStatus = { status -> lastOperationStatus = status }
        )
    }

    private val dashboardRefreshCoordinator by lazy {
        DashboardRefreshCoordinator(
            state = this,
            buildResult = {
                val (profileId, gameId, engine) = captureOnUiThread {
                    Triple(activeProfileId.orEmpty(), selectedGameId, profileScopedEngineFactory.create(prepareDirectories = false))
                }
                engine?.let {
                    dashboardRefreshWorkflow.build(
                        engine = DashboardRefreshEngineAdapter(it), selectedGameId = gameId,
                        profileId = profileId, observation = integrityObservation.get()
                    )
                }
            },
            runOnUiThread = { action -> runOnUiThread(action) },
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

    private fun createInstallReplacementRecoveryEngine(): InstallReplacementRecoveryEngineAdapter? {
        lateinit var createEngine: () -> InstallReplacementRecoveryEngineAdapter?
        activityThreadRunner.runOnUiThreadBlocking {
            val profileId = activeProfileId
            val gameId = selectedGameId
            val factory = ProfileScopedEngineFactory(
                appContext = applicationContext,
                externalFilesDirProvider = { getExternalFilesDir(null) },
                profileStoragePaths = ProfileStoragePaths(
                    filesDir = filesDir,
                    activeProfileIdProvider = { profileId },
                    selectedGameIdProvider = { gameId }
                ),
                selectedGameIdProvider = { gameId },
                appendError = { message -> operationReporter.appendError(message) }
            )
            createEngine = {
                factory.create()?.let { engine ->
                    InstallReplacementRecoveryEngineAdapter(engine, profileId, gameId)
                }
            }
        }
        // Keep filesystem work off the UI thread, using the captured profile/game throughout.
        return createEngine()
    }

    private val installReplacementStartupWorkflow by lazy {
        InstallReplacementStartupWorkflow(
            appendLog = operationReporter::appendLog,
            appendError = { message ->
                operationReporter.appendError(message)
            },
            onInstallRecoveryAttention = { profileId, gameId, requiresAttention ->
                updateInstallRecoveryAttention(profileId, gameId, requiresAttention)
            },
            postToUiThread = { action -> runOnUiThread(action) }
        )
    }

    private val appStartupCoordinator by lazy {
        AppStartupCoordinator(
            runInBackground = { task -> runSessionOperation(task = task) },
            cleanTemporaryArchives = {
                try {
                    archiveImportFileStore.cleanAbandonedSnapshots()
                } catch (exception: Exception) {
                    operationReporter.appendError("Temporary archive cleanup failed: ${exception.message}", exception)
                }
            },
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
            createRuntime = ::createInstallReplacementRecoveryEngine,
            checkRecovery = { recoveryEngine ->
                installReplacementStartupWorkflow.checkStartup(
                    recoveryEngine,
                    profileId = recoveryEngine.profileId,
                    gameId = recoveryEngine.gameId
                )

                deployRecoveryWorkflow.checkStartup(
                    DeployRecoveryEngineAdapter(recoveryEngine.engine)
                )
            },
            synchronizePluginsAndRefresh = { recoveryEngine ->
                pluginSyncWorkflowController.syncWithExistingEngineThenRefresh(recoveryEngine?.engine)
            },
            appendLog = operationReporter::appendLog
        )
    }

    // These platform callbacks live only in the Activity's UI bindings, not in
    // this retained graph. Never cache the returned actions here.
    fun buildActions(shareLogs: () -> Unit, requestAllFilesAccess: () -> Unit): DashboardActions =
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
            selectGame = { gameId ->
                runSessionOperation(requireNoPendingInstaller = true) {
                    activityThreadRunner.runOnUiThreadBlocking { selectedGameId = gameId }
                    profileSessionCoordinator.loadSelectedGameConfigIntoUi()
                    profileContentInspectionCoordinator.ensureDataBaselineIfMissing("selected game changed")
                    pluginSyncWorkflowController.syncWithNewEngineThenRefresh()
                }
            },
            appendLog = operationReporter::appendLog,
            archiveImportExecutionWorkflow =
                archiveImportExecutionWorkflow,
            cleanOldArchiveCopies = ::cleanOldArchiveCopies,
            verifyManagedContent = ::verifyManagedContent,
            refreshDeploymentHealth = ::refreshDeploymentHealth,
            inspectStorage = ::inspectStorage,
            inspectEnvironment = ::inspectEnvironment,
            cancelIntegrityCheck = { integrityCancellation.get()?.set(true) },
        ).build(shareLogs = shareLogs, requestAllFilesAccess = requestAllFilesAccess)

    private val integrityCancellation = AtomicReference<AtomicBoolean?>(null)
    private val integrityObservation = AtomicReference<ScopedIntegrityObservation?>(null)

    private fun refreshDeploymentHealth() {
        runSessionOperation(refreshArchives = false, preserveHealthEvidence = true) {
            captureOnUiThread { deploymentHealth = deploymentHealth?.let(DeploymentHealthProjector::invalidate) }
            operationReporter.beginOperation("Refreshing deployment summary...")
            check(dashboardRefreshCoordinator.refresh()) { "Could not collect deployment evidence." }
            operationReporter.finishOperation("Deployment summary refreshed. Choose Show Deployment Details to review it.")
        }
    }

    private fun inspectEnvironment() {
        runSessionOperation(refreshArchives = false, preserveHealthEvidence = true) {
            val context = checkNotNull(admittedOperationContext.get())
            val permission = allFilesAccessManager.state()
            supportEnvironmentObservation = developerDiagnosticsCoordinator.inspectEnvironment(
                collect = { EnvironmentDiagnostics.inspect(context, appDiagnosticInfo(), permission) },
                contextStillCurrent = {
                    captureOnUiThread { context == captureOperationContext() } &&
                        permission == allFilesAccessManager.state()
                }
            )
        }
    }

    private fun inspectStorage() {
        runSessionOperation(refreshArchives = false, preserveHealthEvidence = true) {
            val context = checkNotNull(admittedOperationContext.get())
            val factory = ProfileScopedEngineFactory(applicationContext, { getExternalFilesDir(null) },
                ProfileStoragePaths(filesDir, { context.profileId }, { context.gameId }), { context.gameId },
                { message -> operationReporter.appendError(message) })
            val engine = checkNotNull(factory.create(prepareDirectories = false)) { "Storage engine is unavailable." }
            val observedAtMillis = System.currentTimeMillis()
            supportStorageObservation = developerDiagnosticsCoordinator.inspectStorage(DeveloperDiagnosticsEngineAdapter(engine), context) {
                captureOnUiThread { context == captureOperationContext() }
            }?.let { SupportStorageObservation(context, observedAtMillis, it) }
        }
    }

    private fun verifyManagedContent() {
        val cancellation = AtomicBoolean(false)
        try {
            runSessionOperation(
                requireNoPendingInstaller = true,
                refreshArchives = false,
                prepare = {
                    integrityCancellation.set(cancellation)
                    captureOnUiThread { integrityCheckInProgress = true }
                    true
                }
            ) {
                try {
                    val (profileId, gameId, engine) = captureOnUiThread {
                        Triple(activeProfileId.orEmpty(), selectedGameId,
                            checkNotNull(profileScopedEngineFactory.create(prepareDirectories = false)))
                    }
                    val context = captureOnUiThread { deploymentHealthContextKey() }
                    val journalRevision = engine.deploymentHealthJournalRevision(gameId)
                    val report = developerDiagnosticsCoordinator.verifyManagedContent(
                        DeveloperDiagnosticsEngineAdapter(engine), gameId, profileId,
                        cancelled = cancellation::get,
                        contextStillCurrent = {
                            captureOnUiThread { deploymentHealthContextKey() == context }
                        }
                    )
                    val observation = report?.let { ScopedIntegrityObservation(profileId, gameId, it, journalRevision) }
                    integrityObservation.set(observation)
                    if (report != null) {
                        operationReporter.appendLog("Choose Refresh Deployment Summary to include these file-check results. No saved file checks were rebuilt.")
                    }
                } finally {
                    integrityCancellation.compareAndSet(cancellation, null)
                    captureOnUiThread { integrityCheckInProgress = false }
                }
            }
        } catch (failure: Throwable) {
            if (integrityCancellation.compareAndSet(cancellation, null)) {
                captureOnUiThread { integrityCheckInProgress = false }
            }
            throw failure
        }
    }

    private fun cleanOldArchiveCopies() {
        runSessionOperation(requireNoPendingInstaller = true) {
            operationReporter.beginOperation("Verifying old DML archive copies...")
            check(allFilesAccessManager.isGranted()) { "All-files access is required to verify originals." }
            val base = checkNotNull(getExternalFilesDir(null)) { "External files directory is unavailable." }
            val result = LegacyArchiveCleanup(
                base,
                filesDir,
                selectedFolders = archiveFolderPreferences::selectedFoldersForCleanup,
                report = operationReporter::appendLog
            ).clean()
            operationReporter.finishOperation(
                "Old archive cleanup: ${result.removedFiles} removed (${result.removedBytes} bytes), " +
                    "${result.preservedFiles} preserved. See log for details."
            )
        }
    }

    private fun clearAttentionSessionState(kind: AppAttentionKind) {
        val profileId = activeProfileId ?: return
        val gameId = selectedGameId
        if (gameId.isBlank()) return

        val attentionId = AppAttentionIds.scoped(kind, profileId, gameId)
        dismissedAttentionIds = dismissedAttentionIds - attentionId
        suppressedAttentionPromptIds = suppressedAttentionPromptIds - attentionId
    }

    private fun restoreAttentionSessionState(savedInstanceState: Bundle?) {
        dismissedAttentionIds = savedInstanceState
            ?.getStringArrayList(STATE_DISMISSED_ATTENTION_IDS)
            ?.map(::AppAttentionId)
            ?.toSet()
            .orEmpty()
        suppressedAttentionPromptIds = savedInstanceState
            ?.getStringArrayList(STATE_SUPPRESSED_ATTENTION_PROMPT_IDS)
            ?.map(::AppAttentionId)
            ?.toSet()
            .orEmpty()
    }

    fun initialize(savedInstanceState: Bundle?) {
        refreshAccessState()
        appStartupCoordinator.initialize {
            restoreAttentionSessionState(savedInstanceState)
        }
    }

    fun saveAttentionState(outState: Bundle) {
        outState.putStringArrayList(
            STATE_DISMISSED_ATTENTION_IDS,
            ArrayList(dismissedAttentionIds.map { it.value })
        )
        outState.putStringArrayList(
            STATE_SUPPRESSED_ATTENTION_PROMPT_IDS,
            ArrayList(suppressedAttentionPromptIds.map { it.value })
        )
    }

    fun refreshAccessState() = directFolderSelectionCoordinator.refreshAccessState()

    fun onResume() {
        refreshAccessState()
        archiveBrowserWorkflow.refreshIfOpen()
    }

    fun dashboardState() = toDashboardUiState(
        secondScreenEnabled = secondScreenPluginCoordinator.enabled,
        allFilesAccessRequired = android.os.Build.VERSION.SDK_INT >= AllFilesAccessPolicy.ANDROID_11_API_LEVEL,
        directFolderState = com.shonkware.droidmodloader.ui.DirectFolderSelectionUiState(
            allFilesAccessGranted = directFolderSelectionCoordinator.allFilesAccessGranted,
            showBrowser = directFolderSelectionCoordinator.showBrowser,
            browserTitle = directFolderSelectionCoordinator.browserTitle,
            browserRequiresWritable = directFolderSelectionCoordinator.browserRequiresWritable,
            browserState = directFolderSelectionCoordinator.browserState
        )
    )

    private fun showDeleteConfirmDialog(mod: Mod) {
        runOnUiThread {
            pendingDeleteContext = captureProfileContext()
            pendingDeleteMod = mod
        }
    }

    fun cancelDelete() {
        pendingDeleteMod = null
        pendingDeleteContext = null
    }

    fun confirmDelete(mod: Mod) {
        if (pendingDeleteMod != mod) return
        if (pendingDeleteContext != captureProfileContext()) {
            cancelDelete()
            operationReporter.appendLog("Ignoring delete confirmation: profile, game or target changed.")
            return
        }
        if (runSessionOperation { modManagementWorkflow.deleteInstalledMod(mod.id) }) {
            cancelDelete()
        }
    }

    fun consumeSettingsRequest() { settingsRequested = false }
    fun requestSupportReport() {
        var context: Pair<String?, GameConfigurationInput>? = null
        runSessionOperation(
            refreshArchives = false,
            preserveHealthEvidence = true,
            prepare = {
                context = captureProfileContext()
                true
            }
        ) {
            val report = supportReportCoordinator.buildShareFile(applicationContext.cacheDir)
            val published = captureOnUiThread {
                if (context == captureProfileContext()) {
                    pendingSupportReportContext = context
                    pendingSupportReportFile = report
                    true
                } else {
                    false
                }
            }
            if (!published) report.delete()
        }
    }

    fun consumeSupportReport(): File? {
        val report = pendingSupportReportFile.takeIf { pendingSupportReportContext == captureProfileContext() }
        pendingSupportReportFile = null
        pendingSupportReportContext = null
        return report
    }
    fun logShareUnavailable(error: RuntimeException) {
        operationReporter.appendError("Could not open Share Logs chooser: ${error.message}", error)
        lastOperationStatus = "Could not open Share Logs. See session log for details."
    }

    fun logSettingsUnavailable(error: Throwable? = null) {
        if (error == null) operationReporter.appendLog("All-files access settings are unavailable on this device.")
        else operationReporter.appendError("All-files access settings are unavailable: ${error.message}", error)
    }

    override fun onCleared() {
        // Configuration recreation does not clear this owner. On final removal,
        // request cooperative cancellation; never interrupt filesystem promotion.
        integrityCancellation.get()?.set(true)
        if (archiveImportExecutionWorkflowOwner.isInitialized()) {
            archiveImportExecutionWorkflow.cancelActiveImport()
        }
        if (pendingInstallerWorkflowOwner.isInitialized()) {
            pendingInstallerWorkflow.cancelActiveInstall()
        }
        super.onCleared()
    }


    private fun syncPluginsFromCurrentState(engine: ModEngine) {
        pluginSynchronizationWorkflow.sync(
            engine = PluginSynchronizationEngineAdapter(engine),
            selectedGameId = selectedGameId
        )
    }


}