package com.shonkware.droidmodloader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }

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

            runInBackground {
                savePickedFolderToSelectedGameConfig(uri.toString())
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

        initializeComposeUi()
    }

    private fun buildUiState(): DashboardUiState {
        return DashboardUiState(
            appName = "Droid Mod Loader",
            versionLabel = "Version 0.2 Beta",
            developerModeEnabled = developerModeEnabled,
            lastOperationStatus = lastOperationStatus,
            summaryText = summaryText,
            mods = visibleMods,
            plugins = visiblePlugins,
            gameOptions = gameOptions,
            selectedGameId = selectedGameId,
            targetPathText = targetPathText,
            selectedTreeUriText = selectedTreeUriText,
            realDeployEnabled = realDeployEnabledState,
            logText = logText
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
            onTargetPathChanged = { newValue ->
                targetPathText = newValue
            },
            onRealDeployChanged = { enabled ->
                realDeployEnabledState = enabled
            },
            onPickTargetFolder = {
                pickTargetFolderLauncher.launch(null)
            },
            onSaveSettings = {
                runInBackground { saveSelectedGameConfigFromUi() }
            },
            onShareLogs = {
                shareLogs()
            }
        )
    }

    private fun initializeComposeUi() {
        runInBackground {
            refreshGameOptions()
            loadSelectedGameConfigIntoUi()
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
        appendLog("----- Import Archive Workflow Start -----")

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
            val nextPriority = if (existingMods.isEmpty()) 10 else (existingMods.maxOf { it.priority } + 10)

            val installedMod = engine.installArchiveWithRecord(
                archive = importedArchive,
                priority = nextPriority,
                sourceType = "imported_archive"
            )

            appendLog("Archive install returned successfully.")
            val savedMods = engine.saveInstalledModsFromFolders()
            syncPluginsFromCurrentState(engine)
            appendLog("Installed imported mod: $installedMod")
            appendLog("Saved installed mod count after import: ${savedMods.size}")
            appendLog("Plugins refreshed automatically.")
            appendLog("RESULT: PASS")
            updateLastOperationStatus("Import archive succeeded.")
        } catch (t: Throwable) {
            appendError("Import archive workflow failed: ${t.message}", t)
            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Import archive failed: ${t.message}")
        }

        refreshDashboard()
        appendLog("----- Import Archive Workflow End -----")
    }

    private fun normalizePriorities(mods: List<Mod>): List<Mod> {
        return mods.mapIndexed { index, mod ->
            mod.copy(priority = (index + 1) * 10)
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

        runOnUiThread {
            visibleMods = mods
            visiblePlugins = plugins
            summaryText = newSummary
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
        appendLog("----- Deploy Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            saveSelectedGameConfigFromUi()

            val config = engine.getGameDeploymentConfig(selectedGameId)
            appendLog("Selected game: $selectedGameId")
            appendLog("Active config: $config")

            syncPluginsFromCurrentState(engine)

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

            updateLastOperationStatus("Deploy succeeded ($effectiveMode).")
        } catch (e: Exception) {
            appendError("Deploy workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Deploy failed: ${e.message}")
        }

        refreshDashboard()
        appendLog("----- Deploy Workflow End -----")
    }

    private fun syncPluginsFromCurrentState(engine: ModEngine) {
        val previous = engine.loadPlugins().associateBy { it.normalizedPath }
        val discovered = engine.discoverPluginsFromCurrentMods()

        val merged = discovered.mapIndexed { index, plugin ->
            val existing = previous[plugin.normalizedPath]
            if (existing != null) {
                plugin.copy(
                    enabled = existing.enabled,
                    priority = existing.priority
                )
            } else {
                plugin.copy(priority = (index + 1) * 10)
            }
        }

        val normalized = engine.normalizePluginPriorities(merged)
        engine.saveCurrentPlugins(normalized)
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
        appendLog("----- Write Load Order Files Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            syncPluginsFromCurrentState(engine)

            val (pluginsTxt, loadorderTxt) = engine.exportCurrentPluginOutputs()

            appendLog("plugins.txt contents:")
            appendLog(pluginsTxt.ifBlank { "(empty)" })

            appendLog("loadorder.txt contents:")
            appendLog(loadorderTxt.ifBlank { "(empty)" })

            appendLog("RESULT: PASS")
            updateLastOperationStatus("Load order files written successfully.")
        } catch (e: Exception) {
            appendError("Write load order files workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Writing load order files failed: ${e.message}")
        }

        appendLog("----- Write Load Order Files Workflow End -----")
    }
}

