package com.shonkware.droidmodloader

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.build.DiffEngine
import com.shonkware.droidmodloader.engine.build.FileChange
import com.shonkware.droidmodloader.engine.build.StagingManager
import com.shonkware.droidmodloader.engine.conflict.ConflictResolver
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.io.FileScanner
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DroidModLoader"
    }

    private lateinit var logTextView: TextView
    private lateinit var summaryTextView: TextView
    private lateinit var installedModsContainer: LinearLayout
    private lateinit var pluginsContainer: LinearLayout
    private lateinit var developerToolsContainer: LinearLayout

    private lateinit var spinnerGameConfig: Spinner
    private lateinit var editTargetPath: EditText
    private lateinit var checkRealDeployEnabled: CheckBox
    private lateinit var textSelectedTreeUri: TextView

    private var developerTapCount = 0
    private var developerModeEnabled = false
    private var lastOperationStatus = "No operations yet."

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
        setContentView(R.layout.activity_main)

        bindCoreViews()
        setupDeveloperUnlock()
        setupButtonListeners()
        initializeUi()
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            block()
        }.start()
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logTextView.append(message + "\n")
        }
    }

    private fun appendError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }

        runOnUiThread {
            logTextView.append("ERROR: $message\n")
        }
    }

    private fun runSmokeTest() {
        val baseDir = filesDir

        val tempDir = File(baseDir, "temp")
        val modsDir = File(baseDir, "mods")

        val tempCreated = tempDir.mkdirs()
        val modsCreated = modsDir.mkdirs()

        appendLog("Base directory: ${baseDir.absolutePath}")
        appendLog("Temp directory: ${tempDir.absolutePath}")
        appendLog("Mods directory: ${modsDir.absolutePath}")
        appendLog("temp.mkdirs() returned: $tempCreated")
        appendLog("mods.mkdirs() returned: $modsCreated")
        appendLog("Temp exists: ${tempDir.exists()}")
        appendLog("Mods exists: ${modsDir.exists()}")
        appendLog("Smoke test complete")
    }

    private fun runPathUtilsLessonTest() {
        val tests = listOf(
            Pair("Meshes\\Armor\\Iron\\helmet.nif", "meshes/armor/iron/helmet.nif"),
            Pair("meshes/armor/iron/helmet.nif", "meshes/armor/iron/helmet.nif"),
            Pair("./Data/Meshes/Armor/Iron/helmet.nif", "meshes/armor/iron/helmet.nif"),
            Pair("Data\\Meshes\\Armor\\Iron\\helmet.nif", "meshes/armor/iron/helmet.nif"),
            Pair("/meshes//armor///iron/helmet.nif", "meshes/armor/iron/helmet.nif"),
            Pair("__MACOSX/Data/Meshes/Armor/helmet.nif", null),
            Pair("./.hidden/file.txt", null),
            Pair("textures/ui/汉字.dds", "textures/ui/汉字.dds")
        )

        appendLog("----- PathUtils Lesson Test Start -----")

        for (test in tests) {
            val input = test.first
            val expected = test.second
            val actual = PathUtils.normalize(input)
            val passed = actual == expected

            appendLog("INPUT: $input")
            appendLog("EXPECTED: $expected")
            appendLog("ACTUAL: $actual")
            appendLog("RESULT: ${if (passed) "PASS" else "FAIL"}")
            appendLog("--------------------------------------")
        }

        appendLog("----- PathUtils Lesson Test End -----")
    }

    private fun createLooseTestMod(modsDir: File): File {
        val modDir = File(modsDir, "LooseTestMod")

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        File(modDir, "Data/Meshes/Armor/Iron").mkdirs()
        File(modDir, "Data/Textures/UI").mkdirs()
        File(modDir, "Data/Scripts").mkdirs()

        File(modDir, "Data/Meshes/Armor/Iron/helmet.nif")
            .writeText("fake mesh data")

        File(modDir, "Data/Textures/UI/icon.dds")
            .writeText("fake texture data")

        File(modDir, "Data/Scripts/TestScript.psc")
            .writeText("script source code")

        return modDir
    }

    private fun logScanResults(scanner: FileScanner, label: String) {
        val fileMap = scanner.getFileMap()

        appendLog("----- Scan Results for $label -----")
        appendLog("Normalized path count: ${fileMap.size}")

        for ((path, infos) in fileMap) {
            appendLog("PATH: $path")
            for (info in infos) {
                appendLog("  MOD: ${info.sourceMod}, ORIGINAL: ${info.originalPath}, HASH: ${info.hash}")
            }
        }

        appendLog("----- End Scan Results for $label -----")
    }

    private fun runFileScannerLessonTest() {
        appendLog("----- FileScanner Lesson Test Start -----")

        val baseDir = filesDir
        val modsDir = File(baseDir, "mods")
        modsDir.mkdirs()

        val skyUiDir = File(modsDir, "SkyUI")
        val looseTestModDir = createLooseTestMod(modsDir)

        if (!skyUiDir.exists()) {
            appendError("SkyUI mod folder not found: ${skyUiDir.absolutePath}")
            appendLog("Run Chapter 3 extraction test first.")
            appendLog("----- FileScanner Lesson Test End -----")
            return
        }

        try {
            val skyUiScanner = FileScanner()
            skyUiScanner.scanDirectory(skyUiDir, skyUiDir, "SkyUI")
            logScanResults(skyUiScanner, "SkyUI")

            val looseScanner = FileScanner()
            looseScanner.scanDirectory(looseTestModDir, looseTestModDir, "LooseTestMod")
            logScanResults(looseScanner, "LooseTestMod")

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("FileScanner test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- FileScanner Lesson Test End -----")
    }

    private fun detectModType(modDir: File): ModType {
        val allPaths = modDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(modDir).path.lowercase().replace("\\", "/") }
            .toList()

        val hasLooseGameFiles = allPaths.any {
            it.startsWith("data/") ||
                it.startsWith("meshes/") ||
                it.startsWith("textures/") ||
                it.startsWith("scripts/") ||
                it.startsWith("interface/")
        }

        val hasArchiveFiles = allPaths.any {
            it.endsWith(".esp") || it.endsWith(".esm") || it.endsWith(".bsa")
        }

        return when {
            hasLooseGameFiles && hasArchiveFiles -> ModType.MIXED
            hasLooseGameFiles -> ModType.LOOSE
            else -> ModType.ARCHIVE
        }
    }

    private fun convertScannerResultsToModFiles(
        modId: String,
        sourceModName: String,
        fileMap: Map<String, List<com.shonkware.droidmodloader.engine.io.FileInfo>>
    ): List<ModFile> {
        val results = mutableListOf<ModFile>()

        for ((_, infos) in fileMap) {
            for (info in infos) {
                results.add(
                    ModFile(
                        modId = modId,
                        sourceModName = sourceModName,
                        originalPath = info.originalPath,
                        normalizedPath = info.normalizedPath,
                        hash = info.hash
                    )
                )
            }
        }

        return results
    }

    private fun logModelResults(mod: Mod, modFiles: List<ModFile>) {
        appendLog("----- Model Results for ${mod.name} -----")
        appendLog("MOD OBJECT: $mod")
        appendLog("Mod file count: ${modFiles.size}")

        for (modFile in modFiles.take(20)) {
            appendLog("MOD FILE: $modFile")
        }

        appendLog("----- End Model Results for ${mod.name} -----")
    }

    private fun createOverwriteTestMod(modsDir: File): File {
        val modDir = File(modsDir, "OverwriteTestMod")

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        File(modDir, "Data/Textures/UI").mkdirs()
        File(modDir, "Data/Scripts").mkdirs()

        File(modDir, "Data/Textures/UI/icon.dds")
            .writeText("overwrite texture data")

        File(modDir, "Data/Scripts/AnotherScript.psc")
            .writeText("another script source")

        return modDir
    }

    private fun logConflictResults(results: List<FileRecord>, label: String) {
        appendLog("----- Conflict Results for $label -----")
        appendLog("Winning record count: ${results.size}")

        for (record in results.sortedBy { it.normalizedPath }) {
            appendLog("WINNER: $record")
        }

        appendLog("----- End Conflict Results for $label -----")
    }

    private fun runConflictResolverLessonTest() {
        appendLog("----- ConflictResolver Lesson Test Start -----")

        val baseDir = filesDir
        val modsDir = File(baseDir, "mods")
        modsDir.mkdirs()

        val skyUiDir = File(modsDir, "SkyUI")
        val looseTestModDir = File(modsDir, "LooseTestMod")
        val overwriteTestModDir = createOverwriteTestMod(modsDir)

        if (!skyUiDir.exists() || !looseTestModDir.exists()) {
            appendError("Required mod folders are missing.")
            appendLog("Run earlier lessons first.")
            appendLog("----- ConflictResolver Lesson Test End -----")
            return
        }

        try {
            val skyUiMod = Mod(
                id = "SkyUI",
                name = "SkyUI",
                installPath = skyUiDir.absolutePath,
                enabled = true,
                priority = 10,
                modType = detectModType(skyUiDir)
            )

            val looseTestMod = Mod(
                id = "LooseTestMod",
                name = "LooseTestMod",
                installPath = looseTestModDir.absolutePath,
                enabled = true,
                priority = 20,
                modType = detectModType(looseTestModDir)
            )

            val overwriteTestMod = Mod(
                id = "OverwriteTestMod",
                name = "OverwriteTestMod",
                installPath = overwriteTestModDir.absolutePath,
                enabled = true,
                priority = 30,
                modType = detectModType(overwriteTestModDir)
            )

            val mods = listOf(skyUiMod, looseTestMod, overwriteTestMod)

            val allModFiles = mutableListOf<ModFile>()

            val skyUiScanner = FileScanner()
            skyUiScanner.scanDirectory(skyUiDir, skyUiDir, skyUiMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = skyUiMod.id,
                    sourceModName = skyUiMod.name,
                    fileMap = skyUiScanner.getFileMap()
                )
            )

            val looseScanner = FileScanner()
            looseScanner.scanDirectory(looseTestModDir, looseTestModDir, looseTestMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = looseTestMod.id,
                    sourceModName = looseTestMod.name,
                    fileMap = looseScanner.getFileMap()
                )
            )

            val overwriteScanner = FileScanner()
            overwriteScanner.scanDirectory(overwriteTestModDir, overwriteTestModDir, overwriteTestMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = overwriteTestMod.id,
                    sourceModName = overwriteTestMod.name,
                    fileMap = overwriteScanner.getFileMap()
                )
            )

            val resolver = ConflictResolver()
            val results = resolver.resolve(mods, allModFiles)

            logConflictResults(results, "All Mods")

            val iconWinner = results.firstOrNull {
                it.normalizedPath == "textures/ui/icon.dds"
            }

            if (iconWinner == null) {
                appendLog("Expected winner for textures/ui/icon.dds was not found")
                appendLog("RESULT: FAIL")
            } else if (iconWinner.winningModId != "OverwriteTestMod") {
                appendLog("Wrong winner for textures/ui/icon.dds: ${iconWinner.winningModId}")
                appendLog("RESULT: FAIL")
            } else {
                appendLog("Correct winner for textures/ui/icon.dds: ${iconWinner.winningModId}")
                appendLog("RESULT: PASS")
            }

        } catch (e: Exception) {
            appendError("ConflictResolver test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- ConflictResolver Lesson Test End -----")
    }

    private fun logStagingContents(stagingDir: File, label: String) {
        appendLog("----- Staging Contents for $label -----")
        appendLog("Staging exists: ${stagingDir.exists()}")

        val items = if (stagingDir.exists()) stagingDir.walkTopDown().toList() else emptyList()
        appendLog("Staging item count: ${items.size}")

        for (item in items.take(30)) {
            appendLog("STAGING ITEM: ${item.absolutePath}")
        }

        appendLog("----- End Staging Contents for $label -----")
    }

    private fun runStagingManagerLessonTest() {
        appendLog("----- StagingManager Lesson Test Start -----")

        val baseDir = filesDir
        val modsDir = File(baseDir, "mods")
        val stagingDir = File(baseDir, "staging")

        modsDir.mkdirs()
        stagingDir.mkdirs()

        val skyUiDir = File(modsDir, "SkyUI")
        val looseTestModDir = File(modsDir, "LooseTestMod")
        val overwriteTestModDir = createOverwriteTestMod(modsDir)

        if (!skyUiDir.exists() || !looseTestModDir.exists()) {
            appendError("Required mod folders are missing.")
            appendLog("Run earlier lessons first.")
            appendLog("----- StagingManager Lesson Test End -----")
            return
        }

        try {
            val skyUiMod = Mod(
                id = "SkyUI",
                name = "SkyUI",
                installPath = skyUiDir.absolutePath,
                enabled = true,
                priority = 10,
                modType = detectModType(skyUiDir)
            )

            val looseTestMod = Mod(
                id = "LooseTestMod",
                name = "LooseTestMod",
                installPath = looseTestModDir.absolutePath,
                enabled = true,
                priority = 20,
                modType = detectModType(looseTestModDir)
            )

            val overwriteTestMod = Mod(
                id = "OverwriteTestMod",
                name = "OverwriteTestMod",
                installPath = overwriteTestModDir.absolutePath,
                enabled = true,
                priority = 30,
                modType = detectModType(overwriteTestModDir)
            )

            val mods = listOf(skyUiMod, looseTestMod, overwriteTestMod)
            val allModFiles = mutableListOf<ModFile>()

            val skyUiScanner = FileScanner()
            skyUiScanner.scanDirectory(skyUiDir, skyUiDir, skyUiMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = skyUiMod.id,
                    sourceModName = skyUiMod.name,
                    fileMap = skyUiScanner.getFileMap()
                )
            )

            val looseScanner = FileScanner()
            looseScanner.scanDirectory(looseTestModDir, looseTestModDir, looseTestMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = looseTestMod.id,
                    sourceModName = looseTestMod.name,
                    fileMap = looseScanner.getFileMap()
                )
            )

            val overwriteScanner = FileScanner()
            overwriteScanner.scanDirectory(overwriteTestModDir, overwriteTestModDir, overwriteTestMod.name)
            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = overwriteTestMod.id,
                    sourceModName = overwriteTestMod.name,
                    fileMap = overwriteScanner.getFileMap()
                )
            )

            val resolver = ConflictResolver()
            val winningRecords = resolver.resolve(mods, allModFiles)

            val stagingManager = StagingManager(stagingDir)
            stagingManager.rebuildStaging(winningRecords)

            logStagingContents(stagingDir, "Merged Output")

            val stagedIcon = File(stagingDir, "textures/ui/icon.dds")
            if (!stagedIcon.exists()) {
                appendLog("Missing staged file: ${stagedIcon.absolutePath}")
                appendLog("RESULT: FAIL")
                appendLog("----- StagingManager Lesson Test End -----")
                return
            }

            val stagedIconContent = stagedIcon.readText()
            appendLog("Staged icon content: $stagedIconContent")

            if (stagedIconContent != "overwrite texture data") {
                appendLog("Wrong staged content for textures/ui/icon.dds")
                appendLog("RESULT: FAIL")
            } else {
                appendLog("Correct staged content for textures/ui/icon.dds")
                appendLog("RESULT: PASS")
            }

        } catch (e: Exception) {
            appendError("StagingManager test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- StagingManager Lesson Test End -----")
    }

    private fun logDiffResults(changes: List<FileChange>, label: String) {
        appendLog("----- Diff Results for $label -----")
        appendLog("Change count: ${changes.size}")

        for (change in changes) {
            when (change) {
                is FileChange.Add -> appendLog("ADD: ${change.record.normalizedPath}")
                is FileChange.Remove -> appendLog("REMOVE: ${change.normalizedPath}")
                is FileChange.Update -> appendLog(
                    "UPDATE: ${change.newRecord.normalizedPath} " +
                        "(old=${change.oldRecord.winningModId}, new=${change.newRecord.winningModId})"
                )
            }
        }

        appendLog("----- End Diff Results for $label -----")
    }

    private fun createUpdatedOverwriteTestMod(modsDir: File): File {
        val modDir = File(modsDir, "OverwriteTestMod")

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        File(modDir, "Data/Textures/UI").mkdirs()
        File(modDir, "Data/Scripts").mkdirs()

        File(modDir, "Data/Textures/UI/icon.dds")
            .writeText("updated overwrite texture data")

        File(modDir, "Data/Scripts/AnotherScript.psc")
            .writeText("another script source")

        File(modDir, "Data/Scripts/TestScript.psc")
            .writeText("updated overwritten script source")

        return modDir
    }

    private fun runDiffEngineLessonTest() {
        appendLog("----- DiffEngine Lesson Test Start -----")

        val baseDir = filesDir
        val modsDir = File(baseDir, "mods")
        val stagingDir = File(baseDir, "staging")

        modsDir.mkdirs()
        stagingDir.mkdirs()

        val skyUiDir = File(modsDir, "SkyUI")
        val looseTestModDir = File(modsDir, "LooseTestMod")
        val overwriteTestModDir = createOverwriteTestMod(modsDir)

        if (!skyUiDir.exists() || !looseTestModDir.exists()) {
            appendError("Required mod folders are missing.")
            appendLog("Run earlier lessons first.")
            appendLog("----- DiffEngine Lesson Test End -----")
            return
        }

        try {
            val initialMods = listOf(
                Mod(
                    id = "SkyUI",
                    name = "SkyUI",
                    installPath = skyUiDir.absolutePath,
                    enabled = true,
                    priority = 10,
                    modType = detectModType(skyUiDir)
                ),
                Mod(
                    id = "LooseTestMod",
                    name = "LooseTestMod",
                    installPath = looseTestModDir.absolutePath,
                    enabled = true,
                    priority = 20,
                    modType = detectModType(looseTestModDir)
                ),
                Mod(
                    id = "OverwriteTestMod",
                    name = "OverwriteTestMod",
                    installPath = overwriteTestModDir.absolutePath,
                    enabled = true,
                    priority = 30,
                    modType = detectModType(overwriteTestModDir)
                )
            )

            val initialFiles = collectAllModFiles(initialMods)
            val resolver = ConflictResolver()
            val oldRecords = resolver.resolve(initialMods, initialFiles)

            val stagingManager = StagingManager(stagingDir)
            stagingManager.rebuildStaging(oldRecords)

            val updatedOverwriteDir = createUpdatedOverwriteTestMod(modsDir)

            val updatedMods = listOf(
                Mod(
                    id = "SkyUI",
                    name = "SkyUI",
                    installPath = skyUiDir.absolutePath,
                    enabled = true,
                    priority = 10,
                    modType = detectModType(skyUiDir)
                ),
                Mod(
                    id = "LooseTestMod",
                    name = "LooseTestMod",
                    installPath = looseTestModDir.absolutePath,
                    enabled = true,
                    priority = 20,
                    modType = detectModType(looseTestModDir)
                ),
                Mod(
                    id = "OverwriteTestMod",
                    name = "OverwriteTestMod",
                    installPath = updatedOverwriteDir.absolutePath,
                    enabled = true,
                    priority = 30,
                    modType = detectModType(updatedOverwriteDir)
                )
            )

            val newFiles = collectAllModFiles(updatedMods)
            val newRecords = resolver.resolve(updatedMods, newFiles)

            val diffEngine = DiffEngine()
            val changes = diffEngine.diff(oldRecords, newRecords)

            logDiffResults(changes, "Old vs New Winning Records")

            stagingManager.applyChanges(changes)

            val stagedIcon = File(stagingDir, "textures/ui/icon.dds")
            if (!stagedIcon.exists()) {
                appendLog("Missing staged icon after incremental update")
                appendLog("RESULT: FAIL")
                appendLog("----- DiffEngine Lesson Test End -----")
                return
            }

            val stagedIconContent = stagedIcon.readText()
            appendLog("Staged icon content after diff apply: $stagedIconContent")

            if (stagedIconContent != "updated overwrite texture data") {
                appendLog("Incremental update failed for textures/ui/icon.dds")
                appendLog("RESULT: FAIL")
            } else {
                appendLog("Incremental update succeeded for textures/ui/icon.dds")
                appendLog("RESULT: PASS")
            }

        } catch (e: Exception) {
            appendError("DiffEngine test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- DiffEngine Lesson Test End -----")
    }

    private fun collectAllModFiles(mods: List<Mod>): List<ModFile> {
        val allModFiles = mutableListOf<ModFile>()

        for (mod in mods) {
            val modDir = File(mod.installPath)
            val scanner = FileScanner()
            scanner.scanDirectory(modDir, modDir, mod.name)

            allModFiles.addAll(
                convertScannerResultsToModFiles(
                    modId = mod.id,
                    sourceModName = mod.name,
                    fileMap = scanner.getFileMap()
                )
            )
        }

        return allModFiles
    }
    private fun buildInstalledModsFromFolders(modsDir: File): List<Mod> {
        val modDirs = modsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        val mods = mutableListOf<Mod>()
        var priority = 10

        for (modDir in modDirs) {
            mods.add(
                Mod(
                    id = modDir.name,
                    name = modDir.name,
                    installPath = modDir.absolutePath,
                    enabled = true,
                    priority = priority,
                    modType = detectModType(modDir)
                )
            )
            priority += 10
        }

        return mods
    }

    private fun runModStateLessonTest() {
        appendLog("----- Mod State Lesson Test Start -----")

        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            appendLog("----- Mod State Lesson Test End -----")
            return
        }

        val modsDir = File(filesDir, "mods")
        val stateDir = File(externalBaseDir, "state")
        val stateFile = File(stateDir, "installed_mods.json")

        modsDir.mkdirs()
        stateDir.mkdirs()

        createArchiveStyleTestMod(modsDir, "ArchiveStateTestMod")
        createLooseTestMod(modsDir)
        createOverwriteTestMod(modsDir)

        val modsToSave = buildInstalledModsFromFolders(modsDir)

        if (modsToSave.isEmpty()) {
            appendError("No mod folders found to save.")
            appendLog("RESULT: FAIL")
            appendLog("----- Mod State Lesson Test End -----")
            return
        }

        try {
            val repository = ModStateRepository(stateFile)

            repository.saveMods(modsToSave)

            appendLog("Saved mod count: ${modsToSave.size}")
            appendLog("State file absolute path: ${stateFile.absolutePath}")
            appendLog("State file exists: ${stateFile.exists()}")
            appendLog("State file contents:")
            appendLog(stateFile.readText())

            val loadedMods = repository.loadMods()
            appendLog("Loaded mod count: ${loadedMods.size}")

            for (mod in loadedMods) {
                appendLog("LOADED MOD: $mod")
            }

            val sameCount = modsToSave.size == loadedMods.size
            val sameNames = modsToSave.map { it.name } == loadedMods.map { it.name }
            val sameTypes = modsToSave.map { it.modType } == loadedMods.map { it.modType }

            if (sameCount && sameNames && sameTypes) {
                appendLog("Mod state save/load matched expected results.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Mismatch detected between saved and loaded mod state.")
                appendLog("RESULT: FAIL")
            }

        } catch (e: Exception) {
            appendError("Mod state test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Mod State Lesson Test End -----")
    }

    private fun createTestArchiveZip(baseDir: File): File {
        val zipFile = File(baseDir, "SkyUI.zip")

        if (zipFile.exists()) {
            zipFile.delete()
        }

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            addZipEntry(zos, "SkyUI/")
            addZipEntry(zos, "SkyUI/fomod/")
            addZipEntry(zos, "SkyUI/fomod/info.xml", "<fomod><name>SkyUI Test</name></fomod>")
            addZipEntry(zos, "SkyUI/fomod/script.cs", "// fake fomod script")
            addZipEntry(zos, "SkyUI/fomod/screenshot.jpg", "fake screenshot bytes")
            addZipEntry(zos, "SkyUI/SkyUI.bsa", "fake bsa data")
            addZipEntry(zos, "SkyUI/SkyUI.esp", "fake esp data")
        }

        return zipFile
    }
    private fun addZipEntry(zos: ZipOutputStream, entryName: String, content: String? = null) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)

        if (content != null) {
            zos.write(content.toByteArray())
        }

        zos.closeEntry()
    }

    private fun createArchiveStyleTestMod(modsDir: File, modName: String = "ArchiveStateTestMod"): File {
        val modDir = File(modsDir, modName)

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        File(modDir, "fomod").mkdirs()

        File(modDir, "fomod/info.xml")
            .writeText("<fomod><name>$modName</name></fomod>")

        File(modDir, "fomod/script.cs")
            .writeText("// fake fomod script")

        File(modDir, "fomod/screenshot.jpg")
            .writeText("fake screenshot bytes")

        File(modDir, "$modName.bsa")
            .writeText("fake bsa data")

        File(modDir, "$modName.esp")
            .writeText("fake esp data")

        return modDir
    }

    private fun runModEngineLessonTest() {
        appendLog("----- ModEngine Lesson Test Start -----")

        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            appendLog("----- ModEngine Lesson Test End -----")
            return
        }

        val internalBaseDir = filesDir

        val testRoot = File(internalBaseDir, "lesson_tests/mod_engine")
        val externalTestRoot = File(externalBaseDir, "lesson_tests/mod_engine")

        val tempDir = File(testRoot, "temp")
        val modsDir = File(testRoot, "mods")
        val stagingDir = File(testRoot, "staging")
        val archiveDir = File(testRoot, "archives")

        val stateDir = File(externalTestRoot, "state")
        val stateFile = File(stateDir, "installed_mods.json")

        val deploymentManifestFile = File(externalBaseDir, "state/deployment_manifest.json")
        val deployRootDir = File(externalBaseDir, "deploy_target/Skyrim/Data")
        val gameConfigFile = File(externalBaseDir, "state/game_deployment_configs.json")
        val pluginListFile = File(externalBaseDir, "state/plugins.json")
        val pluginsTxtFile = File(externalBaseDir, "state/plugins.txt")
        val loadorderTxtFile = File(externalBaseDir, "state/loadorder.txt")

        fun ensureDir(dir: File) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Could not create directory: ${dir.absolutePath}")
            }

            if (!dir.isDirectory) {
                throw IllegalStateException("Path exists but is not a directory: ${dir.absolutePath}")
            }
        }

        try {
            appendLog("Cleaning previous ModEngine lesson test files...")

            if (testRoot.exists() && !testRoot.deleteRecursively()) {
                throw IllegalStateException("Could not delete old test root: ${testRoot.absolutePath}")
            }

            if (externalTestRoot.exists() && !externalTestRoot.deleteRecursively()) {
                throw IllegalStateException("Could not delete old external test root: ${externalTestRoot.absolutePath}")
            }

            ensureDir(tempDir)
            ensureDir(modsDir)
            ensureDir(stagingDir)
            ensureDir(archiveDir)
            ensureDir(stateDir)

            appendLog("Test root: ${testRoot.absolutePath}")
            appendLog("External test root: ${externalTestRoot.absolutePath}")
            appendLog("Temp dir: ${tempDir.absolutePath}")
            appendLog("Mods dir: ${modsDir.absolutePath}")
            appendLog("Staging dir: ${stagingDir.absolutePath}")
            appendLog("State file: ${stateFile.absolutePath}")

            val engine = ModEngine(
                tempDir = tempDir,
                modsDir = modsDir,
                stagingDir = stagingDir,
                stateFile = stateFile,
                deploymentManifestFile = deploymentManifestFile,
                deployRootDir = deployRootDir,
                gameConfigFile = gameConfigFile,
                appContext = applicationContext,
                pluginListFile = pluginListFile,
                pluginsTxtFile = pluginsTxtFile,
                loadorderTxtFile = loadorderTxtFile
            )

            val archive = createTestArchiveZip(archiveDir)
            appendLog("Created test archive: ${archive.absolutePath}")
            appendLog("Archive exists: ${archive.exists()}")
            appendLog("Archive size: ${archive.length()} bytes")

            val archiveMod = engine.installArchive(archive, priority = 10)
            appendLog("Installed archive mod through ModEngine:")
            appendLog("$archiveMod")

            val looseDir = createLooseTestMod(modsDir)
            appendLog("Created loose test mod folder: ${looseDir.absolutePath}")

            val overwriteDir = createOverwriteTestMod(modsDir)
            appendLog("Created overwrite test mod folder: ${overwriteDir.absolutePath}")

            val looseMod = engine.buildModFromInstalledFolder(looseDir, priority = 20)
            appendLog("Built loose mod through ModEngine:")
            appendLog("$looseMod")

            val overwriteMod = engine.buildModFromInstalledFolder(overwriteDir, priority = 30)
            appendLog("Built overwrite mod through ModEngine:")
            appendLog("$overwriteMod")

            val mods = listOf(archiveMod, looseMod, overwriteMod)

            appendLog("Saving ${mods.size} mods through ModEngine...")
            engine.saveMods(mods)

            appendLog("State file exists after save: ${stateFile.exists()}")
            appendLog("State file size after save: ${stateFile.length()} bytes")

            val loadedMods = engine.loadMods()
            appendLog("Loaded mods through ModEngine: ${loadedMods.size}")

            for (mod in loadedMods) {
                appendLog("ENGINE LOADED MOD: $mod")
            }

            if (loadedMods.size != 3) {
                appendError("Expected 3 loaded mods, but got ${loadedMods.size}")
                appendLog("RESULT: FAIL")
                appendLog("----- ModEngine Lesson Test End -----")
                return
            }

            appendLog("Rebuilding staging through ModEngine...")

            if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
                throw IllegalStateException("Could not clean staging before rebuild: ${stagingDir.absolutePath}")
            }

            ensureDir(stagingDir)

            val winningRecords = engine.rebuildStaging(loadedMods)
            appendLog("Winning record count from ModEngine: ${winningRecords.size}")

            val stagedIcon = File(stagingDir, "textures/ui/icon.dds")
            appendLog("Expected staged icon path: ${stagedIcon.absolutePath}")
            appendLog("Staged icon exists: ${stagedIcon.exists()}")

            if (!stagedIcon.exists()) {
                appendError("Staged icon not found after engine rebuild.")
                appendLog("RESULT: FAIL")
                appendLog("----- ModEngine Lesson Test End -----")
                return
            }

            val stagedIconContent = stagedIcon.readText()
            appendLog("Engine staged icon content: $stagedIconContent")

            if (stagedIconContent != "overwrite texture data") {
                appendError("Engine staging produced wrong winner for textures/ui/icon.dds")
                appendError("Expected: overwrite texture data")
                appendError("Actual: $stagedIconContent")
                appendLog("RESULT: FAIL")
            } else {
                appendLog("Engine staging produced correct winner for textures/ui/icon.dds")
                appendLog("State file path: ${stateFile.absolutePath}")
                appendLog("State file exists: ${stateFile.exists()}")

                if (stateFile.exists()) {
                    appendLog("State file contents:")
                    appendLog(stateFile.readText())
                }

                appendLog("RESULT: PASS")
            }

        } catch (e: Exception) {
            appendError("ModEngine test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- ModEngine Lesson Test End -----")
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

    private fun runInstallArchiveWorkflow() {
        appendLog("----- Install Archive Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val internalBaseDir = filesDir

        try {
            val archive = createTestArchiveZip(internalBaseDir)
            val mod = engine.installArchiveWithRecord(
                archive = archive,
                priority = 10,
                sourceType = "generated_archive"
            )

            appendLog("Installed archive mod: $mod")
            appendLog("RESULT: PASS")
            updateLastOperationStatus("Install archive succeeded.")
        } catch (e: Exception) {
            appendError("Install archive workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Install archive failed: ${e.message}")
        }

        refreshDashboard()
        appendLog("----- Install Archive Workflow End -----")
    }

    private fun runInstallLooseWorkflow() {
        appendLog("----- Install Loose Mods Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val modsDir = File(filesDir, "mods")

        try {
            val looseDir = createLooseTestMod(modsDir)
            val overwriteDir = createOverwriteTestMod(modsDir)

            val looseMod = engine.registerExistingInstalledFolderWithRecord(
                modDir = looseDir,
                priority = 20,
                sourceType = "generated_loose"
            )

            val overwriteMod = engine.registerExistingInstalledFolderWithRecord(
                modDir = overwriteDir,
                priority = 30,
                sourceType = "generated_loose"
            )

            appendLog("Installed/generated loose mod: $looseMod")
            appendLog("Installed/generated loose mod: $overwriteMod")
            appendLog("RESULT: PASS")
            updateLastOperationStatus("Install loose mods succeeded.")
        } catch (e: Exception) {
            appendError("Install loose workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Install loose mods failed: ${e.message}")
        }

        refreshDashboard()
        appendLog("----- Install Loose Mods Workflow End -----")
    }

    private fun runListInstalledModsWorkflow() {
        appendLog("----- List Installed Mods Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val mods = engine.getInstalledModsFromFolders()

            appendLog("Installed mod count: ${mods.size}")
            for (mod in mods) {
                appendLog("INSTALLED MOD: $mod")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("List installed mods workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- List Installed Mods Workflow End -----")
    }

    private fun runSaveInstalledModsWorkflow() {
        appendLog("----- Save Installed Mods Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            appendLog("----- Save Installed Mods Workflow End -----")
            return
        }

        val stateFile = File(File(externalBaseDir, "state"), "installed_mods.json")

        try {
            val savedMods = engine.saveInstalledModsFromFolders()

            appendLog("Saved installed mod count: ${savedMods.size}")
            appendLog("State file path: ${stateFile.absolutePath}")
            appendLog("State file exists: ${stateFile.exists()}")

            if (stateFile.exists()) {
                appendLog("State file contents:")
                appendLog(stateFile.readText())
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Save installed mods workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshDashboard()

        appendLog("----- Save Installed Mods Workflow End -----")
    }

    private fun runLoadSavedModsWorkflow() {
        appendLog("----- Load Saved Mods Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val loadedMods = engine.loadMods()

            appendLog("Loaded mod count: ${loadedMods.size}")
            for (mod in loadedMods) {
                appendLog("LOADED MOD: $mod")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Load saved mods workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshDashboard()

        appendLog("----- Load Saved Mods Workflow End -----")
    }

    private fun runRebuildInstalledStagingWorkflow() {
        appendLog("----- Rebuild Staging From Current State Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val stagingDir = File(filesDir, "staging")

        try {
            val records = engine.rebuildStagingFromCurrentState()

            appendLog("Winning record count: ${records.size}")

            val stagedIcon = File(stagingDir, "textures/ui/icon.dds")
            appendLog("Staged icon exists: ${stagedIcon.exists()}")

            if (stagedIcon.exists()) {
                appendLog("Staged icon content: ${stagedIcon.readText()}")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Rebuild staging workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Rebuild Staging From Current State Workflow End -----")
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
        appendLog("----- Import ZIP Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            appendLog("----- Import ZIP Workflow End -----")
            return
        }

        val importsDir = File(externalBaseDir, "imports")
        importsDir.mkdirs()

        val importedArchive = File(importsDir, "imported_mod.zip")


        try {
            copyUriToAppFile(uri, importedArchive)

            appendLog("Imported file copied to: ${importedArchive.absolutePath}")
            appendLog("Imported file exists: ${importedArchive.exists()}")
            appendLog("Imported file size: ${importedArchive.length()} bytes")

            val existingMods = engine.getInstalledModsFromFolders()
            val nextPriority = if (existingMods.isEmpty()) 10 else (existingMods.maxOf { it.priority } + 10)

            val installedMod = engine.installArchiveWithRecord(
                archive = importedArchive,
                priority = nextPriority,
                sourceType = "imported_zip"
            )

            val savedMods = engine.saveInstalledModsFromFolders()
            appendLog("Saved installed mod count after import: ${savedMods.size}")

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Import ZIP workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshDashboard()

        appendLog("----- Import ZIP Workflow End -----")
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
        refreshDashboard()
    }

    private fun createModRow(mod: Mod): LinearLayout {

        val engine = createModEngineForWorkflows()
        val record = engine?.loadInstalledModRecord(mod)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val installedSource = record?.sourceType ?: "unknown"
        val archiveName = record?.sourceArchiveName ?: "none"
        val installedAt = record?.installedAtEpochMillis ?: 0L

        val infoText = TextView(this).apply {
            text = buildString {
                appendLine("Priority ${mod.priority} | ${mod.name} | ${mod.modType} | ${if (mod.enabled) "ENABLED" else "DISABLED"}")
                appendLine("Source: $installedSource")
                append("Archive: $archiveName")
                if (installedAt > 0L) {
                    append(" | InstalledAt: $installedAt")
                }
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val toggleButton = Button(this).apply {
            text = if (mod.enabled) "Disable" else "Enable"
            setOnClickListener {
                runInBackground { toggleModEnabled(mod.id) }
            }
        }

        val upButton = Button(this).apply {
            text = "Up"
            setOnClickListener {
                runInBackground { moveModUp(mod.id) }
            }
        }

        val downButton = Button(this).apply {
            text = "Down"
            setOnClickListener {
                runInBackground { moveModDown(mod.id) }
            }
        }

        val deleteButton = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                showDeleteConfirmDialog(mod)
            }
        }

        buttonRow.addView(toggleButton)
        buttonRow.addView(upButton)
        buttonRow.addView(downButton)
        buttonRow.addView(deleteButton)

        row.addView(infoText)
        row.addView(buttonRow)

        return row
    }

    private fun refreshInstalledModsPanel() {
        val engine = createModEngineForWorkflows() ?: return
        val mods = engine.getCurrentMods().sortedBy { it.priority }

        runOnUiThread {
            installedModsContainer.removeAllViews()

            if (mods.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = "No installed mods found."
                }
                installedModsContainer.addView(emptyText)
                return@runOnUiThread
            }

            for (mod in mods) {
                installedModsContainer.addView(createModRow(mod))
            }
        }
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

    private fun runDeleteModLessonTest() {
        appendLog("----- Delete Mod Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val stagingDir = File(filesDir, "staging")

        try {
            val modsBefore = engine.getCurrentMods().sortedBy { it.priority }
            appendLog("Current mod count before delete: ${modsBefore.size}")
            for (mod in modsBefore) {
                appendLog("BEFORE DELETE MOD: $mod")
            }

            val overwriteMod = modsBefore.firstOrNull { it.id == "OverwriteTestMod" }
            if (overwriteMod == null) {
                appendError("OverwriteTestMod not found. Install the test mods first.")
                appendLog("RESULT: FAIL")
                appendLog("----- Delete Mod Lesson Test End -----")
                return
            }

            val result = engine.uninstallModAndApplyDiff("OverwriteTestMod")
            if (!result.removed) {
                appendError("Failed to uninstall OverwriteTestMod")
                appendLog("RESULT: FAIL")
                appendLog("----- Delete Mod Lesson Test End -----")
                return
            }

            appendLog("Delete diff stats:")
            appendLog("  Adds: ${result.addCount}")
            appendLog("  Removes: ${result.removeCount}")
            appendLog("  Updates: ${result.updateCount}")

            val modsAfter = engine.getCurrentMods().sortedBy { it.priority }
            appendLog("Current mod count after delete: ${modsAfter.size}")
            for (mod in modsAfter) {
                appendLog("AFTER DELETE MOD: $mod")
            }

            val stagedIcon = File(stagingDir, "textures/ui/icon.dds")
            if (!stagedIcon.exists()) {
                appendError("Staged icon missing after delete")
                appendLog("RESULT: FAIL")
                appendLog("----- Delete Mod Lesson Test End -----")
                return
            }

            val stagedIconContent = stagedIcon.readText()
            appendLog("Staged icon content after delete: $stagedIconContent")

            if (stagedIconContent != "fake texture data") {
                appendError("Delete fallback failed. Expected LooseTestMod to win.")
                appendLog("RESULT: FAIL")
            } else {
                appendLog("Delete fallback succeeded. LooseTestMod now wins.")
                appendLog("RESULT: PASS")
            }

            refreshDashboard()
        } catch (e: Exception) {
            appendError("Delete mod lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Delete Mod Lesson Test End -----")
    }

    private fun getImportsDir(): File? {
        val externalBaseDir = getExternalFilesDir(null) ?: return null
        return File(externalBaseDir, "imports")
    }

    private fun runResetAppDataWorkflow() {
        appendLog("----- Reset App Data Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val importsDir = getImportsDir()

        if (importsDir == null) {
            appendError("Imports directory could not be created.")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Reset app data failed: imports directory could not be created.")
            appendLog("----- Reset App Data Workflow End -----")
            return
        }

        try {
            appendLog("Reset will remove mods, staging, temp, imports, and saved state.")

            val success = engine.resetAllAppData(importsDir)

            if (!success) {
                appendError("Engine reset failed.")
                appendLog("RESULT: FAIL")
                updateLastOperationStatus("Reset app data failed: engine reset failed.")
                appendLog("----- Reset App Data Workflow End -----")
                return
            }

            appendLog("App data reset completed.")
            appendLog("Mods dir exists after reset: ${File(filesDir, "mods").exists()}")
            appendLog("Staging dir exists after reset: ${File(filesDir, "staging").exists()}")

            val externalBaseDir = getExternalFilesDir(null)
            if (externalBaseDir != null) {
                val stateFile = File(File(externalBaseDir, "state"), "installed_mods.json")
                appendLog("State file exists after reset: ${stateFile.exists()}")
            }

            refreshDashboard()
            appendLog("Installed mods panel refreshed.")
            appendLog("RESULT: PASS")
            updateLastOperationStatus("Reset app data succeeded.")
        } catch (e: Exception) {
            appendError("Reset app data workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Reset app data failed: ${e.message}")
        }

        appendLog("----- Reset App Data Workflow End -----")
    }

    private fun refreshSummaryPanel() {
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

        val summaryText = buildString {
            appendLine("Installed mods: $installedCount")
            appendLine("Enabled mods: $enabledCount")
            appendLine("Plugins: ${plugins.size}")
            appendLine("State source: $stateSourceText")
            appendLine("Highest priority mod: $highestPriorityMod")
            appendLine("Last operation: $lastOperationStatus")
        }

        runOnUiThread {
            summaryTextView.text = summaryText
        }
    }

    private fun refreshDashboard() {
        refreshSummaryPanel()
        refreshInstalledModsPanel()
        refreshPluginsPanel()
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

    private fun runInstalledRecordLessonTest() {
        appendLog("----- Installed Record Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runInstallArchiveWorkflow()
            runInstallLooseWorkflow()

            val mods = engine.getCurrentMods().sortedBy { it.priority }
            appendLog("Current mod count: ${mods.size}")

            for (mod in mods) {
                val record = engine.loadInstalledModRecord(mod)
                appendLog("MOD: $mod")
                appendLog("RECORD: $record")
            }

            val recordsLoaded = mods.count { engine.loadInstalledModRecord(it) != null }

            if (recordsLoaded == mods.size && mods.isNotEmpty()) {
                appendLog("All installed mods have metadata records.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Some installed mods are missing metadata records.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Installed record lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshDashboard()
        appendLog("----- Installed Record Lesson Test End -----")
    }

    private fun createDeployScopeTestMod(modsDir: File): File {
        val modDir = File(modsDir, "DeployScopeTestMod")

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        File(modDir, "Data/Textures/UI").mkdirs()
        File(modDir, "fomod").mkdirs()
        File(modDir, "Docs").mkdirs()

        File(modDir, "Data/Textures/UI/scope_test.dds")
            .writeText("deploy this texture")

        File(modDir, "fomod/info.xml")
            .writeText("<fomod><name>DeployScopeTestMod</name></fomod>")

        File(modDir, "readme.txt")
            .writeText("do not deploy this")

        File(modDir, "plugins.txt")
            .writeText("*DeployScopeTestMod.esp")

        File(modDir, "d3d11.dll")
            .writeText("root file placeholder")

        return modDir
    }
    private fun runDeployScopeLessonTest() {
        appendLog("----- Deploy Scope Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val modsDir = File(filesDir, "mods")
        val stagingDir = File(filesDir, "staging")

        try {
            val testDir = createDeployScopeTestMod(modsDir)

            val mod = engine.registerExistingInstalledFolderWithRecord(
                modDir = testDir,
                priority = 10,
                sourceType = "generated_loose"
            )

            engine.saveCurrentMods(listOf(mod))

            val scannedFiles = engine.scanMod(mod)
            appendLog("Scanned file count: ${scannedFiles.size}")

            val classified = engine.classifyModFiles(scannedFiles)

            for (scope in DeployScope.values()) {
                val filesForScope = classified[scope].orEmpty()
                appendLog("$scope count: ${filesForScope.size}")
                for (file in filesForScope) {
                    appendLog("  $scope -> ${file.normalizedPath}")
                }
            }

            val winningRecords = engine.rebuildStagingFromCurrentState()
            appendLog("Winning record count: ${winningRecords.size}")

            val stagedDataFile = File(stagingDir, "textures/ui/scope_test.dds")
            val stagedFomodFile = File(stagingDir, "fomod/info.xml")
            val stagedReadme = File(stagingDir, "readme.txt")
            val stagedPluginsTxt = File(stagingDir, "plugins.txt")
            val stagedRootDll = File(stagingDir, "d3d11.dll")

            appendLog("stagedDataFile exists: ${stagedDataFile.exists()}")
            appendLog("stagedFomodFile exists: ${stagedFomodFile.exists()}")
            appendLog("stagedReadme exists: ${stagedReadme.exists()}")
            appendLog("stagedPluginsTxt exists: ${stagedPluginsTxt.exists()}")
            appendLog("stagedRootDll exists: ${stagedRootDll.exists()}")

            val passed =
                stagedDataFile.exists() &&
                        !stagedFomodFile.exists() &&
                        !stagedReadme.exists() &&
                        !stagedPluginsTxt.exists() &&
                        !stagedRootDll.exists()

            if (passed) {
                appendLog("Deploy scope filtering worked.")
                appendLog("RESULT: PASS")
                updateLastOperationStatus("Install archive succeeded.")
            } else {
                appendLog("Deploy scope filtering failed.")
                appendLog("RESULT: FAIL")
            }

        } catch (e: Exception) {
            appendError("Deploy scope lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")

        }

        refreshDashboard()
        appendLog("----- Deploy Scope Lesson Test End -----")
    }

    private fun runDeployCurrentStateWorkflow() {
        appendLog("----- Deploy Current State Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Deploy current state failed: external files directory is null.")
            appendLog("----- Deploy Current State Workflow End -----")
            return
        }

        val deployRootDir = File(externalBaseDir, "deploy_target/Skyrim/Data")
        val manifestFile = File(externalBaseDir, "state/deployment_manifest.json")

        try {
            val result = engine.deployCurrentState()

            appendLog("Deploy target: ${deployRootDir.absolutePath}")
            appendLog("Deployment manifest: ${manifestFile.absolutePath}")
            appendLog("Manifest exists: ${manifestFile.exists()}")
            appendLog("Adds: ${result.addCount}")
            appendLog("Removes: ${result.removeCount}")
            appendLog("Updates: ${result.updateCount}")
            appendLog("Final deployed file count: ${result.finalRecordCount}")

            val deployedItems = if (deployRootDir.exists()) deployRootDir.walkTopDown().toList() else emptyList()
            appendLog("Deploy target item count: ${deployedItems.size}")

            for (item in deployedItems.take(25)) {
                appendLog("DEPLOY ITEM: ${item.absolutePath}")
            }

            appendLog("RESULT: PASS")
            updateLastOperationStatus("Deploy current state succeeded.")
        } catch (e: Exception) {
            appendError("Deploy current state workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Deploy current state failed: ${e.message}")
        }

        appendLog("----- Deploy Current State Workflow End -----")
    }

    private fun runDeploymentManifestLessonTest() {
        appendLog("----- Deployment Manifest Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runInstallArchiveWorkflow()
            runInstallLooseWorkflow()
            runSaveInstalledModsWorkflow()

            val firstDeploy = engine.deployCurrentState()
            appendLog("First deploy -> Adds: ${firstDeploy.addCount}, Removes: ${firstDeploy.removeCount}, Updates: ${firstDeploy.updateCount}")

            val secondDeploy = engine.deployCurrentState()
            appendLog("Second deploy -> Adds: ${secondDeploy.addCount}, Removes: ${secondDeploy.removeCount}, Updates: ${secondDeploy.updateCount}")

            val passed =
                secondDeploy.addCount == 0 &&
                        secondDeploy.removeCount == 0 &&
                        secondDeploy.updateCount == 0

            if (passed) {
                appendLog("Second deploy correctly detected no changes.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Second deploy unexpectedly changed files.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Deployment manifest lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Deployment Manifest Lesson Test End -----")
    }

    private fun createDefaultGameConfigs(): List<GameDeploymentConfig> {
        val externalBaseDir = getExternalFilesDir(null) ?: return emptyList()

        val skyrimPath = File(externalBaseDir, "shared_game/Skyrim/Data")
        val falloutNvPath = File(externalBaseDir, "shared_game/FalloutNV/Data")
        skyrimPath.mkdirs()
        falloutNvPath.mkdirs()

        return listOf(
            GameDeploymentConfig(
                gameId = "skyrim_le",
                displayName = "Skyrim Legendary Edition",
                targetDataPath = skyrimPath.absolutePath,
                realDeployEnabled = false,
                targetTreeUri = null
            ),
            GameDeploymentConfig(
                gameId = "fallout_nv",
                displayName = "Fallout New Vegas",
                targetDataPath = falloutNvPath.absolutePath,
                realDeployEnabled = false,
                targetTreeUri = null
            )
        )
    }

    private fun runSaveGameConfigWorkflow() {
        appendLog("----- Save Game Config Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val configs = createDefaultGameConfigs()
            engine.saveGameDeploymentConfigs(configs)

            appendLog("Saved game config count: ${configs.size}")
            for (config in configs) {
                appendLog("CONFIG: $config")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Save game config workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Save Game Config Workflow End -----")
    }

    private fun runLoadGameConfigWorkflow() {
        appendLog("----- Load Game Config Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val configs = engine.loadGameDeploymentConfigs()
            appendLog("Loaded game config count: ${configs.size}")
            for (config in configs) {
                appendLog("CONFIG: $config")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Load game config workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Load Game Config Workflow End -----")
    }

    private fun runDeploySkyrimWorkflow() {
        appendLog("----- Deploy Skyrim Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Deploy Skyrim failed: external files directory is null.")
            appendLog("----- Deploy Skyrim Workflow End -----")
            return
        }

        val config = engine.getGameDeploymentConfig("skyrim_le")
        appendLog("Skyrim config: $config")

        try {
            val result = engine.deployForGame("skyrim_le")

            val usingTreeUri = config != null &&
                    config.realDeployEnabled &&
                    !config.targetTreeUri.isNullOrBlank()

            val usingRealPathTarget = config != null &&
                    config.realDeployEnabled &&
                    engine.validateTargetDataPath(config.targetDataPath)

            appendLog("Using tree URI target: $usingTreeUri")
            appendLog("Using real path target: $usingRealPathTarget")

            val effectiveTarget = when {
                usingTreeUri -> "TREE_URI:${config!!.targetTreeUri}"
                usingRealPathTarget -> config!!.targetDataPath
                else -> File(externalBaseDir, "deploy_target/Skyrim/Data").absolutePath
            }

            appendLog("Effective deploy target: $effectiveTarget")
            appendLog("Adds: ${result.addCount}")
            appendLog("Removes: ${result.removeCount}")
            appendLog("Updates: ${result.updateCount}")
            appendLog("Final deployed file count: ${result.finalRecordCount}")
            appendLog("RESULT: PASS")
            updateLastOperationStatus("Deploy Skyrim succeeded.")
        } catch (e: Exception) {
            appendError("Deploy Skyrim workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Deploy Skyrim failed: ${e.message}")
        }

        appendLog("----- Deploy Skyrim Workflow End -----")
    }

    private fun runGameTargetLessonTest() {
        appendLog("----- Game Target Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runSaveGameConfigWorkflow()
            runInstallArchiveWorkflow()
            runInstallLooseWorkflow()
            runSaveInstalledModsWorkflow()

            val config = engine.getGameDeploymentConfig("skyrim_le")
            appendLog("Loaded Skyrim config: $config")

            val configValid = config != null && engine.validateTargetDataPath(config.targetDataPath)
            appendLog("Skyrim target path valid: $configValid")

            val deployResult = engine.deployForGame("skyrim_le")
            appendLog("Deploy result -> Adds: ${deployResult.addCount}, Removes: ${deployResult.removeCount}, Updates: ${deployResult.updateCount}")

            val usedRealTarget = config != null &&
                    config.realDeployEnabled &&
                    configValid

            if (!usedRealTarget) {
                appendLog("Using simulated deploy target (expected for this test).")
            }

            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Game target lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Game Target Lesson Test End -----")
    }

    private fun refreshGameConfigSpinner() {
        val engine = createModEngineForWorkflows() ?: return
        val configs = engine.loadGameDeploymentConfigs()

        val labels = if (configs.isEmpty()) {
            listOf("skyrim_le", "fallout_nv")
        } else {
            configs.map { it.gameId }
        }

        runOnUiThread {
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                labels
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerGameConfig.adapter = adapter
        }
    }

    private fun loadSelectedGameConfigIntoForm() {
        val engine = createModEngineForWorkflows() ?: return
        val selectedGameId = spinnerGameConfig.selectedItem?.toString() ?: return

        val config = engine.getGameDeploymentConfig(selectedGameId)
        if (config == null) {
            appendError("No config found for gameId=$selectedGameId")
            return
        }

        runOnUiThread {
            editTargetPath.setText(config.targetDataPath)
            checkRealDeployEnabled.isChecked = config.realDeployEnabled
            textSelectedTreeUri.text = "Selected folder URI: ${config.targetTreeUri ?: "none"}"
        }



        appendLog("Loaded config into form: $config")
    }

    private fun saveSelectedGameConfigFromForm() {
        val engine = createModEngineForWorkflows() ?: return
        val selectedGameId = spinnerGameConfig.selectedItem?.toString() ?: return

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
            targetDataPath = editTargetPath.text.toString().trim(),
            realDeployEnabled = checkRealDeployEnabled.isChecked,
            targetTreeUri = oldConfig?.targetTreeUri
        )

        val index = existingConfigs.indexOfFirst { it.gameId == selectedGameId }
        if (index >= 0) {
            existingConfigs[index] = updatedConfig
        } else {
            existingConfigs.add(updatedConfig)
        }

        engine.saveGameDeploymentConfigs(existingConfigs)
        appendLog("Saved updated config: $updatedConfig")
    }

    private fun runGameConfigEditorLessonTest() {
        appendLog("----- Game Config Editor Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runSaveGameConfigWorkflow()

            val original = engine.getGameDeploymentConfig("skyrim_le")
            appendLog("Original Skyrim config: $original")

            val configs = engine.loadGameDeploymentConfigs().toMutableList()
            val index = configs.indexOfFirst { it.gameId == "skyrim_le" }

            if (index == -1) {
                appendError("Could not find Skyrim config to edit.")
                appendLog("RESULT: FAIL")
                appendLog("----- Game Config Editor Lesson Test End -----")
                return
            }

            val edited = configs[index].copy(
                targetDataPath = configs[index].targetDataPath + "_edited",
                realDeployEnabled = true
            )
            configs[index] = edited

            engine.saveGameDeploymentConfigs(configs)

            val reloaded = engine.getGameDeploymentConfig("skyrim_le")
            appendLog("Reloaded Skyrim config: $reloaded")

            val passed = reloaded != null &&
                    reloaded.targetDataPath.endsWith("_edited") &&
                    reloaded.realDeployEnabled

            if (passed) {
                appendLog("Game config editing persistence worked.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Game config editing persistence failed.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Game config editor lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        runInBackground { refreshGameConfigSpinner() }
        appendLog("----- Game Config Editor Lesson Test End -----")
    }

    private fun savePickedFolderToSelectedGameConfig(treeUri: String) {
        val engine = createModEngineForWorkflows() ?: return
        val selectedGameId = spinnerGameConfig.selectedItem?.toString() ?: return

        val existingConfigs = engine.loadGameDeploymentConfigs().toMutableList()
        val index = existingConfigs.indexOfFirst { it.gameId == selectedGameId }

        if (index == -1) {
            appendError("No config found for selected game: $selectedGameId")
            return
        }

        val oldConfig = existingConfigs[index]
        val updatedConfig = oldConfig.copy(targetTreeUri = treeUri)
        existingConfigs[index] = updatedConfig

        engine.saveGameDeploymentConfigs(existingConfigs)

        runOnUiThread {
            textSelectedTreeUri.text = "Selected folder URI: $treeUri"
        }

        appendLog("Saved picked folder URI for $selectedGameId")
        loadSelectedGameConfigIntoForm()
    }

    private fun runTargetFolderPickerLessonTest() {
        appendLog("----- Target Folder Picker Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runSaveGameConfigWorkflow()

            val configBefore = engine.getGameDeploymentConfig("skyrim_le")
            appendLog("Before picker test config: $configBefore")

            if (configBefore == null) {
                appendError("Skyrim config missing before picker test.")
                appendLog("RESULT: FAIL")
                appendLog("----- Target Folder Picker Lesson Test End -----")
                return
            }

            appendLog("Manual step required: use 'Pick Target Folder' and then reload the Skyrim config.")
            appendLog("If the loaded config shows a non-null targetTreeUri, the picker integration works.")
            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Target folder picker lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Target Folder Picker Lesson Test End -----")
    }

    private fun runTreeUriDeployLessonTest() {
        appendLog("----- Tree URI Deploy Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val config = engine.getGameDeploymentConfig("skyrim_le")
            appendLog("Current Skyrim config: $config")

            if (config == null) {
                appendError("Skyrim config not found.")
                appendLog("RESULT: FAIL")
                appendLog("----- Tree URI Deploy Lesson Test End -----")
                return
            }

            if (config.targetTreeUri.isNullOrBlank()) {
                appendError("Skyrim targetTreeUri is null. Pick a target folder first.")
                appendLog("RESULT: FAIL")
                appendLog("----- Tree URI Deploy Lesson Test End -----")
                return
            }

            val configs = engine.loadGameDeploymentConfigs().toMutableList()
            val index = configs.indexOfFirst { it.gameId == "skyrim_le" }
            configs[index] = configs[index].copy(realDeployEnabled = true)
            engine.saveGameDeploymentConfigs(configs)

            runInstallArchiveWorkflow()
            runInstallLooseWorkflow()
            runSaveInstalledModsWorkflow()

            val result = engine.deployForGame("skyrim_le")
            appendLog("Tree URI deploy result -> Adds: ${result.addCount}, Removes: ${result.removeCount}, Updates: ${result.updateCount}")

            appendLog("If your picked folder now contains deployed files like textures/ui/icon.dds, the test succeeded.")
            appendLog("RESULT: PASS")
        } catch (e: Exception) {
            appendError("Tree URI deploy lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Tree URI Deploy Lesson Test End -----")
    }

    private fun runArchiveExtractorLessonTest() {
        appendLog("----- Archive Extractor Lesson Test Start -----")

        val internalBaseDir = filesDir
        val tempDir = File(internalBaseDir, "temp")
        val modsDir = File(internalBaseDir, "mods")

        tempDir.mkdirs()
        modsDir.mkdirs()

        try {
            val registry = com.shonkware.droidmodloader.engine.install.ArchiveExtractorRegistry()

            val zipArchive = createTestArchiveZip(internalBaseDir)
            val zipExtractor = registry.findExtractor(zipArchive)
            appendLog("ZIP extractor selected: ${zipExtractor::class.java.simpleName}")

            val fake7z = File(internalBaseDir, "FakeTest.7z")
            if (!fake7z.exists()) {
                fake7z.writeText("not a real 7z yet")
            }

            val sevenZipExtractor = registry.findExtractor(fake7z)
            appendLog("7Z extractor selected: ${sevenZipExtractor::class.java.simpleName}")

            val fakeRar = File(internalBaseDir, "FakeTest.rar")
            if (!fakeRar.exists()) {
                fakeRar.writeText("not supported")
            }

            try {
                registry.findExtractor(fakeRar)
                appendLog("RAR unexpectedly resolved to an extractor.")
                appendLog("RESULT: FAIL")
            } catch (e: IllegalArgumentException) {
                appendLog("RAR correctly rejected as unsupported.")
                appendLog("RESULT: PASS")
            }

        } catch (e: Exception) {
            appendError("Archive extractor lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Archive Extractor Lesson Test End -----")
    }

    private fun createPluginRow(plugin: PluginEntry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 20)
        }

        val infoText = TextView(this).apply {
            this.text = "Priority ${plugin.priority} | ${plugin.pluginName} | ${plugin.pluginType} | " +
                    "${if (plugin.enabled) "ENABLED" else "DISABLED"} | From: ${plugin.sourceModName}"
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val toggleButton = Button(this).apply {
            text = if (plugin.enabled) "Disable" else "Enable"
            setOnClickListener {
                runInBackground { togglePluginEnabled(plugin.pluginName) }
            }
        }

        val upButton = Button(this).apply {
            text = "Up"
            setOnClickListener {
                runInBackground { movePluginUp(plugin.pluginName) }
            }
        }

        val downButton = Button(this).apply {
            text = "Down"
            setOnClickListener {
                runInBackground { movePluginDown(plugin.pluginName) }
            }
        }

        buttonRow.addView(toggleButton)
        buttonRow.addView(upButton)
        buttonRow.addView(downButton)

        row.addView(infoText)
        row.addView(buttonRow)

        return row
    }

    private fun refreshPluginsPanel() {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }

        runOnUiThread {
            pluginsContainer.removeAllViews()

            if (plugins.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = "No plugins found."
                }
                pluginsContainer.addView(emptyText)
                return@runOnUiThread
            }

            for (plugin in plugins) {
                pluginsContainer.addView(createPluginRow(plugin))
            }
        }
    }

    private fun runSaveDiscoveredPluginsWorkflow() {
        appendLog("----- Save Discovered Plugins Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            val plugins = engine.saveDiscoveredPlugins()
            appendLog("Saved discovered plugin count: ${plugins.size}")

            for (plugin in plugins) {
                appendLog("PLUGIN: $plugin")
            }

            appendLog("RESULT: PASS")
            updateLastOperationStatus("Save discovered plugins succeeded.")
        } catch (e: Exception) {
            appendError("Save discovered plugins workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Save discovered plugins failed: ${e.message}")
        }

        refreshPluginsPanel()
        appendLog("----- Save Discovered Plugins Workflow End -----")
    }

    private fun runPluginDiscoveryLessonTest() {
        appendLog("----- Plugin Discovery Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runInstallArchiveWorkflow()
            runInstallLooseWorkflow()
            runSaveInstalledModsWorkflow()

            val discovered = engine.discoverPluginsFromCurrentMods()
            appendLog("Discovered plugin count: ${discovered.size}")
            for (plugin in discovered) {
                appendLog("DISCOVERED PLUGIN: $plugin")
            }

            val saved = engine.saveDiscoveredPlugins()
            appendLog("Saved plugin count: ${saved.size}")

            val loaded = engine.loadPlugins()
            appendLog("Loaded plugin count: ${loaded.size}")

            val hasSkyUi = loaded.any { it.pluginName.equals("SkyUI.esp", ignoreCase = true) }

            if (loaded.isNotEmpty() && hasSkyUi) {
                appendLog("Plugin discovery succeeded and includes SkyUI.esp")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Plugin discovery failed or expected plugin missing.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Plugin discovery lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshPluginsPanel()
        appendLog("----- Plugin Discovery Lesson Test End -----")
    }

    private fun togglePluginEnabled(pluginName: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.pluginName == pluginName }
        if (index == -1) {
            appendError("Could not find plugin: $pluginName")
            return
        }

        plugins[index] = plugins[index].copy(enabled = !plugins[index].enabled)
        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Toggled plugin enabled state for $pluginName")
        refreshPluginsPanel()
    }

    private fun movePluginUp(pluginName: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.pluginName == pluginName }
        if (index <= 0) {
            appendLog("Cannot move plugin up: $pluginName")
            return
        }

        val temp = plugins[index - 1]
        plugins[index - 1] = plugins[index]
        plugins[index] = temp

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin up: $pluginName")
        refreshPluginsPanel()
    }

    private fun movePluginDown(pluginName: String) {
        val engine = createModEngineForWorkflows() ?: return
        val plugins = engine.getCurrentPlugins().sortedBy { it.priority }.toMutableList()

        val index = plugins.indexOfFirst { it.pluginName == pluginName }
        if (index == -1 || index >= plugins.lastIndex) {
            appendLog("Cannot move plugin down: $pluginName")
            return
        }

        val temp = plugins[index + 1]
        plugins[index + 1] = plugins[index]
        plugins[index] = temp

        val normalized = engine.normalizePluginPriorities(plugins)
        engine.saveCurrentPlugins(normalized)

        appendLog("Moved plugin down: $pluginName")
        refreshPluginsPanel()
    }

    private fun runPluginEditLessonTest() {
        appendLog("----- Plugin Edit Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runInstallArchiveWorkflow()
            runSaveInstalledModsWorkflow()
            runSaveDiscoveredPluginsWorkflow()

            val before = engine.getCurrentPlugins().sortedBy { it.priority }
            appendLog("Plugin count before edit: ${before.size}")
            for (plugin in before) {
                appendLog("BEFORE PLUGIN: $plugin")
            }

            val skyUiPlugin = before.firstOrNull { it.pluginName.equals("SkyUI.esp", ignoreCase = true) }
            if (skyUiPlugin == null) {
                appendError("SkyUI.esp not found in plugin list.")
                appendLog("RESULT: FAIL")
                appendLog("----- Plugin Edit Lesson Test End -----")
                return
            }

            val edited = before.map {
                if (it.pluginName.equals("SkyUI.esp", ignoreCase = true)) {
                    it.copy(enabled = false)
                } else {
                    it
                }
            }

            val normalized = engine.normalizePluginPriorities(edited)
            engine.saveCurrentPlugins(normalized)

            val after = engine.loadPlugins().sortedBy { it.priority }
            appendLog("Plugin count after edit: ${after.size}")
            for (plugin in after) {
                appendLog("AFTER PLUGIN: $plugin")
            }

            val updatedSkyUi = after.firstOrNull { it.pluginName.equals("SkyUI.esp", ignoreCase = true) }

            if (updatedSkyUi != null && !updatedSkyUi.enabled) {
                appendLog("Plugin edit persisted correctly.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Plugin edit persistence failed.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Plugin edit lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        refreshPluginsPanel()
        appendLog("----- Plugin Edit Lesson Test End -----")
    }

    private fun runExportPluginOutputsWorkflow() {
        appendLog("----- Export Plugin Outputs Workflow Start -----")

        val engine = createModEngineForWorkflows() ?: return
        val externalBaseDir = getExternalFilesDir(null)
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Export plugin outputs failed: external files directory is null.")
            appendLog("----- Export Plugin Outputs Workflow End -----")
            return
        }

        val pluginsTxtFile = File(externalBaseDir, "state/plugins.txt")
        val loadorderTxtFile = File(externalBaseDir, "state/loadorder.txt")

        try {
            val (pluginsTxt, loadorderTxt) = engine.exportCurrentPluginOutputs()

            appendLog("plugins.txt path: ${pluginsTxtFile.absolutePath}")
            appendLog("loadorder.txt path: ${loadorderTxtFile.absolutePath}")
            appendLog("plugins.txt exists: ${pluginsTxtFile.exists()}")
            appendLog("loadorder.txt exists: ${loadorderTxtFile.exists()}")

            appendLog("plugins.txt contents:")
            appendLog(pluginsTxt.ifBlank { "(empty)" })

            appendLog("loadorder.txt contents:")
            appendLog(loadorderTxt.ifBlank { "(empty)" })

            appendLog("RESULT: PASS")
            updateLastOperationStatus("Export plugin outputs succeeded.")
        } catch (e: Exception) {
            appendError("Export plugin outputs workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            updateLastOperationStatus("Export plugin outputs failed: ${e.message}")
        }

        appendLog("----- Export Plugin Outputs Workflow End -----")
    }

    private fun runPluginOutputLessonTest() {
        appendLog("----- Plugin Output Lesson Test Start -----")

        val engine = createModEngineForWorkflows() ?: return

        try {
            runInstallArchiveWorkflow()
            runSaveInstalledModsWorkflow()
            runSaveDiscoveredPluginsWorkflow()

            val pluginsBefore = engine.getCurrentPlugins().sortedBy { it.priority }
            appendLog("Plugin count before export: ${pluginsBefore.size}")
            for (plugin in pluginsBefore) {
                appendLog("PLUGIN BEFORE EXPORT: $plugin")
            }

            val (pluginsTxt, loadorderTxt) = engine.exportCurrentPluginOutputs()

            appendLog("Generated plugins.txt:")
            appendLog(pluginsTxt.ifBlank { "(empty)" })

            appendLog("Generated loadorder.txt:")
            appendLog(loadorderTxt.ifBlank { "(empty)" })

            val hasSkyUiInPlugins = pluginsTxt.lines().any { it.equals("SkyUI.esp", ignoreCase = true) }
            val hasSkyUiInLoadorder = loadorderTxt.lines().any { it.equals("SkyUI.esp", ignoreCase = true) }

            if (hasSkyUiInPlugins && hasSkyUiInLoadorder) {
                appendLog("Plugin output export succeeded.")
                appendLog("RESULT: PASS")
            } else {
                appendLog("Plugin output export failed or expected plugin missing.")
                appendLog("RESULT: FAIL")
            }
        } catch (e: Exception) {
            appendError("Plugin output lesson test failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
        }

        appendLog("----- Plugin Output Lesson Test End -----")
    }

    private fun buildDiagnosticSummary(): String {
        val engine = createModEngineForWorkflows()

        val mods = engine?.getCurrentMods() ?: emptyList()
        val plugins = engine?.getCurrentPlugins() ?: emptyList()

        val enabledMods = mods.count { it.enabled }
        val enabledPlugins = plugins.count { it.enabled }

        return buildString {
            appendLine("=== Droid Mod Loader Diagnostic Summary ===")
            appendLine()
            appendLine("App Version: 0.1 Beta")
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
            appendLine(logTextView.text.toString())
        }
    }

    private fun copyDiagnosticSummary() {
        val summary = buildDiagnosticSummary()

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(
            "DroidModLoaderDiagnosticSummary",
            summary
        )

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            this,
            "Diagnostic summary copied.",
            Toast.LENGTH_SHORT
        ).show()

        appendLog("Diagnostic summary copied.")
    }

    private fun shareLogs() {
        val summary = buildDiagnosticSummary()

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, summary)
            type = "text/plain"
        }

        startActivity(Intent.createChooser(sendIntent, "Share Diagnostic Summary"))
    }

    private fun updateLastOperationStatus(status: String) {
        lastOperationStatus = status
    }

    private fun bindCoreViews() {
        developerToolsContainer = findViewById(R.id.developerToolsContainer)
        logTextView = findViewById(R.id.logTextView)
        summaryTextView = findViewById(R.id.summaryTextView)
        installedModsContainer = findViewById(R.id.installedModsContainer)
        pluginsContainer = findViewById(R.id.pluginsContainer)

        spinnerGameConfig = findViewById(R.id.spinnerGameConfig)
        editTargetPath = findViewById(R.id.editTargetPath)
        checkRealDeployEnabled = findViewById(R.id.checkRealDeployEnabled)
        textSelectedTreeUri = findViewById(R.id.textSelectedTreeUri)
    }

    private fun setupDeveloperUnlock() {
        val textVersionLabel: TextView = findViewById(R.id.textVersionLabel)

        textVersionLabel.setOnClickListener {
            developerTapCount++

            if (!developerModeEnabled && developerTapCount >= 7) {
                developerModeEnabled = true
                developerToolsContainer.visibility = View.VISIBLE

                appendLog("Developer tools unlocked.")
                Toast.makeText(this, "Developer tools enabled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initializeUi() {
        runInBackground { refreshGameConfigSpinner() }
        appendLog("UI ready.")
        runInBackground { refreshDashboard() }
    }

    private fun setupButtonListeners() {
        val buttonRefreshPluginsPanel: Button = findViewById(R.id.buttonRefreshPluginsPanel)
        val buttonSaveDiscoveredPlugins: Button = findViewById(R.id.buttonSaveDiscoveredPlugins)
        val buttonTargetFolderPickerTest: Button = findViewById(R.id.buttonTargetFolderPickerTest)
        val buttonPickTargetFolder: Button = findViewById(R.id.buttonPickTargetFolder)
        val buttonLoadSelectedGameConfig: Button = findViewById(R.id.buttonLoadSelectedGameConfig)
        val buttonSaveSelectedGameConfig: Button = findViewById(R.id.buttonSaveSelectedGameConfig)
        val buttonRefreshDashboard: Button = findViewById(R.id.buttonRefreshDashboard)
        val buttonRefreshModsPanel: Button = findViewById(R.id.buttonRefreshModsPanel)
        val buttonSmokeTest: Button = findViewById(R.id.buttonSmokeTest)
        val buttonPathUtilsTest: Button = findViewById(R.id.buttonPathUtilsTest)
        val buttonScannerTest: Button = findViewById(R.id.buttonScannerTest)
        val buttonConflictTest: Button = findViewById(R.id.buttonConflictTest)
        val buttonStagingTest: Button = findViewById(R.id.buttonStagingTest)
        val buttonDiffTest: Button = findViewById(R.id.buttonDiffTest)
        val buttonClearLog: Button = findViewById(R.id.buttonClearLog)
        val buttonStateTest: Button = findViewById(R.id.buttonStateTest)
        val buttonEngineTest: Button = findViewById(R.id.buttonEngineTest)
        val buttonInstallArchiveWorkflow: Button = findViewById(R.id.buttonInstallArchiveWorkflow)
        val buttonInstallLooseWorkflow: Button = findViewById(R.id.buttonInstallLooseWorkflow)
        val buttonListInstalledMods: Button = findViewById(R.id.buttonListInstalledMods)
        val buttonSaveInstalledMods: Button = findViewById(R.id.buttonSaveInstalledMods)
        val buttonLoadSavedMods: Button = findViewById(R.id.buttonLoadSavedMods)
        val buttonRebuildInstalledStaging: Button = findViewById(R.id.buttonRebuildInstalledStaging)
        val buttonImportZip: Button = findViewById(R.id.buttonImportZip)
        val buttonDeleteModTest: Button = findViewById(R.id.buttonDeleteModTest)
        val buttonResetAppData: Button = findViewById(R.id.buttonResetAppData)
        val buttonInstalledRecordTest: Button = findViewById(R.id.buttonInstalledRecordTest)
        val buttonDeployScopeTest: Button = findViewById(R.id.buttonDeployScopeTest)
        val buttonDeployCurrentState: Button = findViewById(R.id.buttonDeployCurrentState)
        val buttonDeploymentManifestTest: Button = findViewById(R.id.buttonDeploymentManifestTest)
        val buttonSaveGameConfig: Button = findViewById(R.id.buttonSaveGameConfig)
        val buttonLoadGameConfig: Button = findViewById(R.id.buttonLoadGameConfig)
        val buttonDeploySkyrim: Button = findViewById(R.id.buttonDeploySkyrim)
        val buttonGameTargetTest: Button = findViewById(R.id.buttonGameTargetTest)
        val buttonGameConfigEditorTest: Button = findViewById(R.id.buttonGameConfigEditorTest)
        val buttonTreeUriDeployTest: Button = findViewById(R.id.buttonTreeUriDeployTest)
        val buttonArchiveExtractorTest: Button = findViewById(R.id.buttonArchiveExtractorTest)
        val buttonPluginDiscoveryTest: Button = findViewById(R.id.buttonPluginDiscoveryTest)
        val buttonPluginEditTest: Button = findViewById(R.id.buttonPluginEditTest)
        val buttonExportPluginOutputs: Button = findViewById(R.id.buttonExportPluginOutputs)
        val buttonPluginOutputTest: Button = findViewById(R.id.buttonPluginOutputTest)
        val buttonCopyDiagnosticSummary: Button = findViewById(R.id.buttonCopyDiagnosticSummary)
        val buttonShareLogs: Button = findViewById(R.id.buttonShareLogs)

        buttonPluginEditTest.setOnClickListener {
            runInBackground { runPluginEditLessonTest() }
        }

        buttonPluginDiscoveryTest.setOnClickListener {
            runInBackground { runPluginDiscoveryLessonTest() }
        }

        buttonArchiveExtractorTest.setOnClickListener {
            runInBackground { runArchiveExtractorLessonTest() }
        }

        buttonTreeUriDeployTest.setOnClickListener {
            runInBackground { runTreeUriDeployLessonTest() }
        }

        buttonGameConfigEditorTest.setOnClickListener {
            runInBackground { runGameConfigEditorLessonTest() }
        }

        buttonSmokeTest.setOnClickListener {
            runInBackground { runSmokeTest() }
        }

        buttonPathUtilsTest.setOnClickListener {
            runInBackground { runPathUtilsLessonTest() }
        }

        buttonScannerTest.setOnClickListener {
            runInBackground { runFileScannerLessonTest() }
        }

        buttonConflictTest.setOnClickListener {
            runInBackground { runConflictResolverLessonTest() }
        }

        buttonStagingTest.setOnClickListener {
            runInBackground { runStagingManagerLessonTest() }
        }

        buttonDiffTest.setOnClickListener {
            runInBackground { runDiffEngineLessonTest() }
        }

        buttonStateTest.setOnClickListener {
            runInBackground { runModStateLessonTest() }
        }

        buttonClearLog.setOnClickListener {
            logTextView.text = ""
        }

        buttonEngineTest.setOnClickListener {
            runInBackground { runModEngineLessonTest() }
        }

        buttonInstallArchiveWorkflow.setOnClickListener {
            runInBackground { runInstallArchiveWorkflow() }
        }

        buttonInstallLooseWorkflow.setOnClickListener {
            runInBackground { runInstallLooseWorkflow() }
        }

        buttonListInstalledMods.setOnClickListener {
            runInBackground { runListInstalledModsWorkflow() }
        }

        buttonSaveInstalledMods.setOnClickListener {
            runInBackground { runSaveInstalledModsWorkflow() }
        }

        buttonLoadSavedMods.setOnClickListener {
            runInBackground { runLoadSavedModsWorkflow() }
        }

        buttonRebuildInstalledStaging.setOnClickListener {
            runInBackground { runRebuildInstalledStagingWorkflow() }
        }

        buttonImportZip.setOnClickListener {
            appendLog("Opening document picker...")
            importZipLauncher.launch(arrayOf("*/*"))
        }

        buttonRefreshModsPanel.setOnClickListener {
            runInBackground { refreshInstalledModsPanel() }
        }

        buttonDeleteModTest.setOnClickListener {
            runInBackground { runDeleteModLessonTest() }
        }

        buttonResetAppData.setOnClickListener {
            runInBackground { runResetAppDataWorkflow() }
        }

        buttonRefreshDashboard.setOnClickListener {
            runInBackground { refreshDashboard() }
        }

        buttonInstalledRecordTest.setOnClickListener {
            runInBackground { runInstalledRecordLessonTest() }
        }

        buttonDeployScopeTest.setOnClickListener {
            runInBackground { runDeployScopeLessonTest() }
        }

        buttonDeployCurrentState.setOnClickListener {
            runInBackground { runDeployCurrentStateWorkflow() }
        }

        buttonDeploymentManifestTest.setOnClickListener {
            runInBackground { runDeploymentManifestLessonTest() }
        }

        buttonSaveGameConfig.setOnClickListener {
            runInBackground { runSaveGameConfigWorkflow() }
        }

        buttonLoadGameConfig.setOnClickListener {
            runInBackground { runLoadGameConfigWorkflow() }
        }

        buttonDeploySkyrim.setOnClickListener {
            runInBackground { runDeploySkyrimWorkflow() }
        }

        buttonGameTargetTest.setOnClickListener {
            runInBackground { runGameTargetLessonTest() }
        }

        buttonLoadSelectedGameConfig.setOnClickListener {
            runInBackground { loadSelectedGameConfigIntoForm() }
        }

        buttonSaveSelectedGameConfig.setOnClickListener {
            runInBackground { saveSelectedGameConfigFromForm() }
        }

        buttonPickTargetFolder.setOnClickListener {
            pickTargetFolderLauncher.launch(null)
        }

        buttonTargetFolderPickerTest.setOnClickListener {
            runInBackground { runTargetFolderPickerLessonTest() }
        }

        buttonRefreshPluginsPanel.setOnClickListener {
            runInBackground { refreshPluginsPanel() }
        }

        buttonSaveDiscoveredPlugins.setOnClickListener {
            runInBackground { runSaveDiscoveredPluginsWorkflow() }
        }

        buttonExportPluginOutputs.setOnClickListener {
            runInBackground { runExportPluginOutputsWorkflow() }
        }

        buttonPluginOutputTest.setOnClickListener {
            runInBackground { runPluginOutputLessonTest() }
        }

        buttonCopyDiagnosticSummary.setOnClickListener {
            copyDiagnosticSummary()
        }

        buttonShareLogs.setOnClickListener {
            shareLogs()
        }
    }




}
