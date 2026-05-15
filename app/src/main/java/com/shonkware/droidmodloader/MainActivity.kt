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
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }

    private enum class FolderPickMode {
        ActiveProfile,
        NewProfile
    }

    private var secondScreenController: SecondScreenController? = null

    private var secondScreenEnabled by mutableStateOf(false)

    private var folderPickMode by mutableStateOf(FolderPickMode.ActiveProfile)
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
                    FolderPickMode.ActiveProfile -> {
                        savePickedFolderToSelectedGameConfig(uriString)
                    }

                    FolderPickMode.NewProfile -> {
                        runOnUiThread {
                            newProfileTreeUriText = uriString
                        }
                        appendLog("Selected target folder for new profile.")
                    }
                }
            }
        } catch (e: Exception) {
            appendError("Failed to persist folder permission: ${e.message}", e)
        }
    }

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
            versionLabel = "Version 0.4 Beta",
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
                runInBackground { loadSelectedGameConfigIntoUi() }
            },
            onRealDeployChanged = { enabled ->
                realDeployEnabledState = enabled
            },
            onPickTargetFolder = {
                folderPickMode = FolderPickMode.ActiveProfile
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
                setupGameDisplayName = when (gameId) {
                    "skyrim_le" -> "Skyrim Legendary Edition"
                    "fallout_nv" -> "Fallout New Vegas"
                    else -> gameId
                }
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
                newProfileGameDisplayName = when (gameId) {
                    "skyrim_le" -> "Skyrim Legendary Edition"
                    "fallout_nv" -> "Fallout New Vegas"
                    else -> gameId
                }
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
                folderPickMode = FolderPickMode.NewProfile
                pickTargetFolderLauncher.launch(null)
            },
            onDeleteProfile = { profileId ->
                runInBackground { deleteProfile(profileId) }
            },
            onToggleInstallerOption = { optionId ->
                val current = pendingInstallerSelectedOptionIds
                pendingInstallerSelectedOptionIds =
                    if (current.contains(optionId)) current - optionId else current + optionId
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
        )

    }

    private fun initializeComposeUi() {
        runInBackground {
            loadSetupState()
            refreshGameOptions()
            loadSelectedGameConfigIntoUi()
            migratePrioritySpacingIfNeeded()

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
    private fun appendLog(message: String) {
        Log.d(TAG, message)
        appendLogToFile(message)

        runOnUiThread {
            logText = if (logText.isBlank()) {
                message
            } else {
                logText + "\n" + message
            }
        }
    }
    private fun appendError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
            appendLogToFile("ERROR: $message\n${Log.getStackTraceString(throwable)}")
        } else {
            Log.e(TAG, message)
            appendLogToFile("ERROR: $message")
        }

        runOnUiThread {
            logText = if (logText.isBlank()) {
                "ERROR: $message"
            } else {
                logText + "\nERROR: $message"
            }
        }
    }
    private fun createModEngineForWorkflows(): ModEngine? {
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val internalBaseDir = filesDir

        val tempDir = File(internalBaseDir, "temp")
        val modsDir = File(internalBaseDir, "mods")
        val stagingDir = File(internalBaseDir, "staging")
        val stateDir = File(externalBaseDir, "state")
        val stateFile = File(stateDir, "installed_mods.json")

        val deployDir = File(externalBaseDir, "deploy_target/Skyrim/Data")
        val deploymentManifestFile = File(externalBaseDir, "state/deployment_manifest.json")
        val gameConfigFile = File(externalBaseDir, "state/game_deployment_configs.json")
        val pluginListFile = File(externalBaseDir, "state/plugins.json")
        val pluginsTxtFile = File(externalBaseDir, "state/plugins.txt")
        val loadorderTxtFile = File(externalBaseDir, "state/loadorder.txt")


        tempDir.mkdirs()
        modsDir.mkdirs()
        stagingDir.mkdirs()
        stateDir.mkdirs()
        deployDir.mkdirs()

        return ModEngine(
            appContext = applicationContext,
            tempDir = tempDir,
            modsDir = modsDir,
            stagingDir = stagingDir,
            stateFile = stateFile,
            deploymentManifestFile = deploymentManifestFile,
            deployRootDir = deployDir,
            gameConfigFile = gameConfigFile,
            pluginListFile = pluginListFile,
            pluginsTxtFile = pluginsTxtFile,
            loadorderTxtFile = loadorderTxtFile
        )
    }
    private fun copyUriToAppFile(uri: Uri, destinationFile: File) {
        contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                throw IllegalStateException("Could not open input stream for selected file.")
            }

            destinationFile.parentFile?.mkdirs()

            destinationFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
    private fun handleImportedArchive(uri: Uri) {
        if (operationInProgress) {
            appendLog("Ignoring import request: operation already in progress.")
            return
        }
        beginOperation("Importing archive...")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Import archive failed: external files directory is null.")
            appendLog("----- Import Archive Workflow End -----")
            return
        }

        val importsDir = File(externalBaseDir, "imports")
        importsDir.mkdirs()

        val fileName = queryDisplayName(uri) ?: "imported_mod"
        val sanitizedName = fileName.replace(Regex("""[^\w.\- ]"""), "_")
        val importedArchive = File(importsDir, sanitizedName)

        try {
            copyUriToAppFile(uri, importedArchive)

            appendLog("Imported file copied to: ${importedArchive.absolutePath}")
            appendLog("Imported file exists: ${importedArchive.exists()}")
            appendLog("Imported file size: ${importedArchive.length()} bytes")
            appendLog("About to install imported archive using engine...")

            val existingMods = engine.getInstalledModsFromFolders()
            val nextPriority = if (existingMods.isEmpty()) 1 else (existingMods.maxOf { it.priority } + 1)

            val prepared = engine.prepareArchiveInstall(importedArchive)

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
            appendLog("Saved installed mod count after import: ${updatedMods.size}")
            appendLog("Plugins refreshed automatically.")
            appendLog("RESULT: PASS")
            finishOperation("Archive imported successfully.")
        } catch (t: Throwable) {
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
            appendLog("Staging changes after delete:")
            appendLog("  Adds: ${result.addCount}")
            appendLog("  Removes: ${result.removeCount}")
            appendLog("  Updates: ${result.updateCount}")

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
                            "This will permanently remove the installed mod folder and update staging."
                )
                .setPositiveButton("Delete") { _, _ ->
                    runInBackground { deleteInstalledMod(mod.id) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    private fun savePickedFolderToSelectedGameConfig(treeUri: String) {
        val engine = createModEngineForWorkflows() ?: return

        val existingConfigs = engine.loadGameDeploymentConfigs().toMutableList()
        val index = existingConfigs.indexOfFirst { it.gameId == selectedGameId }

        val displayName = when (selectedGameId) {
            "skyrim_le" -> "Skyrim Legendary Edition"
            "fallout_nv" -> "Fallout New Vegas"
            else -> selectedGameId
        }

        if (index == -1) {
            val newConfig = GameDeploymentConfig(
                gameId = selectedGameId,
                displayName = displayName,
                targetDataPath = targetPathText.trim(),
                realDeployEnabled = realDeployEnabledState,
                targetTreeUri = treeUri
            )
            existingConfigs.add(newConfig)
            engine.saveGameDeploymentConfigs(existingConfigs)

            runOnUiThread {
                selectedTreeUriText = treeUri
            }

            appendLog("Created config and saved picked folder URI for $selectedGameId")
            return
        }

        val oldConfig = existingConfigs[index]
        val updatedConfig = oldConfig.copy(
            targetDataPath = targetPathText.trim(),
            realDeployEnabled = realDeployEnabledState,
            targetTreeUri = treeUri
        )
        existingConfigs[index] = updatedConfig

        engine.saveGameDeploymentConfigs(existingConfigs)
        saveActiveProfileFromDashboard()
        refreshDashboard()

        runOnUiThread {
            selectedTreeUriText = treeUri
        }

        appendLog("Saved picked folder URI for $selectedGameId")
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
        val engine = createModEngineForWorkflows() ?: return

        try {
            saveSelectedGameConfigFromUi()

            val config = engine.getGameDeploymentConfig(selectedGameId)
            appendLog("Selected game: $selectedGameId")
            appendLog("Active config: $config")

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
                else -> File(getExternalFilesDir(null), "deploy_target/Skyrim/Data").absolutePath
            }

            appendLog("Deploy mode: $effectiveMode")
            appendLog("Deploy target: $effectiveTarget")
            appendLog("Adds: ${result.addCount}")
            appendLog("Removes: ${result.removeCount}")
            appendLog("Updates: ${result.updateCount}")
            appendLog("Final deployed file count: ${result.finalRecordCount}")
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

        appendLog("Plugin scan complete. Plugin count: ${normalized.size}")
    }

    private fun refreshGameOptions() {
        val engine = createModEngineForWorkflows() ?: return
        val configs = engine.loadGameDeploymentConfigs()

        val options = if (configs.isEmpty()) {
            listOf("skyrim_le", "fallout_nv")
        } else {
            configs.map { it.gameId }
        }

        runOnUiThread {
            gameOptions = options
            if (selectedGameId !in options) {
                selectedGameId = options.firstOrNull() ?: "skyrim_le"
            }
        }
    }
    private fun loadSelectedGameConfigIntoUi() {
        val engine = createModEngineForWorkflows() ?: return

        val config = engine.getGameDeploymentConfig(selectedGameId)
        if (config == null) {
            runOnUiThread {
                targetPathText = ""
                realDeployEnabledState = false
                selectedTreeUriText = "No folder selected"
            }
            appendLog("No config found for gameId=$selectedGameId")
            return
        }

        runOnUiThread {
            targetPathText = config.targetDataPath
            realDeployEnabledState = config.realDeployEnabled
            selectedTreeUriText = config.targetTreeUri ?: "No folder selected"
        }

        appendLog("Loaded config into Compose state: $config")
    }

    private fun saveSelectedGameConfigFromUi() {
        val engine = createModEngineForWorkflows() ?: return

        val existingConfigs = engine.loadGameDeploymentConfigs().toMutableList()

        val displayName = when (selectedGameId) {
            "skyrim_le" -> "Skyrim Legendary Edition"
            "fallout_nv" -> "Fallout New Vegas"
            else -> selectedGameId
        }

        val oldConfig = existingConfigs.firstOrNull { it.gameId == selectedGameId }

        val updatedConfig = GameDeploymentConfig(
            gameId = selectedGameId,
            displayName = displayName,
            targetDataPath = targetPathText.trim(),
            realDeployEnabled = realDeployEnabledState,
            targetTreeUri = oldConfig?.targetTreeUri
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
            appendLog("Ignoring load order write request: operation already in progress.")
            return
        }

        beginOperation("Writing load order files...")

        val engine = createModEngineForWorkflows() ?: return

        try {
            syncPluginsFromCurrentState(engine)
            val (pluginsTxt, loadorderTxt) = engine.exportCurrentPluginOutputs()

            appendLog("plugins.txt contents:")
            appendLog(pluginsTxt.ifBlank { "(empty)" })

            appendLog("loadorder.txt contents:")
            appendLog(loadorderTxt.ifBlank { "(empty)" })

            appendLog("RESULT: PASS")
            finishOperation("Load order files written successfully.")
        } catch (e: Exception) {
            appendError("Write load order files workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Writing load order files failed: ${e.message}", e)
        }

        appendLog("----- Write Load Order Files Workflow End -----")
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
            "fallout_nv" -> "Fallout New Vegas"
            "fallout_4" -> "Fallout 4"
            "oblivion" -> "Oblivion"
            else -> gameId
        }
    }

    private fun loadSetupState() {
        val repo = createProfileRepository() ?: return
        val state = repo.loadSetupState()
        val profiles = repo.loadProfiles()
        val activeProfile = profiles.firstOrNull { it.profileId == state.activeProfileId }

        runOnUiThread {
            setupComplete = state.setupComplete
            activeProfileId = state.activeProfileId
            profileOptions = profiles

            if (activeProfile != null) {
                activeProfileName = activeProfile.profileName
                selectedGameId = activeProfile.gameId
                targetPathText = activeProfile.targetDataPath
                selectedTreeUriText = activeProfile.targetTreeUri ?: "No folder selected"
                realDeployEnabledState = activeProfile.realDeployEnabled
            } else if (profiles.isNotEmpty()) {
                val fallback = profiles.first()
                activeProfileId = fallback.profileId
                activeProfileName = fallback.profileName
                selectedGameId = fallback.gameId
                targetPathText = fallback.targetDataPath
                selectedTreeUriText = fallback.targetTreeUri ?: "No folder selected"
                realDeployEnabledState = fallback.realDeployEnabled
            }
        }

        if (state.activeProfileId == null && profiles.isNotEmpty()) {
            val fallback = profiles.first()
            repo.saveSetupState(
                AppSetupState(
                    setupComplete = true,
                    activeProfileId = fallback.profileId
                )
            )
            appendLog("Recovered missing active profile using: ${fallback.profileName}")
        }

        appendLog("Loaded setup state: $state")
        appendLog("Loaded profile count: ${profiles.size}")
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
            targetPathText = profile.targetDataPath
            selectedTreeUriText = profile.targetTreeUri ?: "No folder selected"
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
            targetTreeUri = if (newProfileTreeUriText == "No folder selected") null else newProfileTreeUriText,
            realDeployEnabled = newProfileRealDeployEnabled,
            iniPresetId = null
        )

        val profiles = repo.loadProfiles().toMutableList()
        profiles.add(profile)
        repo.saveProfiles(profiles)

        runOnUiThread {
            profileOptions = profiles
            newProfileNameText = ""
            newProfileTreeUriText = "No folder selected"
            newProfileRealDeployEnabled = false
            showProfileDialog = false
        }

        appendLog("Created additional profile: $profile")
        updateLastOperationStatus("Profile created: ${profile.profileName}")
    }

    private fun switchActiveProfile(profileId: String) {
        val repo = createProfileRepository() ?: return
        val profiles = repo.loadProfiles()
        val profile = profiles.firstOrNull { it.profileId == profileId }

        if (profile == null) {
            appendError("Profile not found: $profileId")
            return
        }

        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profile.profileId
            )
        )

        runOnUiThread {
            activeProfileId = profile.profileId
            activeProfileName = profile.profileName
            selectedGameId = profile.gameId
            targetPathText = profile.targetDataPath
            selectedTreeUriText = profile.targetTreeUri ?: "No folder selected"
            realDeployEnabledState = profile.realDeployEnabled
        }

        saveSelectedGameConfigFromUi()
        appendLog("Switched active profile: $profile")
        updateLastOperationStatus("Switched profile: ${profile.profileName}")
        refreshDashboard()
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
            gameId = selectedGameId,
            gameDisplayName = getGameDisplayName(selectedGameId),
            targetDataPath = targetPathText.trim(),
            targetTreeUri = if (selectedTreeUriText == "No folder selected") null else selectedTreeUriText,
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
                realDeployEnabledState = newActiveProfile.realDeployEnabled
            } else {
                selectedGameId = "skyrim_le"
                targetPathText = ""
                selectedTreeUriText = "No folder selected"
                realDeployEnabledState = false
                showProfileDialog = false
            }
        }

        appendLog("Deleted profile settings only: ${profileToDelete.profileName}")
        updateLastOperationStatus("Deleted profile: ${profileToDelete.profileName}")

        if (newActiveProfile != null) {
            saveSelectedGameConfigFromUi()
        }

        refreshDashboard()
    }

    private fun beginOperation(text: String) {
        runOnUiThread {
            operationInProgress = true
            activeOperationText = text
            updateLastOperationStatus(text)
        }

        showToast(text)
        appendLog("OPERATION START: $text")
    }
    private fun finishOperation(successText: String) {
        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(successText)
        }

        showToast(successText)
        appendLog("OPERATION END: $successText")
    }
    private fun failOperation(message: String, throwable: Throwable? = null) {
        runOnUiThread {
            operationInProgress = false
            activeOperationText = ""
            updateLastOperationStatus(message)
        }

        showToast(message)
        appendError(message, throwable)
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
            appendLog("RESULT: PASS")

            finishOperation("Archive imported successfully.")
            refreshDashboard()
        } catch (t: Throwable) {
            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            failOperation("Installer finalize failed: ${t.message}", t)
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
            val entries = engine.scanOverwriteFiles(selectedGameId)

            runOnUiThread {
                overwriteEntries = entries
                showOverwriteDialog = true
            }

            appendLog("Opened overwrite folder panel. Entry count: ${entries.size}")
        } catch (e: Exception) {
            appendError("Failed to scan overwrite files: ${e.message}", e)
        }
    }
}

