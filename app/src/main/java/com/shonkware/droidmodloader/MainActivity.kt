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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import android.os.Looper
import java.util.concurrent.CountDownLatch
import com.shonkware.droidmodloader.engine.install.InstallerGroupType
import com.shonkware.droidmodloader.engine.repair.V050ArtifactRepairTool

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }

    private enum class FolderPickMode {
        ActiveDataFolder,
        ActiveGameRootFolder,
        NewProfileDataFolder
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

    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            appendLog("No file selected.")
            return@registerForActivityResult
        }

        runInBackground {
            handleImportedArchive(uri)
        }
    }
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

            val uriString = uri.toString()

            runInBackground {
                when (folderPickMode) {
                    FolderPickMode.ActiveDataFolder -> {
                        savePickedDataFolderToSelectedGameConfig(uriString)
                    }

                    FolderPickMode.ActiveGameRootFolder -> {
                        savePickedRootFolderToSelectedGameConfig(uriString)
                    }

                    FolderPickMode.NewProfileDataFolder -> {
                        runOnUiThread {
                            newProfileTreeUriText = uriString
                        }
                        appendLog("Selected Data folder for new profile.")
                    }
                }
            }
        } catch (e: Exception) {
            appendError("Failed to persist folder permission: ${e.message}", e)
        }
    }
    private var activeOperationStartedAtMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
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
            versionLabel = "Version 0.5.2 Beta",
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

        )
    }

    private fun buildUiActions(): DashboardActions {
        return DashboardActions(
            onVersionTap = {
                developerTapCount++
                if (!developerModeEnabled && developerTapCount >= 7) {
                    developerModeEnabled = true
                    appendLog("Developer tools unlocked.")
                }
            },
            onImportArchive = {
                appendLog("Opening document picker...")
                importZipLauncher.launch(arrayOf("*/*"))
            },
            onDeployMods = {
                runInBackground { runDeployWorkflow() }
            },
            onWriteLoadOrderFiles = {
                runInBackground { runWriteLoadOrderFilesWorkflow() }
            },
            onToggleMod = { modId ->
                runInBackground { toggleModEnabled(modId) }
            },
            onMoveModUp = { modId ->
                runInBackground { moveModUp(modId) }
            },
            onMoveModDown = { modId ->
                runInBackground { moveModDown(modId) }
            },
            onDeleteMod = { mod ->
                showDeleteConfirmDialog(mod)
            },
            onTogglePlugin = { normalizedPath ->
                runInBackground { togglePluginEnabled(normalizedPath) }
            },
            onMovePluginUp = { normalizedPath ->
                runInBackground { movePluginUp(normalizedPath) }
            },
            onMovePluginDown = { normalizedPath ->
                runInBackground { movePluginDown(normalizedPath) }
            },
            onSelectGame = { gameId ->
                selectedGameId = gameId
                loadSelectedGameConfigIntoUi()
                runInBackground {
                    ensureDataBaselineIfMissing("selected game changed")
                    val engine = createModEngineForWorkflows()
                    if (engine != null) {
                        syncPluginsFromCurrentState(engine)
                    }
                    refreshDashboard()
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
                    saveSelectedGameConfigFromUi()
                    saveActiveProfileFromDashboard()
                    refreshDashboard()
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
                runInBackground { completeFirstSetup() }
            },
            onSelectProfile = { profileId ->
                runInBackground { switchActiveProfile(profileId) }
            },
            onNewProfileNameChanged = { newProfileNameText = it },
            onNewProfileGameChanged = { gameId ->
                newProfileGameId = gameId
                newProfileGameDisplayName = getGameDisplayName(gameId)
            },
            onNewProfileRealDeployChanged = { newProfileRealDeployEnabled = it },
            onCreateAdditionalProfile = {
                runInBackground { createAdditionalProfile() }
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
                runInBackground { deleteProfile(profileId) }
            },
            onToggleInstallerOption = { optionId ->
                toggleInstallerOption(optionId)
            },
            onConfirmInstaller = {
                runInBackground { finalizePendingInstallerInstall() }
            },
            onCancelInstaller = {
                runInBackground { cancelPendingInstallerInstall() }
            },
            onToggleInstallerFullscreen = {
                installerDialogFullscreen = !installerDialogFullscreen
            },
            onViewModFiles = { modId ->
                runInBackground { openModFilePreview(modId) }
            },
            onCloseModFilePreview = {
                selectedModFilePreview = null
                showModFilePreviewDialog = false
                modFilePreviewFullscreen = false
            },
            onToggleModFilePreviewFullscreen = {
                modFilePreviewFullscreen = !modFilePreviewFullscreen
            },
            onToggleSecondScreen = {
                toggleSecondScreenPluginDisplay()
            },
            onOpenModsFullscreen = {
                fullscreenPanel = FullscreenPanel.MODS
            },
            onOpenPluginsFullscreen = {
                fullscreenPanel = FullscreenPanel.PLUGINS
            },
            onCloseFullscreenPanel = {
                fullscreenPanel = FullscreenPanel.NONE
            },
            onApplyModOrder = { orderedModIds ->
                runInBackground { applyModOrder(orderedModIds) }
            },
            onApplyPluginOrder = { orderedPluginPaths ->
                runInBackground { applyPluginOrder(orderedPluginPaths) }
            },
            onOpenOverwriteFolder = {
                runInBackground { openOverwriteFolderPanel() }
            },
            onCloseOverwriteFolder = {
                showOverwriteDialog = false
            },
            onRepairV050Artifacts = {
                runInBackground { runV050ArtifactRepairTool() }
            },
            onBuildResolvedDataGraph = {
                runInBackground { runResolvedDataGraphDebugSummary() }
            },

        )

    }

    private fun initializeComposeUi() {
        runInBackground {
            loadSetupState()
            migrateLegacyGlobalStateIfNeeded()
            refreshGameOptions()
            loadSelectedGameConfigIntoUi()
            migratePrioritySpacingIfNeeded()

            ensureDataBaselineIfMissing("startup")

            val engine = createModEngineForWorkflows()
            if (engine != null) {
                syncPluginsFromCurrentState(engine)
            }
            refreshDashboard()
        }

        appendLog("UI ready.")
    }
    private fun runInBackground(block: () -> Unit) {
        Thread {
            block()
        }.start()
    }

    //log stuff
    private fun appendLog(message: String) {
        val line = formatLogLine(message)

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
        val line = formatLogLine("ERROR: $message")

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
    private fun appendDeploymentResultBlock(
        title: String,
        result: com.shonkware.droidmodloader.engine.deploy.DeploymentResult
    ) {
        appendLog("$title:")
        appendLog("  Adds: ${result.addCount}")
        appendLog("  Removes: ${result.removeCount}")
        appendLog("  Updates: ${result.updateCount}")
        appendLog("  Backups created: ${result.backupCount}")
        appendLog("  Backups restored: ${result.restoreCount}")
        appendLog("  Protected conflicts: ${result.protectedConflictCount}")
        appendLog("  Final file count: ${result.finalRecordCount}")
    }
    private fun timestampNow(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }
    private fun formatLogLine(message: String): String {
        return "[${timestampNow()}] $message"
    }
    private fun formatOperationDuration(startedAtMillis: Long): String {
        if (startedAtMillis <= 0L) {
            return "0 ms"
        }

        val elapsedMillis = System.currentTimeMillis() - startedAtMillis
        return "${elapsedMillis.coerceAtLeast(0L)} ms"
    }
    private fun beginOperation(text: String) {
        activeOperationStartedAtMillis = System.currentTimeMillis()

        runOnUiThread {
            operationInProgress = true
            activeOperationText = text
            updateLastOperationStatus(text)
        }

        showToast(text)
        appendLog("OPERATION START: $text")
    }
    private fun finishOperation(successText: String) {
        val durationText = formatOperationDuration(activeOperationStartedAtMillis)
        activeOperationStartedAtMillis = 0L

        val completedText = "$successText ($durationText)"

        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(completedText)
        }

        showToast(successText)
        appendLog("OPERATION END: $completedText")
    }
    private fun failOperation(message: String, throwable: Throwable? = null) {
        val durationText = formatOperationDuration(activeOperationStartedAtMillis)
        activeOperationStartedAtMillis = 0L

        val failedText = "$message ($durationText)"

        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(failedText)
        }

        showToast(message)
        appendError("OPERATION FAILED: $failedText", throwable)
    }

    private fun createModEngineForWorkflows(): ModEngine? {
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val profileInternalDir = getProfileInternalDir()
        val profileStateDir = getProfileStateDir(externalBaseDir)

        val tempDir = File(profileInternalDir, "temp")
        val modsDir = File(profileInternalDir, "mods")

        val stateFile = File(profileStateDir, "installed_mods.json")
        val pluginListFile = File(profileStateDir, "plugins.json")
        val pluginsTxtFile = File(profileStateDir, "plugins.txt")
        val loadorderTxtFile = File(profileStateDir, "loadorder.txt")
        val deploymentManifestFile = File(profileStateDir, "deployment_manifest.json")
        val gameConfigFile = File(profileStateDir, "game_deployment_configs.json")

        val deployDir = File(
            externalBaseDir,
            "deploy_target/profiles/${getActiveProfileStorageKey()}/$selectedGameId/Data"
        )

        tempDir.mkdirs()
        modsDir.mkdirs()
        profileStateDir.mkdirs()
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
            loadorderTxtFile = loadorderTxtFile
        )
    }
    private fun sanitizeStorageName(value: String): String {
        return value
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .trim('_')
            .ifBlank { "default" }
    }
    private fun getActiveProfileStorageKey(): String {
        val profileId = activeProfileId

        return if (!profileId.isNullOrBlank()) {
            sanitizeStorageName(profileId)
        } else {
            sanitizeStorageName("unassigned_$selectedGameId")
        }
    }
    private fun getProfileInternalDir(): File {
        return File(filesDir, "profiles/${getActiveProfileStorageKey()}")
    }
    private fun getProfileStateDir(externalBaseDir: File): File {
        return File(externalBaseDir, "state/profiles/${getActiveProfileStorageKey()}")
    }
    private fun copyUriToFile(uri: Uri, destinationFile: File): File {
        destinationFile.parentFile?.mkdirs()

        contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IllegalStateException("Could not open input stream for selected file.")
            }

            destinationFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return destinationFile
    }
    private fun copyUriToTemporaryArchiveFile(uri: Uri, sanitizedName: String): File {
        cleanOldTemporaryImportSources()

        val tempSourceDir = File(getProfileInternalDir(), "temp/import_sources")
        tempSourceDir.mkdirs()

        val tempArchive = File(
            tempSourceDir,
            "${System.currentTimeMillis()}_$sanitizedName"
        )

        return copyUriToFile(uri, tempArchive)
    }
    private fun cleanOldTemporaryImportSources(maxAgeMillis: Long = 24L * 60L * 60L * 1000L) {
        val tempSourceDir = File(getProfileInternalDir(), "temp/import_sources")
        if (!tempSourceDir.exists()) return

        val now = System.currentTimeMillis()

        tempSourceDir.listFiles()
            ?.filter { it.isFile }
            ?.filter { now - it.lastModified() > maxAgeMillis }
            ?.forEach { it.delete() }
    }
    private fun migrateLegacyGlobalStateIfNeeded() {
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("Cannot migrate legacy state: external files directory is null.")
            return
        }

        val currentProfileId = activeProfileId
        if (currentProfileId.isNullOrBlank()) {
            appendLog("Skipping legacy migration: no active profile.")
            return
        }

        val stateDir = File(externalBaseDir, "state")
        val migrationMarker = File(stateDir, "profile_storage_migration_v2.json")

        if (migrationMarker.exists()) {
            appendLog("Legacy profile storage migration already completed.")
            return
        }

        val legacyModsDir = File(filesDir, "mods")
        val legacyStateFile = File(stateDir, "installed_mods.json")
        val legacyPluginListFile = File(stateDir, "plugins.json")
        val legacyPluginsTxtFile = File(stateDir, "plugins.txt")
        val legacyLoadorderTxtFile = File(stateDir, "loadorder.txt")
        val legacyGameConfigFile = File(stateDir, "game_deployment_configs.json")

        val hasLegacyState =
            legacyModsDir.exists() ||
                    legacyStateFile.exists() ||
                    legacyPluginListFile.exists() ||
                    legacyPluginsTxtFile.exists() ||
                    legacyLoadorderTxtFile.exists() ||
                    legacyGameConfigFile.exists()

        if (!hasLegacyState) {
            writeLegacyMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "no_legacy_state_found"
            )
            appendLog("No legacy global mod/plugin state found to migrate.")
            return
        }

        val profileInternalDir = getProfileInternalDir()
        val profileModsDir = File(profileInternalDir, "mods")
        val profileStateDir = getProfileStateDir(externalBaseDir)

        val profileAlreadyHasState =
            File(profileStateDir, "installed_mods.json").exists() ||
                    File(profileStateDir, "plugins.json").exists() ||
                    (profileModsDir.exists() && profileModsDir.listFiles()?.isNotEmpty() == true)

        if (profileAlreadyHasState) {
            writeLegacyMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "skipped_profile_already_has_state"
            )
            appendLog("Skipped legacy migration because active profile already has profile-scoped state.")
            return
        }

        try {
            profileInternalDir.mkdirs()
            profileModsDir.mkdirs()
            profileStateDir.mkdirs()

            if (legacyModsDir.exists()) {
                legacyModsDir.copyRecursively(
                    target = profileModsDir,
                    overwrite = false
                )
                appendLog("Copied legacy mods folder into active profile.")
            }

            copyLegacyFileIfExists(
                source = legacyStateFile,
                destination = File(profileStateDir, "installed_mods.json")
            )

            copyLegacyFileIfExists(
                source = legacyPluginListFile,
                destination = File(profileStateDir, "plugins.json")
            )

            copyLegacyFileIfExists(
                source = legacyPluginsTxtFile,
                destination = File(profileStateDir, "plugins.txt")
            )

            copyLegacyFileIfExists(
                source = legacyLoadorderTxtFile,
                destination = File(profileStateDir, "loadorder.txt")
            )

            copyLegacyFileIfExists(
                source = legacyGameConfigFile,
                destination = File(profileStateDir, "game_deployment_configs.json")
            )

            writeLegacyMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "migrated_to_active_profile"
            )

            appendLog("Migrated legacy global mod/plugin state into active profile: $currentProfileId")
            updateLastOperationStatus("Legacy mod state migrated into active profile.")
        } catch (e: Exception) {
            appendError("Legacy profile migration failed: ${e.message}", e)
        }
    }
    private fun copyLegacyFileIfExists(source: File, destination: File) {
        if (!source.exists()) return

        destination.parentFile?.mkdirs()

        if (!destination.exists()) {
            source.copyTo(destination, overwrite = false)
            appendLog("Migrated legacy file: ${source.name}")
        }
    }
    private fun writeLegacyMigrationMarker(
        markerFile: File,
        profileId: String,
        status: String
    ) {
        markerFile.parentFile?.mkdirs()

        markerFile.writeText(
            """
        {
          "schemaVersion": 1,
          "profileId": "$profileId",
          "status": "$status",
          "createdAtEpochMillis": ${System.currentTimeMillis()}
        }
        """.trimIndent()
        )
    }

    private fun handleImportedArchive(uri: Uri) {
        if (operationInProgress) {
            appendLog("Ignoring import request: operation already in progress.")
            return
        }
        beginOperation("Importing archive...")

        val engine = createModEngineForWorkflows() ?: return
        val fileName = queryDisplayName(uri) ?: "imported_mod"
        val sanitizedName = fileName.replace(Regex("""[^\w.\- ]"""), "_")

        var temporaryArchive: File? = null

        try {
            temporaryArchive = copyUriToTemporaryArchiveFile(uri, sanitizedName)

            appendLog("Selected archive copied to temporary install cache: ${temporaryArchive.name}")
            appendLog("Temporary archive size: ${temporaryArchive.length()} bytes")
            appendLog("About to install imported archive using engine...")

            val existingMods = engine.getInstalledModsFromFolders()
            val nextPriority = if (existingMods.isEmpty()) 1 else (existingMods.maxOf { it.priority } + 1)

            val prepared = engine.prepareArchiveInstall(temporaryArchive)
            temporaryArchive.delete()
            temporaryArchive = null

            if (prepared.plan.requiresUserChoice) {
                runOnUiThread {
                    pendingArchiveInstall = prepared
                    pendingInstallerSelectedOptionIds = prepared.plan.defaultSelectedOptionIds
                    showInstallerDialog = true
                    installerDialogFullscreen = false
                }

                appendLog("Installer choices required: ${prepared.plan.installerType}")
                prepared.plan.warnings.forEach { appendLog("INSTALLER WARNING: $it") }

                finishOperation("Choose installer options.")
                return
            }

            val installedMod = engine.finalizePreparedArchiveInstall(
                prepared = prepared,
                selectedOptionIds = prepared.plan.defaultSelectedOptionIds,
                priority = nextPriority,
                sourceType = "imported_archive"
            )

            appendLog("Archive install returned successfully.")
            val currentMods = engine.getCurrentMods()
                .filterNot { it.id == installedMod.id }
                .sortedBy { it.priority }

            val updatedMods = currentMods + installedMod.copy(priority = currentMods.size + 1)
            engine.saveCurrentMods(updatedMods)

            syncPluginsFromCurrentState(engine)

            appendLog("Installed imported mod: $installedMod")
            appendInstalledModRoutingSummary(engine, installedMod)
            appendLog("Saved installed mod count after import: ${updatedMods.size}")
            appendLog("Plugins refreshed automatically.")
            appendLog("RESULT: PASS")
            finishOperation("Archive imported successfully.")
        } catch (t: Throwable) {
            temporaryArchive?.delete()

            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            failOperation("Import archive failed: ${t.message}", t)
        }

        refreshDashboard()
        appendLog("----- Import Archive Workflow End -----")
    }
    private fun normalizePriorities(mods: List<Mod>): List<Mod> {
        return mods.mapIndexed { index, mod ->
            mod.copy(priority = index + 1)
        }
    }
    private fun toggleModEnabled(modId: String) {
        val engine = createModEngineForWorkflows() ?: return
        val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()

        val index = mods.indexOfFirst { it.id == modId }
        if (index == -1) {
            appendError("Could not find mod: $modId")
            return
        }

        mods[index] = mods[index].copy(enabled = !mods[index].enabled)
        engine.saveCurrentMods(normalizePriorities(mods))
        appendLog("Toggled enabled state for $modId")
        syncPluginsFromCurrentState(engine)
        refreshDashboard()
    }
    private fun moveModUp(modId: String) {
        val engine = createModEngineForWorkflows() ?: return
        val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()

        val index = mods.indexOfFirst { it.id == modId }
        if (index <= 0) {
            appendLog("Cannot move up: $modId")
            return
        }

        val temp = mods[index - 1]
        mods[index - 1] = mods[index]
        mods[index] = temp

        engine.saveCurrentMods(normalizePriorities(mods))

        appendLog("Moved up: $modId")
        syncPluginsFromCurrentState(engine)
        refreshDashboard()
    }
    private fun moveModDown(modId: String) {
        val engine = createModEngineForWorkflows() ?: return
        val mods = engine.getCurrentMods().sortedBy { it.priority }.toMutableList()

        val index = mods.indexOfFirst { it.id == modId }
        if (index == -1 || index >= mods.lastIndex) {
            appendLog("Cannot move down: $modId")
            return
        }

        val temp = mods[index + 1]
        mods[index + 1] = mods[index]
        mods[index] = temp

        engine.saveCurrentMods(normalizePriorities(mods))

        appendLog("Moved down: $modId")
        syncPluginsFromCurrentState(engine)
        refreshDashboard()
    }
    private fun deleteInstalledMod(modId: String) {
        appendLog("----- Delete Installed Mod Workflow Start -----")
        appendLog("Requested delete for mod: $modId")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val result = engine.uninstallModAndApplyDiff(modId)

            if (!result.removed) {
                appendError("Could not remove mod: $modId")
                appendLog("RESULT: FAIL")
                updateLastOperationStatus("Delete mod failed: could not remove $modId.")
                appendLog("----- Delete Installed Mod Workflow End -----")
                return
            }

            appendLog("Deleted mod: ${result.removedModId}")
            appendLog("Deleted installed mod files: ${result.deletedFileCount}")
            appendLog("Deploy again to remove this mod's files from the selected game Data folder.")

            syncPluginsFromCurrentState(engine)

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
                    runInBackground { deleteInstalledMod(mod.id) }
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
        saveActiveProfileFromDashboard()

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
        saveActiveProfileFromDashboard()
        refreshDashboard()

        appendLog("Saved picked game root folder URI for $selectedGameId")
    }
    private fun togglePluginEnabled(normalizedPath: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index == -1) {
            appendError("Could not find plugin: $normalizedPath")
            return
        }

        val updated = plugins[index].copy(enabled = !plugins[index].enabled)
        plugins[index] = updated

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Toggled plugin enabled state for ${updated.pluginName}")
        updateLastOperationStatus("Plugin updated: ${updated.pluginName}")
        refreshDashboard()
    }
    private fun movePluginUp(normalizedPath: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index <= 0) {
            appendLog("Cannot move plugin up: $normalizedPath")
            return
        }

        val temp = plugins[index - 1]
        plugins[index - 1] = plugins[index]
        plugins[index] = temp

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin up: ${plugins[index - 1].pluginName}")
        updateLastOperationStatus("Plugin moved up.")
        refreshDashboard()
    }
    private fun movePluginDown(normalizedPath: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.normalizedPath == normalizedPath }
        if (index == -1 || index >= plugins.lastIndex) {
            appendLog("Cannot move plugin down: $normalizedPath")
            return
        }

        val temp = plugins[index + 1]
        plugins[index + 1] = plugins[index]
        plugins[index] = temp

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin down: ${plugins[index + 1].pluginName}")
        updateLastOperationStatus("Plugin moved down.")
        refreshDashboard()
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
            appendLine("Display Version: 0.1 Beta")
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
    private fun runDeployWorkflow() {
        if (operationInProgress) {
            appendLog("Ignoring deploy request: operation already in progress.")
            return
        }

        beginOperation("Deploying mods...")

        try {
            saveActiveProfileFromDashboard()
            saveSelectedGameConfigFromUi()

            val engine = createModEngineForWorkflows() ?: return

            val config = engine.getGameDeploymentConfig(selectedGameId)
            appendLog("Selected game: $selectedGameId")
            appendLog("Active config: $config")
            appendLog(engine.getDeploymentTargetDebugSummary(selectedGameId))
            val rootRecordCount = engine.getCurrentRootWinningRecords().size
            val rootTargetSelected =
                config != null &&
                        config.realDeployEnabled &&
                        (
                                !config.targetRootTreeUri.isNullOrBlank() ||
                                        engine.validateTargetDataPath(config.targetRootPath)
                                )

            if (rootRecordCount > 0) {
                appendLog("Root-scope deployable file count: $rootRecordCount")
            }

            if (rootRecordCount > 0 && config?.realDeployEnabled == true && !rootTargetSelected) {
                appendLog(
                    "WARNING: Root-scope files were detected, but no game root folder is selected. " +
                            "Pick the game root folder to deploy files like SKSE/NVSE loaders, DLLs, ENB files, or other root-level files."
                )
            }

            val result = engine.deployForGame(selectedGameId)

            val usingRealDeploy = config != null && config.realDeployEnabled
            val usingTreeUri = usingRealDeploy && !config.targetTreeUri.isNullOrBlank()
            val usingRealPath = usingRealDeploy && engine.validateTargetDataPath(config.targetDataPath)

            val effectiveMode = when {
                usingTreeUri -> "Tree URI"
                usingRealPath -> "Real Path"
                else -> "Simulated"
            }

            val effectiveTarget = when {
                usingTreeUri -> config?.targetTreeUri ?: "none"
                usingRealPath -> config?.targetDataPath ?: "none"
                else -> File(
                    getExternalFilesDir(null),
                    "deploy_target/profiles/${getActiveProfileStorageKey()}/$selectedGameId/Data"
                ).absolutePath
            }

            appendLog("Deploy mode: $effectiveMode")
            appendLog("Data deploy target: $effectiveTarget")

            val rootTarget = when {
                config != null &&
                        config.realDeployEnabled &&
                        !config.targetRootTreeUri.isNullOrBlank() -> {
                    "TREE_URI:${config.targetRootTreeUri}"
                }

                config != null &&
                        config.realDeployEnabled &&
                        engine.validateTargetDataPath(config.targetRootPath) -> {
                    config.targetRootPath
                }

                else -> {
                    "Simulated game root"
                }
            }

            appendLog("Game root deploy target: $rootTarget")

            appendDeploymentResultBlock(
                title = "Data deploy result",
                result = result.dataResult
            )

            appendDeploymentResultBlock(
                title = "Game root deploy result",
                result = result.rootResult
            )

            appendLog("Combined deploy result:")
            appendLog("  Adds: ${result.addCount}")
            appendLog("  Removes: ${result.removeCount}")
            appendLog("  Updates: ${result.updateCount}")
            appendLog("  Backups created: ${result.dataResult.backupCount + result.rootResult.backupCount}")
            appendLog("  Backups restored: ${result.dataResult.restoreCount + result.rootResult.restoreCount}")
            appendLog("  Protected conflicts: ${result.dataResult.protectedConflictCount + result.rootResult.protectedConflictCount}")
            appendLog("  Final file count: ${result.finalRecordCount}")

            appendLog("RESULT: PASS")

            finishOperation("Deploy succeeded ($effectiveMode).")
        } catch (e: Exception) {
            appendError("Deploy workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Deploy failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Deploy Workflow End -----")
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
                val recoveredConfig = GameDeploymentConfig(
                    gameId = fallbackProfile.gameId,
                    displayName = fallbackProfile.gameDisplayName,
                    targetDataPath = fallbackProfile.targetDataPath,
                    realDeployEnabled = fallbackProfile.realDeployEnabled,
                    targetTreeUri = fallbackProfile.targetTreeUri,
                    targetRootPath = fallbackProfile.targetRootPath,
                    targetRootTreeUri = fallbackProfile.targetRootTreeUri
                )

                val configs = engine.loadGameDeploymentConfigs().toMutableList()
                val index = configs.indexOfFirst { it.gameId == recoveredConfig.gameId }

                if (index >= 0) {
                    configs[index] = recoveredConfig
                } else {
                    configs.add(recoveredConfig)
                }

                engine.saveGameDeploymentConfigs(configs)

                runOnUiThread {
                    targetPathText = recoveredConfig.targetDataPath
                    realDeployEnabledState = recoveredConfig.realDeployEnabled
                    selectedTreeUriText = recoveredConfig.targetTreeUri ?: "No folder selected"
                    rootTargetPathText = recoveredConfig.targetRootPath
                    selectedRootTreeUriText = recoveredConfig.targetRootTreeUri ?: "No root folder selected"
                }

                appendLog("Recovered missing config from active profile: $recoveredConfig")
                return
            }

            runOnUiThread {
                targetPathText = ""
                realDeployEnabledState = false
                selectedTreeUriText = "No folder selected"
                rootTargetPathText = ""
                selectedRootTreeUriText = "No root folder selected"
            }

            appendLog("No config found for gameId=$selectedGameId")
            return
        }

        runOnUiThread {
            targetPathText = config.targetDataPath
            realDeployEnabledState = config.realDeployEnabled
            selectedTreeUriText = config.targetTreeUri ?: "No folder selected"
            rootTargetPathText = config.targetRootPath
            selectedRootTreeUriText = config.targetRootTreeUri ?: "No root folder selected"
        }

        appendLog("Loaded config into Compose state: $config")
    }

    private fun saveSelectedGameConfigFromUi() {
        val engine = createModEngineForWorkflows() ?: return

        val existingConfigs = engine.loadGameDeploymentConfigs().toMutableList()

        val selectedTreeUri = selectedTreeUriText
            .trim()
            .takeIf { it.isNotBlank() && it != "No folder selected" }
        val selectedRootTreeUri = selectedRootTreeUriText
            .trim()
            .takeIf { it.isNotBlank() && it != "No root folder selected" }

        val updatedConfig = GameDeploymentConfig(
            gameId = selectedGameId,
            displayName = getGameDisplayName(selectedGameId),
            targetDataPath = targetPathText.trim(),
            realDeployEnabled = realDeployEnabledState,
            targetTreeUri = selectedTreeUri,
            targetRootPath = rootTargetPathText.trim(),
            targetRootTreeUri = selectedRootTreeUri
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

    private fun runWriteLoadOrderFilesWorkflow() {
        if (operationInProgress) {
            appendLog("Ignoring plugin file write request: operation already in progress.")
            return
        }

        beginOperation("Writing plugin files...")

        val engine = createModEngineForWorkflows()
        if (engine == null) {
            failOperation("Writing plugin files failed: engine could not be created.")
            return
        }

        try {
            val savedPlugins = engine.loadPlugins().sortedBy { it.priority }

            if (savedPlugins.isEmpty()) {
                appendLog("No saved plugin list found. Refreshing plugin list once before writing files.")
                syncPluginsFromCurrentState(engine)
            }

            val pluginsAfterFallback = engine.loadPlugins().sortedBy { it.priority }

            if (pluginsAfterFallback.isEmpty()) {
                appendLog("No plugins available to write.")
                appendLog("RESULT: FAIL")
                failOperation("Writing plugin files failed: no plugins available.")
                return
            }

            val (pluginsTxt, loadorderTxt) = engine.exportSavedPluginOutputs()
            val (pluginsTxtPath, loadorderTxtPath) = engine.getPluginOutputFilePaths()

            val enabledPluginCount = pluginsAfterFallback.count { it.enabled }

            appendLog("Plugin files written from saved plugin list.")
            appendLog("Plugin count: ${pluginsAfterFallback.size}")
            appendLog("Enabled plugin count: $enabledPluginCount")
            appendLog("plugins.txt path: $pluginsTxtPath")
            appendLog("loadorder.txt path: $loadorderTxtPath")
            appendLog("plugins.txt line count: ${pluginsTxt.lines().filter { it.isNotBlank() }.size}")
            appendLog("loadorder.txt line count: ${loadorderTxt.lines().filter { it.isNotBlank() }.size}")
            appendLog("RESULT: PASS")

            finishOperation("Plugin files written successfully.")
        } catch (e: Exception) {
            appendError("Write plugin files workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Writing plugin files failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Write Plugin Files Workflow End -----")
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
                activeProfileName = activeProfile.profileName
                selectedGameId = activeProfile.gameId

                targetPathText = activeProfile.targetDataPath
                selectedTreeUriText = activeProfile.targetTreeUri ?: "No folder selected"

                rootTargetPathText = activeProfile.targetRootPath
                selectedRootTreeUriText = activeProfile.targetRootTreeUri ?: "No root folder selected"

                realDeployEnabledState = activeProfile.realDeployEnabled

                visibleMods = emptyList()
                visiblePlugins = emptyList()
                visibleModContentIndexes = emptyMap()
            } else {
                activeProfileName = "No profile"
                selectedGameId = "No Game Selected"

                targetPathText = ""
                selectedTreeUriText = "No folder selected"

                rootTargetPathText = ""
                selectedRootTreeUriText = "No root folder selected"

                realDeployEnabledState = false

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

    private fun completeFirstSetup() {
        val repo = createProfileRepository() ?: return

        val profileId = "${setupGameId}_${System.currentTimeMillis()}"
        val cleanProfileName = profileNameText.trim().ifBlank { "Default" }

        val profile = GameProfile(
            profileId = profileId,
            profileName = cleanProfileName,
            gameId = setupGameId,
            gameDisplayName = getGameDisplayName(setupGameId),
            targetDataPath = "",
            targetTreeUri = null,
            targetRootPath = "",
            targetRootTreeUri = null,
            realDeployEnabled = setupRealDeployEnabled,
            iniPresetId = null
        )

        val existingProfiles = repo.loadProfiles().toMutableList()
        existingProfiles.add(profile)

        repo.saveProfiles(existingProfiles)
        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profileId
            )
        )

        runOnUiThread {
            setupComplete = true
            activeProfileId = profileId
            activeProfileName = profile.profileName
            profileOptions = existingProfiles

            selectedGameId = profile.gameId
            rootTargetPathText = profile.targetRootPath
            selectedRootTreeUriText = profile.targetRootTreeUri ?: "No root folder selected"
            realDeployEnabledState = profile.realDeployEnabled
        }

        saveSelectedGameConfigFromUi()

        appendLog("Created first profile: $profile")
        updateLastOperationStatus("Setup complete.")
        refreshDashboard()
    }

    private fun createAdditionalProfile() {
        val repo = createProfileRepository() ?: return

        val cleanProfileName = newProfileNameText.trim().ifBlank {
            "${getGameDisplayName(newProfileGameId)} Profile"
        }

        val profileId = "${newProfileGameId}_${System.currentTimeMillis()}"

        val profile = GameProfile(
            profileId = profileId,
            profileName = cleanProfileName,
            gameId = newProfileGameId,
            gameDisplayName = getGameDisplayName(newProfileGameId),
            targetDataPath = "",
            targetRootPath = "",
            targetRootTreeUri = null,
            targetTreeUri = if (newProfileTreeUriText == "No folder selected") null else newProfileTreeUriText,
            realDeployEnabled = newProfileRealDeployEnabled,
            iniPresetId = null
        )

        val profiles = repo.loadProfiles().toMutableList()
        profiles.add(profile)
        repo.saveProfiles(profiles)
        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profile.profileId
            )
        )

        runOnUiThreadBlocking {
            profileOptions = profiles
            activeProfileId = profile.profileId
            activeProfileName = profile.profileName
            selectedGameId = profile.gameId


            targetPathText = profile.targetDataPath
            selectedTreeUriText = profile.targetTreeUri ?: "No folder selected"

            rootTargetPathText = profile.targetRootPath
            selectedRootTreeUriText = profile.targetRootTreeUri ?: "No root folder selected"

            realDeployEnabledState = profile.realDeployEnabled
            visibleMods = emptyList()
            visiblePlugins = emptyList()
            visibleModContentIndexes = emptyMap()

            newProfileNameText = ""
            newProfileTreeUriText = "No folder selected"
            newProfileRealDeployEnabled = false
            showProfileDialog = false
        }

        saveSelectedGameConfigFromUi()

        val engine = createModEngineForWorkflows()
        if (engine != null) {
            syncPluginsFromCurrentState(engine)
        }

        refreshDashboard()

        appendLog("Created and switched to profile: $profile")
        updateLastOperationStatus("Profile created and selected: ${profile.profileName}")
    }

    private fun switchActiveProfile(profileId: String) {
        val repo = createProfileRepository() ?: return
        val profiles = repo.loadProfiles()
        val profile = profiles.firstOrNull { it.profileId == profileId }

        if (profile == null) {
            appendError("Could not switch profile: profile not found: $profileId")
            return
        }

        saveActiveProfileFromDashboard()

        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profile.profileId
            )
        )

        runOnUiThreadBlocking {
            activeProfileId = profile.profileId
            activeProfileName = profile.profileName
            selectedGameId = profile.gameId


            targetPathText = profile.targetDataPath
            selectedTreeUriText = profile.targetTreeUri ?: "No folder selected"

            rootTargetPathText = profile.targetRootPath
            selectedRootTreeUriText = profile.targetRootTreeUri ?: "No root folder selected"

            realDeployEnabledState = profile.realDeployEnabled
            visibleMods = emptyList()
            visiblePlugins = emptyList()
            visibleModContentIndexes = emptyMap()
        }

        loadSelectedGameConfigIntoUi()

        val engine = createModEngineForWorkflows()
        if (engine != null) {
            syncPluginsFromCurrentState(engine)
        }

        refreshDashboard()

        appendLog("Switched active profile: $profile")
        updateLastOperationStatus("Switched profile: ${profile.profileName}")
    }

    private fun saveActiveProfileFromDashboard() {
        val repo = createProfileRepository() ?: return
        val currentProfileId = activeProfileId

        if (currentProfileId == null) {
            appendError("Cannot save active profile: no active profile.")
            return
        }

        val profiles = repo.loadProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.profileId == currentProfileId }

        if (index == -1) {
            appendError("Active profile not found: $currentProfileId")
            return
        }

        val oldProfile = profiles[index]
        val updatedProfile = oldProfile.copy(
            gameId = oldProfile.gameId,
            gameDisplayName = getGameDisplayName(oldProfile.gameId),
            targetDataPath = targetPathText.trim(),
            targetTreeUri = if (selectedTreeUriText == "No folder selected") null else selectedTreeUriText,
            targetRootPath = rootTargetPathText.trim(),
            targetRootTreeUri = if (selectedRootTreeUriText == "No root folder selected") null else selectedRootTreeUriText,
            realDeployEnabled = realDeployEnabledState
        )

        profiles[index] = updatedProfile
        repo.saveProfiles(profiles)

        runOnUiThread {
            profileOptions = profiles
            activeProfileName = updatedProfile.profileName
        }

        appendLog("Saved active profile: $updatedProfile")
    }

    private fun deleteProfile(profileId: String) {
        val repo = createProfileRepository() ?: return

        val profiles = repo.loadProfiles().toMutableList()
        val profileToDelete = profiles.firstOrNull { it.profileId == profileId }

        if (profileToDelete == null) {
            appendError("Profile not found: $profileId")
            return
        }

        profiles.removeAll { it.profileId == profileId }
        repo.saveProfiles(profiles)

        val newActiveProfile = if (activeProfileId == profileId) {
            profiles.firstOrNull()
        } else {
            profiles.firstOrNull { it.profileId == activeProfileId }
        }

        repo.saveSetupState(
            AppSetupState(
                setupComplete = profiles.isNotEmpty(),
                activeProfileId = newActiveProfile?.profileId
            )
        )

        runOnUiThread {
            profileOptions = profiles
            activeProfileId = newActiveProfile?.profileId
            activeProfileName = newActiveProfile?.profileName ?: "No profile"
            setupComplete = profiles.isNotEmpty()


            if (newActiveProfile != null) {
                selectedGameId = newActiveProfile.gameId
                targetPathText = newActiveProfile.targetDataPath
                selectedTreeUriText = newActiveProfile.targetTreeUri ?: "No folder selected"
                rootTargetPathText = newActiveProfile.targetRootPath
                selectedRootTreeUriText = newActiveProfile.targetRootTreeUri ?: "No root folder selected"
                realDeployEnabledState = newActiveProfile.realDeployEnabled
            } else {
                selectedGameId = "No Game Selected"
                targetPathText = ""
                selectedTreeUriText = "No folder selected"
                rootTargetPathText = ""
                selectedRootTreeUriText = "No root folder selected"
                realDeployEnabledState = false
                showProfileDialog = false
                visiblePlugins = emptyList()
            }
        }

        appendLog("Deleted profile settings only: ${profileToDelete.profileName}")
        updateLastOperationStatus("Deleted profile: ${profileToDelete.profileName}")

        runOnUiThreadBlocking {
            profileOptions = profiles
            activeProfileId = newActiveProfile?.profileId
            activeProfileName = newActiveProfile?.profileName ?: "No profile"
            setupComplete = profiles.isNotEmpty()

            if (newActiveProfile != null) {
                selectedGameId = newActiveProfile.gameId
                targetPathText = newActiveProfile.targetDataPath
                selectedTreeUriText = newActiveProfile.targetTreeUri ?: "No folder selected"
                rootTargetPathText = newActiveProfile.targetRootPath
                selectedRootTreeUriText = newActiveProfile.targetRootTreeUri ?: "No root folder selected"
                realDeployEnabledState = newActiveProfile.realDeployEnabled
            } else {
                selectedGameId = "No Game Selected"
                targetPathText = ""
                selectedTreeUriText = "No folder selected"
                rootTargetPathText = ""
                selectedRootTreeUriText = "No root folder selected"
                realDeployEnabledState = false
                showProfileDialog = false
            }

            visibleMods = emptyList()
            visiblePlugins = emptyList()
            visibleModContentIndexes = emptyMap()
        }
    }


    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    private fun finalizePendingInstallerInstall() {
        if (operationInProgress) {
            appendLog("Ignoring installer finalize request: operation already in progress.")
            return
        }

        val prepared = pendingArchiveInstall
        if (prepared == null) {
            appendError("No pending installer session found.")
            return
        }

        beginOperation("Installing selected options...")

        val engine = createModEngineForWorkflows()
        if (engine == null) {
            failOperation("Install failed: could not create engine.")
            return
        }

        try {
            val existingMods = engine.getCurrentMods()
            val nextPriority = if (existingMods.isEmpty()) 1 else existingMods.maxOf { it.priority } + 1

            val installedMod = engine.finalizePreparedArchiveInstall(
                prepared = prepared,
                selectedOptionIds = pendingInstallerSelectedOptionIds,
                priority = nextPriority,
                sourceType = "imported_archive"
            )

            val currentMods = engine.getCurrentMods()
                .filterNot { it.id == installedMod.id }
                .sortedBy { it.priority }

            val updatedMods = currentMods + installedMod.copy(priority = currentMods.size + 1)
            engine.saveCurrentMods(updatedMods)

            syncPluginsFromCurrentState(engine)

            runOnUiThread {
                pendingArchiveInstall = null
                pendingInstallerSelectedOptionIds = emptySet()
                showInstallerDialog = false
                installerDialogFullscreen = false
            }

            appendLog("Installed selected options for: ${prepared.archiveName}")
            appendLog("Installed mod: $installedMod")
            appendInstalledModRoutingSummary(engine, installedMod)
            appendLog("RESULT: PASS")

            finishOperation("Archive imported successfully.")
            refreshDashboard()
        } catch (t: Throwable) {
            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            failOperation("Installer finalize failed: ${t.message}", t)
        }
    }
    private fun toggleInstallerOption(optionId: String) {
        val prepared = pendingArchiveInstall ?: return
        val plan = prepared.plan

        val group = plan.groups.firstOrNull { candidate ->
            candidate.options.any { it.id == optionId }
        }

        if (group == null) {
            pendingInstallerSelectedOptionIds =
                if (pendingInstallerSelectedOptionIds.contains(optionId)) {
                    pendingInstallerSelectedOptionIds - optionId
                } else {
                    pendingInstallerSelectedOptionIds + optionId
                }
            return
        }

        val option = group.options.firstOrNull { it.id == optionId } ?: return

        if (option.required) {
            return
        }

        val groupOptionIds = group.options.map { it.id }.toSet()

        pendingInstallerSelectedOptionIds = when (group.type) {
            InstallerGroupType.SELECT_EXACTLY_ONE -> {
                // Always keep exactly one option from this group selected.
                (pendingInstallerSelectedOptionIds - groupOptionIds) + optionId
            }

            InstallerGroupType.SELECT_AT_MOST_ONE -> {
                // Allow zero or one selected option from this group.
                if (pendingInstallerSelectedOptionIds.contains(optionId)) {
                    pendingInstallerSelectedOptionIds - optionId
                } else {
                    (pendingInstallerSelectedOptionIds - groupOptionIds) + optionId
                }
            }

            InstallerGroupType.SELECT_AT_LEAST_ONE -> {
                // Allow multiple, but do not allow removing the last selected option from this group.
                val currentlySelectedInGroup = pendingInstallerSelectedOptionIds.intersect(groupOptionIds)

                if (pendingInstallerSelectedOptionIds.contains(optionId)) {
                    if (currentlySelectedInGroup.size <= 1) {
                        pendingInstallerSelectedOptionIds
                    } else {
                        pendingInstallerSelectedOptionIds - optionId
                    }
                } else {
                    pendingInstallerSelectedOptionIds + optionId
                }
            }

            InstallerGroupType.SELECT_ANY -> {
                // Normal checkbox behavior.
                if (pendingInstallerSelectedOptionIds.contains(optionId)) {
                    pendingInstallerSelectedOptionIds - optionId
                } else {
                    pendingInstallerSelectedOptionIds + optionId
                }
            }
        }
    }
    private fun cancelPendingInstallerInstall() {
        val prepared = pendingArchiveInstall ?: return
        val engine = createModEngineForWorkflows() ?: return

        try {
            engine.cancelPreparedArchiveInstall(prepared)
            appendLog("Cancelled installer session for: ${prepared.archiveName}")
        } catch (e: Exception) {
            appendError("Failed to clean installer session: ${e.message}", e)
        }

        runOnUiThread {
            pendingArchiveInstall = null
            pendingInstallerSelectedOptionIds = emptySet()
            showInstallerDialog = false
            installerDialogFullscreen = false
        }

        updateLastOperationStatus("Installer cancelled.")
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

    private fun applyModOrder(orderedModIds: List<String>) {
        val engine = createModEngineForWorkflows() ?: return

        try {
            engine.applyModPriorityOrder(orderedModIds)
            syncPluginsFromCurrentState(engine)
            appendLog("Applied dragged mod order.")
            refreshDashboard()
        } catch (e: Exception) {
            appendError("Could not apply dragged mod order: ${e.message}", e)
        }
    }

    private fun applyPluginOrder(orderedPluginPaths: List<String>) {
        val engine = createModEngineForWorkflows() ?: return

        try {
            engine.applyPluginPriorityOrder(orderedPluginPaths)
            appendLog("Applied dragged plugin order.")
            refreshDashboard()
        } catch (e: Exception) {
            appendError("Could not apply dragged plugin order: ${e.message}", e)
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

            val profileInternalDir = getProfileInternalDir()
            val modsDir = File(profileInternalDir, "mods")
            val profileStateDir = getProfileStateDir(externalBaseDir)

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
}

