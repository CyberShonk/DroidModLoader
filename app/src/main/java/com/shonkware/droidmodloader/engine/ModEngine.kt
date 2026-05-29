package com.shonkware.droidmodloader.engine

import com.shonkware.droidmodloader.engine.conflict.ConflictResolver
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.install.ModInstaller
import com.shonkware.droidmodloader.engine.data.InstalledModRecordRepository
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.rules.DeployFileClassifier
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.deploy.DeploymentManager
import com.shonkware.droidmodloader.engine.deploy.DeploymentResult
import com.shonkware.droidmodloader.engine.data.GameDeploymentConfigRepository
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import java.io.File
import android.content.Context
import android.net.Uri
import com.shonkware.droidmodloader.engine.deploy.TreeUriDeploymentManager
import com.shonkware.droidmodloader.engine.data.PluginListRepository
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.data.PluginOutputRepository
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModContentIndexer
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstaller
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.install.InstallerSelection
import com.shonkware.droidmodloader.engine.index.ModContentCategory
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewEntry
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.plugins.DataFolderPluginScanner
import com.shonkware.droidmodloader.engine.plugins.GamePluginRules
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanner
import com.shonkware.droidmodloader.engine.baseline.DataBaselineFileRecord
import com.shonkware.droidmodloader.engine.baseline.DataBaselineRepository
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanResult
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import java.security.MessageDigest
import com.shonkware.droidmodloader.engine.plugins.ManagedPluginScanner
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.install.ModDisplayNameNormalizer
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraph
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraphBuilder
import com.shonkware.droidmodloader.engine.resolve.ResolvedFileIdentity
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanBuilder
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanScope
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightChecker
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalResultSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus

data class UninstallResult(
    val removed: Boolean,
    val removedModId: String,
    val deletedFileCount: Int
)

class ModEngine(
    private val appContext: Context,
    private val tempDir: File,
    private val modsDir: File,
    private val stateFile: File,
    private val deploymentManifestFile: File,
    private val deployRootDir: File,
    private val gameConfigFile: File,
    private val pluginListFile: File,
    private val pluginsTxtFile: File,
    private val loadorderTxtFile: File

) {

    private val modInstaller = ModInstaller(tempDir, modsDir)
    private val resolver = ConflictResolver()
    private val stateRepository = ModStateRepository(stateFile)
    private val installedModRecordRepository = InstalledModRecordRepository()
    private val deployFileClassifier = DeployFileClassifier()
    private val gameDeploymentConfigRepository = GameDeploymentConfigRepository(gameConfigFile)
    private val pluginListRepository = PluginListRepository(pluginListFile)
    private val managedPluginScanner = ManagedPluginScanner()
    private val modContentIndexer = ModContentIndexer()
    private val modFileIndexService = ModFileIndexService(
        ModFileIndexRepository(
            File(stateFile.parentFile, "mod_file_indexes")
        )
    )
    private val overwriteScanner = OverwriteScanner(appContext)
    private val preparedArchiveInstaller = PreparedArchiveInstaller(
        tempDir = tempDir,
        modsDir = modsDir
    )
    private val pluginOutputRepository = PluginOutputRepository(
        pluginsTxtFile = pluginsTxtFile,
        loadorderTxtFile = loadorderTxtFile
    )
    private val dataFolderPluginScanner = DataFolderPluginScanner(appContext)
    private val gamePluginRules = GamePluginRules()
    fun buildModFromInstalledFolder(
        modDir: File,
        priority: Int,
        enabled: Boolean = true
    ): Mod {
        val record = installedModRecordRepository.loadRecord(modDir)
        val displayName = record?.displayName?.takeIf { it.isNotBlank() }
            ?: ModDisplayNameNormalizer.cleanDisplayName(
                sourceArchiveName = record?.sourceArchiveName,
                fallbackFolderName = modDir.name
            )

        return Mod(
            id = modDir.name,
            name = displayName,
            installPath = modDir.absolutePath,
            enabled = enabled,
            priority = priority,
            modType = detectModType(modDir)
        )
    }
    fun scanMod(mod: Mod): List<ModFile> {
        val index = modFileIndexService.getOrBuildIndex(mod)

        return index.entries.map { entry ->
            ModFile(
                modId = mod.id,
                sourceModName = mod.name,
                originalPath = entry.originalPath,
                normalizedPath = entry.normalizedPath,
                hash = entry.hash
            )
        }
    }

    fun scanMods(mods: List<Mod>): List<ModFile> {
        val allModFiles = mutableListOf<ModFile>()

        for (mod in mods) {
            allModFiles.addAll(scanMod(mod))
        }

        return allModFiles
    }

    fun resolve(mods: List<Mod>): List<FileRecord> {
        return resolveForScopes(
            mods = mods,
            deployScopes = setOf(DeployScope.DATA)
        )
    }

    private fun resolveForScopes(
        mods: List<Mod>,
        deployScopes: Set<DeployScope>
    ): List<FileRecord> {
        val enabledMods = mods
            .filter { it.enabled }
            .sortedBy { it.priority }

        if (enabledMods.isEmpty()) {
            return emptyList()
        }

        val modFiles = scanMods(enabledMods)
        val deployableModFiles = modFiles.filter { modFile ->
            deployFileClassifier.classify(modFile.normalizedPath) in deployScopes
        }

        return resolver.resolve(enabledMods, deployableModFiles)
    }

    fun saveMods(mods: List<Mod>) {
        stateRepository.saveMods(mods)
    }

    fun loadMods(): List<Mod> {
        return stateRepository.loadMods()
    }

    fun getInstalledModsFromFolders(): List<Mod> {
        val modDirs = modsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        return modDirs.mapIndexed { index, modDir ->
            buildModFromInstalledFolder(
                modDir = modDir,
                priority = index + 1,
                enabled = true
            )
        }
    }

    fun saveInstalledModsFromFolders(): List<Mod> {
        val mods = getInstalledModsFromFolders()
        saveMods(mods)
        return mods
    }

    fun getCurrentMods(): List<Mod> {
        val savedMods = loadMods().sortedBy { it.priority }
        return if (savedMods.isNotEmpty()) {
            savedMods
        } else {
            getInstalledModsFromFolders()
        }
    }
    fun getEnabledCurrentMods(): List<Mod> {
        return getCurrentMods()
            .filter { it.enabled }
            .sortedBy { it.priority }
    }

    fun saveCurrentMods(mods: List<Mod>) {
        saveMods(normalizeModPriorities(mods))
    }

    fun uninstallModAndApplyDiff(modId: String): UninstallResult {
        val currentMods = getCurrentMods().sortedBy { it.priority }
        val modToRemove = currentMods.firstOrNull { it.id == modId }
            ?: return UninstallResult(
                removed = false,
                removedModId = modId,
                deletedFileCount = 0
            )

        val remainingMods = currentMods
            .filterNot { it.id == modId }
            .mapIndexed { index, mod ->
                mod.copy(priority = index + 1)
            }

        saveCurrentMods(remainingMods)

        val modDir = File(modToRemove.installPath)
        val deletedFileCount = if (modDir.exists()) {
            modDir.walkTopDown().count { it.isFile }
        } else {
            0
        }

        modFileIndexService.deleteIndex(modToRemove)

        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        return UninstallResult(
            removed = true,
            removedModId = modId,
            deletedFileCount = deletedFileCount
        )
    }

    fun resetAllAppData(importsDir: File): Boolean {
        // importsDir is legacy cleanup for old builds that copied selected archives
        // into externalFilesDir/imports. New installs use temporary profile cache.
        return try {
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (modsDir.exists()) modsDir.deleteRecursively()
            if (importsDir.exists()) importsDir.deleteRecursively()
            if (stateFile.exists()) stateFile.delete()
            if (gameConfigFile.exists()) {
                gameConfigFile.delete()
            }

            if (pluginListFile.exists()) {
                pluginListFile.delete()
            }

            if (pluginsTxtFile.exists()) {
                pluginsTxtFile.delete()
            }

            if (loadorderTxtFile.exists()) {
                loadorderTxtFile.delete()
            }
            if (deployRootDir.exists()) {
                deployRootDir.deleteRecursively()
            }

            if (deploymentManifestFile.exists()) {
                deploymentManifestFile.delete()
            }

            if (deploymentManifestFile.parentFile?.exists() == true) {
                deploymentManifestFile.parentFile?.listFiles()
                    ?.filter {
                        (it.name.startsWith("deployment_manifest") && it.extension == "json") ||
                                (it.name.startsWith("data_baseline") && it.extension == "json")
                    }
                    ?.forEach { it.delete() }
            }

            tempDir.mkdirs()
            modsDir.mkdirs()
            stateFile.parentFile?.mkdirs()

            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasSavedState(): Boolean {
        return stateFile.exists() && stateFile.readText().isNotBlank()
    }

    fun getCurrentModSummary(): Triple<Int, Int, Boolean> {
        val mods = getCurrentMods()
        val installedCount = mods.size
        val enabledCount = mods.count { it.enabled }
        val hasSaved = hasSavedState()

        return Triple(installedCount, enabledCount, hasSaved)
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

    private fun writeInstalledModRecord(
        modDir: File,
        sourceType: String,
        sourceArchiveName: String?
    ) {
        val displayName = ModDisplayNameNormalizer.cleanDisplayName(
            sourceArchiveName = sourceArchiveName,
            fallbackFolderName = modDir.name
        )

        val record = InstalledModRecord(
            modId = modDir.name,
            displayName = displayName,
            installPath = modDir.absolutePath,
            sourceType = sourceType,
            sourceArchiveName = sourceArchiveName,
            installedAtEpochMillis = System.currentTimeMillis()
        )

        installedModRecordRepository.saveRecord(modDir, record)
    }
    fun installArchiveWithRecord(
        archive: File,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String = "imported_zip"): Mod {
        val extractedDir = modInstaller.installArchive(archive)
        writeInstalledModRecord(
            modDir = extractedDir,
            sourceType = sourceType,
            sourceArchiveName = archive.name
        )
        return buildModFromInstalledFolder(extractedDir, priority, enabled)
    }

    fun registerExistingInstalledFolderWithRecord(
        modDir: File,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String): Mod {
        writeInstalledModRecord(
            modDir = modDir,
            sourceType = sourceType,
            sourceArchiveName = null
        )
        return buildModFromInstalledFolder(modDir, priority, enabled)
    }

    fun loadInstalledModRecord(mod: Mod): InstalledModRecord? {
        val modDir = File(mod.installPath)
        return installedModRecordRepository.loadRecord(modDir)
    }

    fun loadInstalledModRecords(mods: List<Mod>): Map<String, InstalledModRecord> {
        val results = mutableMapOf<String, InstalledModRecord>()

        for (mod in mods) {
            val record = loadInstalledModRecord(mod)
            if (record != null) {
                results[mod.id] = record
            }
        }

        return results
    }

    fun getDeployScopeForPath(normalizedPath: String): DeployScope {
        return deployFileClassifier.classify(normalizedPath)
    }

    fun classifyModFiles(modFiles: List<ModFile>): Map<DeployScope, List<ModFile>> {
        return modFiles.groupBy { deployFileClassifier.classify(it.normalizedPath) }
    }

    fun filterDeployableModFiles(modFiles: List<ModFile>): List<ModFile> {
        return modFiles.filter {
            val scope = deployFileClassifier.classify(it.normalizedPath)
            deployFileClassifier.isDeployable(scope)
        }
    }

    fun getCurrentDataWinningRecords(): List<FileRecord> {
        return resolveForScopes(
            mods = getEnabledCurrentMods(),
            deployScopes = setOf(DeployScope.DATA)
        )
    }

    fun getCurrentRootWinningRecords(): List<FileRecord> {
        return resolveForScopes(
            mods = getEnabledCurrentMods(),
            deployScopes = setOf(DeployScope.GAME_ROOT)
        )
    }

    fun getCurrentWinningRecords(): List<FileRecord> {
        return getCurrentDataWinningRecords()
    }
    fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>) {
        gameDeploymentConfigRepository.save(configs)
    }

    fun loadGameDeploymentConfigs(): List<GameDeploymentConfig> {
        return gameDeploymentConfigRepository.load()
    }

    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? {
        return loadGameDeploymentConfigs().firstOrNull { it.gameId == gameId }
    }

    fun validateTargetDataPath(path: String): Boolean {
        if (path.isBlank()) return false

        val target = File(path)
        val normalized = path.lowercase()

        if (normalized.contains("android/data/com.shonkware.droidmodloader")) return false
        if (normalized.endsWith("/")) return true

        return target.exists() || target.parentFile?.exists() == true
    }
    fun deployForGame(gameId: String): ScopedDeploymentResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                getEffectiveDeploymentManifestFile(gameId)
            )

            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()

            val (newDataManifest, dataResult) = deployRecordsToConfiguredTarget(
                oldManifest = oldDataManifest,
                newWinningRecords = dataWinningRecords,
                realDeployEnabled = config?.realDeployEnabled == true,
                targetTreeUri = config?.targetTreeUri,
                targetPath = config?.targetDataPath ?: "",
                fallbackRootDir = deployRootDir,
                backupRootDir = getDeploymentBackupDir(
                    gameId = gameId,
                    scopeName = "data",
                    rootTarget = false
                )
            )

            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                getEffectiveRootDeploymentManifestFile(gameId)
            )

            val oldRootManifest = rootManifestRepository.load()
            val rootWinningRecords = getCurrentRootWinningRecords()

            val canDeployRoot = canDeployGameRoot(config)

            val rootResult = if (canDeployRoot && (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())) {
                val (newRootManifest, result) = deployRecordsToConfiguredTarget(
                    oldManifest = oldRootManifest,
                    newWinningRecords = rootWinningRecords,
                    realDeployEnabled = config?.realDeployEnabled == true,
                    targetTreeUri = config?.targetRootTreeUri,
                    targetPath = config?.targetRootPath ?: "",
                    fallbackRootDir = getSimulatedGameRootDir(),
                    backupRootDir = getDeploymentBackupDir(
                        gameId = gameId,
                        scopeName = "root",
                        rootTarget = true
                    )
                )

                rootManifestRepository.save(newRootManifest)
                result
            } else {
                DeploymentResult(
                    addCount = 0,
                    removeCount = 0,
                    updateCount = 0,
                    finalRecordCount = 0
                )
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = DeploymentJournalResultSummary(
                    addCount = scopedResult.addCount,
                    updateCount = scopedResult.updateCount,
                    removeCount = scopedResult.removeCount,
                    backupCount = scopedResult.dataResult.backupCount + scopedResult.rootResult.backupCount,
                    restoreCount = scopedResult.dataResult.restoreCount + scopedResult.rootResult.restoreCount,
                    protectedConflictCount = scopedResult.dataResult.protectedConflictCount + scopedResult.rootResult.protectedConflictCount,
                    finalRecordCount = scopedResult.finalRecordCount
                )
            )

            return scopedResult
        } catch (e: Exception) {
            journalRepository.markFailed(
                record = journalRecord,
                message = e.message ?: e::class.java.name
            )

            throw e
        }
    }

    fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult {
        val plan = buildFullRedeployPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        val rootPlanHasWork = plan.rootPlan.operationCount > 0
        val rootCanDeploy = canDeployGameRoot(config)

        if (rootPlanHasWork && !rootCanDeploy) {
            throw IllegalStateException(
                "Full redeploy needs Game Root work, but no Game Root target is available."
            )
        }

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                getEffectiveDeploymentManifestFile(gameId)
            )

            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()

            val forcedOldDataManifest = forceManifestToRewriteCurrentWinners(
                oldManifest = oldDataManifest,
                currentWinners = dataWinningRecords
            )

            val (newDataManifest, dataResult) = deployRecordsToConfiguredTarget(
                oldManifest = forcedOldDataManifest,
                newWinningRecords = dataWinningRecords,
                realDeployEnabled = config?.realDeployEnabled == true,
                targetTreeUri = config?.targetTreeUri,
                targetPath = config?.targetDataPath ?: "",
                fallbackRootDir = deployRootDir,
                backupRootDir = getDeploymentBackupDir(
                    gameId = gameId,
                    scopeName = "data",
                    rootTarget = false
                )
            )

            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                getEffectiveRootDeploymentManifestFile(gameId)
            )

            val oldRootManifest = rootManifestRepository.load()
            val rootWinningRecords = getCurrentRootWinningRecords()

            val rootResult = if (rootCanDeploy && (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())) {
                val forcedOldRootManifest = forceManifestToRewriteCurrentWinners(
                    oldManifest = oldRootManifest,
                    currentWinners = rootWinningRecords
                )

                val (newRootManifest, result) = deployRecordsToConfiguredTarget(
                    oldManifest = forcedOldRootManifest,
                    newWinningRecords = rootWinningRecords,
                    realDeployEnabled = config?.realDeployEnabled == true,
                    targetTreeUri = config?.targetRootTreeUri,
                    targetPath = config?.targetRootPath ?: "",
                    fallbackRootDir = getSimulatedGameRootDir(),
                    backupRootDir = getDeploymentBackupDir(
                        gameId = gameId,
                        scopeName = "root",
                        rootTarget = true
                    )
                )

                rootManifestRepository.save(newRootManifest)
                result
            } else {
                DeploymentResult(
                    addCount = 0,
                    removeCount = 0,
                    updateCount = 0,
                    finalRecordCount = 0
                )
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = DeploymentJournalResultSummary(
                    addCount = scopedResult.addCount,
                    updateCount = scopedResult.updateCount,
                    removeCount = scopedResult.removeCount,
                    backupCount = scopedResult.dataResult.backupCount + scopedResult.rootResult.backupCount,
                    restoreCount = scopedResult.dataResult.restoreCount + scopedResult.rootResult.restoreCount,
                    protectedConflictCount = scopedResult.dataResult.protectedConflictCount + scopedResult.rootResult.protectedConflictCount,
                    finalRecordCount = scopedResult.finalRecordCount
                )
            )

            return scopedResult
        } catch (e: Exception) {
            journalRepository.markFailed(
                record = journalRecord,
                message = e.message ?: e::class.java.name
            )

            throw e
        }
    }

    private fun deployRecordsToConfiguredTarget(
        oldManifest: List<DeploymentRecord>,
        newWinningRecords: List<FileRecord>,
        realDeployEnabled: Boolean,
        targetTreeUri: String?,
        targetPath: String,
        fallbackRootDir: File,
        backupRootDir: File
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        return when {
            realDeployEnabled && !targetTreeUri.isNullOrBlank() -> {
                val treeDeploymentManager = TreeUriDeploymentManager(
                    context = appContext,
                    contentResolver = appContext.contentResolver,
                    treeUri = Uri.parse(targetTreeUri),
                    backupRootDir = backupRootDir
                )

                treeDeploymentManager.deploy(oldManifest, newWinningRecords)
            }

            realDeployEnabled && validateTargetDataPath(targetPath) -> {
                val deploymentManager = DeploymentManager(
                    deployRootDir = File(targetPath),
                    backupRootDir = backupRootDir
                )
                deploymentManager.deploy(oldManifest, newWinningRecords)
            }

            else -> {
                val deploymentManager = DeploymentManager(
                    deployRootDir = fallbackRootDir,
                    backupRootDir = backupRootDir
                )
                deploymentManager.deploy(oldManifest, newWinningRecords)
            }
        }
    }

    fun buildDeploymentPlanForGame(gameId: String): ScopedDeploymentPlan {
        val dataManifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val oldDataManifest = dataManifestRepository.load()
        val dataWinningRecords = getCurrentDataWinningRecords()

        val rootManifestRepository = DeploymentManifestRepository(
            getEffectiveRootDeploymentManifestFile(gameId)
        )

        val oldRootManifest = rootManifestRepository.load()
        val rootWinningRecords = getCurrentRootWinningRecords()

        val builder = DeploymentPlanBuilder()

        val dataPlan = builder.build(
            scope = DeploymentPlanScope.DATA,
            oldManifest = oldDataManifest,
            newWinningRecords = dataWinningRecords
        )

        val rootPlan = builder.build(
            scope = DeploymentPlanScope.GAME_ROOT,
            oldManifest = oldRootManifest,
            newWinningRecords = rootWinningRecords
        )

        return ScopedDeploymentPlan(
            dataPlan = dataPlan,
            rootPlan = rootPlan
        )
    }

    fun buildDeploymentPlanDebugSummary(gameId: String): String {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )

        return buildString {
            appendLine(buildDeploymentPlanContextSummary(gameId, config, plan))
            appendLine()
            appendLine(plan.toDebugSummary())
            appendLine()
            appendLine(preflight.toDebugSummary())
        }
    }

    private fun buildDeploymentPlanContextSummary(
        gameId: String,
        config: GameDeploymentConfig?,
        plan: ScopedDeploymentPlan
    ): String {
        val realDeployEnabled = config?.realDeployEnabled == true

        val dataTargetStatus = when {
            config == null -> "no config"
            !config.targetTreeUri.isNullOrBlank() -> "Tree URI selected"
            config.targetDataPath.isNotBlank() -> "local path selected"
            else -> "not selected"
        }

        val rootTargetStatus = when {
            config == null -> "no config"
            !config.targetRootTreeUri.isNullOrBlank() -> "Tree URI selected"
            config.targetRootPath.isNotBlank() -> "local path selected"
            else -> "not selected"
        }

        val rootOperationsNeedTarget =
            plan.rootPlan.operationCount > 0 && rootTargetStatus == "not selected"

        return buildString {
            appendLine("Deploy Plan Context")
            appendLine("  Game: $gameId")
            appendLine("  Mode: ${if (realDeployEnabled) "real target folders" else "test output folders"}")
            appendLine("  Data target: $dataTargetStatus")
            appendLine("  Game Root target: $rootTargetStatus")
            appendLine("  Data operations: ${plan.dataPlan.operationCount}")
            appendLine("  Game Root operations: ${plan.rootPlan.operationCount}")

            if (rootOperationsNeedTarget) {
                appendLine("  Warning: Game Root operations exist, but no Game Root target is selected.")
            }
        }
    }

    private fun canDeployGameRoot(config: GameDeploymentConfig?): Boolean {
        if (config == null) return true

        if (!config.realDeployEnabled) {
            return true
        }

        return !config.targetRootTreeUri.isNullOrBlank() ||
                validateTargetDataPath(config.targetRootPath)
    }

    private fun getSimulatedGameRootDir(): File {
        return File(
            deployRootDir.parentFile ?: deployRootDir,
            "GameRoot"
        )
    }

    private fun getDeploymentBackupDir(
        gameId: String,
        scopeName: String,
        rootTarget: Boolean
    ): File {
        val identity = if (rootTarget) {
            getRootDeploymentTargetIdentity(gameId)
        } else {
            getDeploymentTargetIdentity(gameId)
        }

        val hash = hashManifestKey(identity.stableKey())

        val baseDir = deploymentManifestFile.parentFile
            ?: File(appContext.filesDir, "state")

        return File(
            baseDir,
            "deployment_backups/${identity.gameId}_${identity.mode}_$hash/$scopeName"
        )
    }

    private fun getEffectiveDeploymentManifestFile(gameId: String): File {
        return File(
            deploymentManifestFile.parentFile,
            buildTargetScopedFileName("deployment_manifest", gameId)
        )
    }

    private fun getEffectiveRootDeploymentManifestFile(gameId: String): File {
        return File(
            deploymentManifestFile.parentFile,
            buildTargetScopedFileNameForIdentity(
                prefix = "deployment_manifest_root",
                identity = getRootDeploymentTargetIdentity(gameId)
            )
        )
    }

    fun discoverPluginsFromCurrentMods(): List<PluginEntry> {
        return managedPluginScanner.discoverPluginsFromEnabledMods(getCurrentMods())
    }

    fun saveDiscoveredPlugins(): List<PluginEntry> {
        val plugins = discoverPluginsFromCurrentMods()
        pluginListRepository.save(plugins)
        return plugins
    }

    fun loadPlugins(): List<PluginEntry> {
        return pluginListRepository.load()
    }

    fun getCurrentPlugins(): List<PluginEntry> {
        val saved = loadPlugins().sortedBy { it.priority }
        return if (saved.isNotEmpty()) {
            saved
        } else {
            discoverPluginsFromCurrentMods()
        }
    }

    fun clearPluginList() {
        pluginListRepository.clear()
    }

    fun savePlugins(plugins: List<PluginEntry>) {
        pluginListRepository.save(plugins.sortedBy { it.priority })
    }

    fun saveCurrentPlugins(plugins: List<PluginEntry>) {
        savePlugins(plugins)
    }

    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> {
        return plugins.mapIndexed { index, plugin ->
            plugin.copy(priority = index + 1)
        }
    }

    fun buildPluginsTxt(plugins: List<PluginEntry>): String {
        return plugins
            .sortedBy { it.priority }
            .filter { it.enabled }
            .joinToString(separator = "\n") { it.pluginName }
    }

    fun buildLoadorderTxt(plugins: List<PluginEntry>): String {
        return plugins
            .sortedBy { it.priority }
            .joinToString(separator = "\n") { it.pluginName }
    }

    fun exportCurrentPluginOutputs(): Pair<String, String> {
        val plugins = getCurrentPlugins().sortedBy { it.priority }

        val pluginsTxt = buildPluginsTxt(plugins)
        val loadorderTxt = buildLoadorderTxt(plugins)

        pluginOutputRepository.savePluginsTxt(pluginsTxt)
        pluginOutputRepository.saveLoadorderTxt(loadorderTxt)

        return Pair(pluginsTxt, loadorderTxt)
    }

    fun exportSavedPluginOutputs(): Pair<String, String> {
        val plugins = loadPlugins().sortedBy { it.priority }

        val pluginsTxt = buildPluginsTxt(plugins)
        val loadorderTxt = buildLoadorderTxt(plugins)

        pluginOutputRepository.savePluginsTxt(pluginsTxt)
        pluginOutputRepository.saveLoadorderTxt(loadorderTxt)

        return Pair(pluginsTxt, loadorderTxt)
    }

    fun getPluginOutputFilePaths(): Pair<String, String> {
        return Pair(
            pluginsTxtFile.absolutePath,
            loadorderTxtFile.absolutePath
        )
    }

    fun readExportedPluginsTxt(): String {
        return pluginOutputRepository.readPluginsTxt()
    }

    fun readExportedLoadorderTxt(): String {
        return pluginOutputRepository.readLoadorderTxt()
    }

    fun buildCurrentResolvedDataGraph(): ResolvedDataGraph {
        val mods = getCurrentMods().sortedBy { it.priority }

        val contentIndexesByModId = mods.associate { mod ->
            mod.id to indexModContent(mod)
        }

        val fileIdentitiesByModId = mods.associate { mod ->
            val snapshot = modFileIndexService.getOrBuildIndex(mod)

            val identitiesByPath = snapshot.entries.associate { entry ->
                entry.normalizedPath to ResolvedFileIdentity(
                    contentHash = entry.hash,
                    fileSizeBytes = entry.sizeBytes.takeIf { it >= 0L },
                    modifiedEpochMillis = entry.modifiedEpochMillis.takeIf { it >= 0L }
                )
            }

            mod.id to identitiesByPath
        }

        val installedRecordsByModId = loadInstalledModRecords(mods)

        return ResolvedDataGraphBuilder().build(
            mods = mods,
            contentIndexesByModId = contentIndexesByModId,
            fileIdentitiesByModId = fileIdentitiesByModId,
            installedRecordsByModId = installedRecordsByModId
        )
    }

    fun buildResolvedDataGraphDebugSummary(): String {
        return buildCurrentResolvedDataGraph().toDebugSummary()
    }

    fun normalizeModPriorities(mods: List<Mod>): List<Mod> {
        return mods.mapIndexed { index, mod ->
            mod.copy(priority = index + 1)
        }
    }
    fun indexModContent(mod: Mod): ModContentIndex {
        return modContentIndexer.indexMod(mod)
    }

    fun indexCurrentModContent(): Map<String, ModContentIndex> {
        return getCurrentMods().associate { mod ->
            mod.id to modContentIndexer.indexMod(mod)
        }
    }

    fun prepareArchiveInstall(archive: File): PreparedArchiveInstall {
        return preparedArchiveInstaller.prepare(archive)
    }

    fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String = "imported_archive"
    ): Mod {
        val finalDir = preparedArchiveInstaller.finalizeInstall(
            prepared = prepared,
            selection = InstallerSelection(selectedOptionIds)
        )

        writeInstalledModRecord(
            modDir = finalDir,
            sourceType = sourceType,
            sourceArchiveName = prepared.archiveName
        )

        val installedMod = buildModFromInstalledFolder(
            modDir = finalDir,
            priority = priority,
            enabled = enabled
        )

// Build the index during install so first deploy after import does not need
// to hash the entire mod again.
        modFileIndexService.rebuildIndex(installedMod)

        return installedMod
    }

    fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) {
        preparedArchiveInstaller.cancel(prepared)
    }
    fun buildModFilePreview(mod: Mod): ModFilePreview {
        val index = indexModContent(mod)

        val winningRecords = getCurrentDataWinningRecords() + getCurrentRootWinningRecords()
        val winningByPath = winningRecords.associateBy { it.normalizedPath }

        val entries = index.entries.map { entry ->
            val winner = winningByPath[entry.normalizedPath]

            val status = when {
                entry.category == ModContentCategory.PLUGIN -> ModFilePreviewStatus.PLUGIN
                entry.category == ModContentCategory.ARCHIVE -> ModFilePreviewStatus.ARCHIVE
                entry.category == ModContentCategory.CONFIG -> ModFilePreviewStatus.CONFIG
                entry.category == ModContentCategory.SETUP_ONLY -> ModFilePreviewStatus.SETUP_ONLY
                entry.category == ModContentCategory.DOCUMENTATION -> ModFilePreviewStatus.DOCUMENTATION
                entry.category == ModContentCategory.OPTIONAL_MODULE -> ModFilePreviewStatus.OPTIONAL
                entry.category == ModContentCategory.IGNORED -> ModFilePreviewStatus.IGNORED
                entry.category == ModContentCategory.UNKNOWN -> ModFilePreviewStatus.UNKNOWN

                entry.isDeployable && winner == null ->
                    ModFilePreviewStatus.NOT_DEPLOYED

                entry.isDeployable && winner != null && winner.winningModId == mod.id ->
                    ModFilePreviewStatus.WINNING

                entry.isDeployable && winner != null && winner.winningModId != mod.id ->
                    ModFilePreviewStatus.OVERWRITTEN

                else -> ModFilePreviewStatus.UNKNOWN
            }

            ModFilePreviewEntry(
                normalizedPath = entry.normalizedPath,
                originalPath = entry.originalPath,
                status = status,
                reason = entry.reason,
                deployScope = entry.deployScope,
                isDeployable = entry.isDeployable,
                winningModName = winner?.winningModName
            )
        }

        val sortedEntries = entries.sortedBy { it.normalizedPath }

        return ModFilePreview(
            modId = mod.id,
            modName = mod.name,
            entries = sortedEntries,
            folderSummaries = buildFolderSummaries(sortedEntries)
        )
    }

    fun scanDataFolderPlugins(gameId: String): List<PluginEntry> {
        val config = getGameDeploymentConfig(gameId) ?: return emptyList()

        val foundNames = when {
            config.realDeployEnabled && !config.targetTreeUri.isNullOrBlank() -> {
                dataFolderPluginScanner.scanTreeUriDataFolder(config.targetTreeUri)
            }

            config.realDeployEnabled && validateTargetDataPath(config.targetDataPath) -> {
                dataFolderPluginScanner.scanLocalDataFolder(File(config.targetDataPath))
            }

            else -> {
                dataFolderPluginScanner.scanLocalDataFolder(deployRootDir)
            }
        }

        return foundNames.mapIndexed { index, pluginName ->
            val rule = gamePluginRules.findRule(gameId, pluginName)
            val pluginType = detectPluginType(pluginName)

            PluginEntry(
                normalizedPath = pluginName.lowercase(),
                pluginName = pluginName,
                sourceModId = rule?.sourceType ?: "unmanaged_data",
                sourceModName = when (rule?.sourceType) {
                    "base_game" -> "Base Game"
                    "official_dlc" -> "Official DLC"
                    else -> "Unmanaged Data Folder"
                },
                enabled = rule?.defaultEnabled ?: false,
                priority = rule?.orderRank ?: Int.MAX_VALUE - index,
                pluginType = pluginType,
                sourceType = rule?.sourceType ?: "unmanaged_data",
                locked = rule?.locked ?: false,
                filePresentInDataFolder = true
            )
        }
    }

    fun applyModPriorityOrder(orderedModIds: List<String>) {
        val current = getCurrentMods().sortedBy { it.priority }
        val byId = current.associateBy { it.id }

        val reordered = orderedModIds.mapNotNull { byId[it] }

        if (reordered.size != current.size) {
            throw IllegalArgumentException(
                "Could not apply mod order: expected ${current.size} mods but got ${reordered.size}."
            )
        }

        val currentIds = current.map { it.id }.toSet()
        val orderedIds = orderedModIds.toSet()

        if (currentIds != orderedIds) {
            throw IllegalArgumentException("Could not apply mod order: ordered mod IDs do not match current mods.")
        }

        saveCurrentMods(
            reordered.mapIndexed { index, mod ->
                mod.copy(priority = index + 1)
            }
        )
    }

    fun applyPluginPriorityOrder(orderedPluginPaths: List<String>) {
        val current = getCurrentPlugins().sortedBy { it.priority }
        val byPath = current.associateBy { it.normalizedPath }

        val lockedPlugins = current
            .filter { it.locked }
            .sortedBy { it.priority }

        val lockedPaths = lockedPlugins.map { it.normalizedPath }.toSet()

        val orderedUnlockedPaths = orderedPluginPaths.filterNot { it in lockedPaths }

        val currentUnlocked = current.filterNot { it.locked }
        val currentUnlockedPaths = currentUnlocked.map { it.normalizedPath }.toSet()

        if (orderedUnlockedPaths.toSet() != currentUnlockedPaths) {
            throw IllegalArgumentException("Could not apply plugin order: ordered plugin paths do not match current unlocked plugins.")
        }

        val reorderedUnlocked = orderedUnlockedPaths.mapNotNull { byPath[it] }

        if (reorderedUnlocked.size != currentUnlocked.size) {
            throw IllegalArgumentException(
                "Could not apply plugin order: expected ${currentUnlocked.size} unlocked plugins but got ${reorderedUnlocked.size}."
            )
        }

        val reordered = lockedPlugins + reorderedUnlocked

        saveCurrentPlugins(
            reordered.mapIndexed { index, plugin ->
                plugin.copy(priority = index + 1)
            }
        )
    }

    fun scanOverwriteFiles(gameId: String): OverwriteScanResult {
        val baselineRepository = getDataBaselineRepository(gameId)
        val baseline = baselineRepository.load()

        if (baseline == null) {
            return OverwriteScanResult(
                baselineExists = false,
                entries = emptyList(),
                message = "No Data baseline exists yet. Index the current target Data folder first."
            )
        }

        val currentTargetFiles = scanTargetDataFiles(gameId)
        val baselineByPath = baseline.files.associateBy { it.normalizedPath }

        val manifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val deployedPaths = manifestRepository.load()
            .map { it.normalizedPath }
            .toSet()

        val entries = currentTargetFiles
            .filterNot { it.normalizedPath in deployedPaths }
            .filterNot { shouldIgnoreOverwritePath(it.normalizedPath) }
            .mapNotNull { currentFile ->
                val baselineFile = baselineByPath[currentFile.normalizedPath]

                when {
                    baselineFile == null -> {
                        OverwriteEntry(
                            normalizedPath = currentFile.normalizedPath,
                            reason = getOverwriteReason(currentFile.normalizedPath),
                            sizeBytes = currentFile.sizeBytes,
                            modifiedEpochMillis = currentFile.modifiedEpochMillis,
                            status = "NEW"
                        )
                    }

                    hasBaselineFileChanged(baselineFile, currentFile) -> {
                        OverwriteEntry(
                            normalizedPath = currentFile.normalizedPath,
                            reason = "File changed after baseline was created",
                            sizeBytes = currentFile.sizeBytes,
                            modifiedEpochMillis = currentFile.modifiedEpochMillis,
                            status = "CHANGED"
                        )
                    }

                    else -> null
                }
            }
            .sortedBy { it.normalizedPath }

        return OverwriteScanResult(
            baselineExists = true,
            entries = entries,
            message = if (entries.isEmpty()) {
                "Overwrite is clean. No new or changed untracked files detected."
            } else {
                "Detected ${entries.size} overwrite candidate files."
            }
        )
    }

    private fun buildFolderSummaries(
        entries: List<ModFilePreviewEntry>
    ): List<ModFileFolderSummary> {
        val grouped = entries.groupBy { entry ->
            val path = entry.normalizedPath
            if (path.contains("/")) {
                path.substringBefore("/") + "/"
            } else {
                path
            }
        }

        return grouped.map { (topLevelPath, groupEntries) ->
            val winningCount = groupEntries.count { it.status == ModFilePreviewStatus.WINNING }
            val overwrittenCount = groupEntries.count { it.status == ModFilePreviewStatus.OVERWRITTEN }
            val notDeployedCount = groupEntries.count { it.status == ModFilePreviewStatus.NOT_DEPLOYED }
            val pluginCount = groupEntries.count { it.status == ModFilePreviewStatus.PLUGIN }
            val archiveCount = groupEntries.count { it.status == ModFilePreviewStatus.ARCHIVE }
            val configCount = groupEntries.count { it.status == ModFilePreviewStatus.CONFIG }
            val setupCount = groupEntries.count { it.status == ModFilePreviewStatus.SETUP_ONLY }
            val documentationCount = groupEntries.count { it.status == ModFilePreviewStatus.DOCUMENTATION }
            val optionalCount = groupEntries.count { it.status == ModFilePreviewStatus.OPTIONAL }
            val ignoredCount = groupEntries.count { it.status == ModFilePreviewStatus.IGNORED }
            val unknownCount = groupEntries.count { it.status == ModFilePreviewStatus.UNKNOWN }
            val dataFileCount = groupEntries.count {
                it.isDeployable && it.deployScope == DeployScope.DATA
            }

            val gameRootFileCount = groupEntries.count {
                it.isDeployable && it.deployScope == DeployScope.GAME_ROOT
            }

            val dominantStatus = when {
                overwrittenCount > 0 -> ModFilePreviewStatus.OVERWRITTEN
                winningCount > 0 -> ModFilePreviewStatus.WINNING
                pluginCount > 0 -> ModFilePreviewStatus.PLUGIN
                archiveCount > 0 -> ModFilePreviewStatus.ARCHIVE
                configCount > 0 -> ModFilePreviewStatus.CONFIG
                optionalCount > 0 -> ModFilePreviewStatus.OPTIONAL
                setupCount > 0 -> ModFilePreviewStatus.SETUP_ONLY
                documentationCount > 0 -> ModFilePreviewStatus.DOCUMENTATION
                ignoredCount > 0 -> ModFilePreviewStatus.IGNORED
                notDeployedCount > 0 -> ModFilePreviewStatus.NOT_DEPLOYED
                else -> ModFilePreviewStatus.UNKNOWN
            }

            ModFileFolderSummary(
                displayName = topLevelPath,
                normalizedPath = topLevelPath.removeSuffix("/"),
                isTopLevelFile = !topLevelPath.endsWith("/"),
                totalCount = groupEntries.size,
                dataFileCount = dataFileCount,
                gameRootFileCount = gameRootFileCount,
                winningCount = winningCount,
                overwrittenCount = overwrittenCount,
                notDeployedCount = notDeployedCount,
                pluginCount = pluginCount,
                archiveCount = archiveCount,
                configCount = configCount,
                setupCount = setupCount,
                documentationCount = documentationCount,
                optionalCount = optionalCount,
                ignoredCount = ignoredCount,
                unknownCount = unknownCount,
                dominantStatus = dominantStatus
            )
        }.sortedWith(
            compareBy<ModFileFolderSummary> { it.isTopLevelFile }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun detectPluginType(pluginName: String): String {
        val lower = pluginName.lowercase()
        return when {
            lower.endsWith(".esm") -> "ESM"
            lower.endsWith(".esl") -> "ESL"
            else -> "ESP"
        }
    }

    private fun isOfficialGameDataFile(gameId: String, normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()

        return when (gameId) {
            "skyrim_le" -> lower in setOf(
                "skyrim.esm",
                "update.esm",
                "dawnguard.esm",
                "hearthfires.esm",
                "dragonborn.esm",

                "skyrim - animations.bsa",
                "skyrim - interface.bsa",
                "skyrim - meshes.bsa",
                "skyrim - misc.bsa",
                "skyrim - shaders.bsa",
                "skyrim - sounds.bsa",
                "skyrim - textures.bsa",
                "skyrim - voices.bsa",
                "skyrim - voicesextra.bsa",

                "update.bsa",
                "dawnguard.bsa",
                "hearthfires.bsa",
                "dragonborn.bsa",

                "highrestexturepack01.esp",
                "highrestexturepack02.esp",
                "highrestexturepack03.esp",
                "highrestexturepack01.bsa",
                "highrestexturepack02.bsa",
                "highrestexturepack03.bsa"
            )

            "fallout_nv" -> lower in setOf(
                "falloutnv.esm",
                "deadmoney.esm",
                "honesthearts.esm",
                "oldworldblues.esm",
                "lonesomeroad.esm",
                "gunrunnersarsenal.esm",
                "classicpack.esm",
                "mercenarypack.esm",
                "tribalpack.esm",
                "caravanpack.esm"
            )

            "oblivion" -> lower in setOf(
                "oblivion.esm"
            )

            "fallout_4" -> lower in setOf(
                "fallout4.esm",
                "dlcrobot.esm",
                "dlcworkshop01.esm",
                "dlccoast.esm",
                "dlcworkshop02.esm",
                "dlcworkshop03.esm",
                "dlcnukaworld.esm"
            )

            else -> false
        }
    }

    private fun getDataBaselineFile(gameId: String): File {
        return File(
            deploymentManifestFile.parentFile,
            buildTargetScopedFileName("data_baseline", gameId)
        )
    }

    private fun getDataBaselineRepository(gameId: String): DataBaselineRepository {
        return DataBaselineRepository(getDataBaselineFile(gameId))
    }

    fun hasDataBaseline(gameId: String): Boolean {
        return getDataBaselineRepository(gameId).exists()
    }

    fun rebuildDataBaseline(gameId: String): DataBaselineSnapshot {
        val targetFiles = scanTargetDataFiles(gameId)
        val config = getGameDeploymentConfig(gameId)

        val targetDescription = when {
            config != null && config.realDeployEnabled && !config.targetTreeUri.isNullOrBlank() ->
                config.targetTreeUri ?: ""

            config != null && config.realDeployEnabled && validateTargetDataPath(config.targetDataPath) ->
                config.targetDataPath

            else ->
                deployRootDir.absolutePath
        }

        val manifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val deployedPaths = manifestRepository.load()
            .map { it.normalizedPath }
            .toSet()

        val baselineFiles = targetFiles
            .filterNot { it.normalizedPath in deployedPaths }
            .filterNot { shouldIgnoreOverwritePath(it.normalizedPath) }
            .map {
                DataBaselineFileRecord(
                    normalizedPath = it.normalizedPath,
                    sizeBytes = it.sizeBytes,
                    modifiedEpochMillis = it.modifiedEpochMillis
                )
            }
            .sortedBy { it.normalizedPath }

        val snapshot = DataBaselineSnapshot(
            gameId = gameId,
            createdAtEpochMillis = System.currentTimeMillis(),
            targetDescription = targetDescription,
            files = baselineFiles
        )

        getDataBaselineRepository(gameId).save(snapshot)
        return snapshot
    }

    private fun scanTargetDataFiles(gameId: String): List<com.shonkware.droidmodloader.engine.overwrite.TargetDataFileEntry> {
        val config = getGameDeploymentConfig(gameId)

        return when {
            config != null && config.realDeployEnabled && !config.targetTreeUri.isNullOrBlank() -> {
                overwriteScanner.scanTreeUriDataFolder(config.targetTreeUri)
            }

            config != null && config.realDeployEnabled && validateTargetDataPath(config.targetDataPath) -> {
                overwriteScanner.scanLocalDataFolder(File(config.targetDataPath))
            }

            else -> {
                overwriteScanner.scanLocalDataFolder(deployRootDir)
            }
        }
    }

    private fun hasBaselineFileChanged(
        baseline: DataBaselineFileRecord,
        current: com.shonkware.droidmodloader.engine.overwrite.TargetDataFileEntry
    ): Boolean {
        if (baseline.sizeBytes != null && current.sizeBytes != null && baseline.sizeBytes != current.sizeBytes) {
            return true
        }

        // SAF lastModified values are not always reliable, so only treat modified time as a signal
        // when both values are present and the size also changed elsewhere.
        return false
    }

    private fun shouldIgnoreOverwritePath(normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()

        return lower == "plugins.txt" ||
                lower == "loadorder.txt" ||
                lower.endsWith(".bak") ||
                lower.endsWith(".tmp") ||
                lower.endsWith(".old")
    }

    private fun getOverwriteReason(normalizedPath: String): String {
        val lower = normalizedPath.lowercase()

        return when {
            lower.endsWith(".log") ->
                "Generated log file"

            lower.startsWith("skse/plugins/") ||
                    lower.startsWith("skse/plugins/") ||
                    lower.startsWith("nvse/plugins/") ||
                    lower.startsWith("obse/plugins/") ||
                    lower.startsWith("fose/plugins/") ||
                    lower.startsWith("f4se/plugins/") ->
                "Script extender generated file"

            lower.contains("cache") ->
                "Possible generated cache file"

            lower.endsWith(".ini") ->
                "Generated or externally modified config file"

            lower.endsWith(".esp") ||
                    lower.endsWith(".esm") ||
                    lower.endsWith(".esl") ->
                "New plugin file found outside Droid Mod Loader deployment"

            else ->
                "File was created or changed after the Data baseline was indexed"
        }
    }
    private fun getDeploymentTargetIdentity(gameId: String): DeploymentTargetIdentity {
        val config = getGameDeploymentConfig(gameId)

        return when {
            config != null &&
                    config.realDeployEnabled &&
                    !config.targetTreeUri.isNullOrBlank() -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "tree_uri",
                    target = config.targetTreeUri ?: ""
                )
            }

            config != null &&
                    config.realDeployEnabled &&
                    validateTargetDataPath(config.targetDataPath) -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "real_path",
                    target = config.targetDataPath
                )
            }

            else -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "simulated",
                    target = deployRootDir.absolutePath
                )
            }
        }
    }

    private fun hashManifestKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun buildTargetScopedFileName(
        prefix: String,
        gameId: String,
        extension: String = "json"
    ): String {
        return buildTargetScopedFileNameForIdentity(
            prefix = prefix,
            identity = getDeploymentTargetIdentity(gameId),
            extension = extension
        )
    }

    private fun buildTargetScopedFileNameForIdentity(
        prefix: String,
        identity: DeploymentTargetIdentity,
        extension: String = "json"
    ): String {
        val hash = hashManifestKey(identity.stableKey())
        return "${prefix}_${identity.gameId}_${identity.mode}_$hash.$extension"
    }

    private fun getRootDeploymentTargetIdentity(gameId: String): DeploymentTargetIdentity {
        val config = getGameDeploymentConfig(gameId)

        return when {
            config != null &&
                    config.realDeployEnabled &&
                    !config.targetRootTreeUri.isNullOrBlank() -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "root_tree_uri",
                    target = config.targetRootTreeUri ?: ""
                )
            }

            config != null &&
                    config.realDeployEnabled &&
                    validateTargetDataPath(config.targetRootPath) -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "root_real_path",
                    target = config.targetRootPath
                )
            }

            else -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "root_simulated",
                    target = getSimulatedGameRootDir().absolutePath
                )
            }
        }
    }

    fun getDeploymentTargetDebugSummary(gameId: String): String {
        val identity = getDeploymentTargetIdentity(gameId)
        val manifestName = buildTargetScopedFileName("deployment_manifest", gameId)
        val rootManifestName = buildTargetScopedFileNameForIdentity(
            prefix = "deployment_manifest_root",
            identity = getRootDeploymentTargetIdentity(gameId)
        )
        val baselineName = buildTargetScopedFileName("data_baseline", gameId)


        return buildString {
            appendLine("Deployment target identity:")
            appendLine(identity.displaySummary())
            appendLine("Manifest file: $manifestName")
            appendLine("Baseline file: $baselineName")
            appendLine("Root manifest file: $rootManifestName")
        }
    }

    fun rebuildModFileIndex(modId: String): Boolean {
        val mod = getCurrentMods().firstOrNull { it.id == modId } ?: return false
        modFileIndexService.rebuildIndex(mod)
        return true
    }

    fun buildDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        return DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )
    }
    fun requireDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val result = DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )

        if (!result.canDeploy) {
            throw DeploymentPreflightException(result)
        }

        return result
    }

    private fun getDeploymentJournalFile(gameId: String): File {
        val stateDir = stateFile.parentFile ?: tempDir
        return File(stateDir, "deployment_journal_${gameId}.json")
    }

    private fun getCurrentProfileIdForJournal(): String {
        return stateFile.parentFile?.name ?: "unknown_profile"
    }

    fun getDeploymentJournalDebugSummary(gameId: String): String {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load()

        return if (record == null) {
            "No deploy journal found for $gameId."
        } else {
            record.toDebugSummary()
        }
    }

    private fun createStartedDeploymentJournal(
        gameId: String,
        plan: ScopedDeploymentPlan,
        preflight: DeploymentPreflightResult
    ): DeploymentJournalRecord {
        return DeploymentJournalRecord(
            operationId = "${System.currentTimeMillis()}_$gameId",
            gameId = gameId,
            profileId = getCurrentProfileIdForJournal(),
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = System.currentTimeMillis(),
            completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(
                dataOperationCount = plan.dataPlan.operationCount,
                rootOperationCount = plan.rootPlan.operationCount,
                totalOperationCount = plan.totalOperationCount,
                dataEstimatedCopyBytes = plan.dataPlan.estimatedBytesToCopy,
                rootEstimatedCopyBytes = plan.rootPlan.estimatedBytesToCopy,
                preflightCanDeploy = preflight.canDeploy,
                preflightErrorCount = preflight.errorCount,
                preflightWarningCount = preflight.warningCount
            ),
            resultSummary = null,
            failureMessage = null
        )
    }

    fun getDeploymentJournalStartupWarning(gameId: String): String? {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load() ?: return null

        if (record.status != DeploymentJournalStatus.STARTED) {
            return null
        }

        return buildString {
            appendLine("Previous deploy may not have finished cleanly.")
            appendLine("Game: ${record.gameId}")
            appendLine("Profile: ${record.profileId}")
            appendLine("Operation ID: ${record.operationId}")
            appendLine("Status: ${record.status}")
            appendLine("Started: ${record.startedAtEpochMillis}")
            appendLine("Data operations planned: ${record.planSummary.dataOperationCount}")
            appendLine("Game Root operations planned: ${record.planSummary.rootOperationCount}")
            appendLine("Preflight errors: ${record.planSummary.preflightErrorCount}")
            appendLine("Preflight warnings: ${record.planSummary.preflightWarningCount}")
            appendLine("This build will only warn. Recovery actions will be added later.")
        }
    }

    fun markDeploymentJournalReviewed(gameId: String): Boolean {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load() ?: return false

        if (record.status != DeploymentJournalStatus.STARTED) {
            return false
        }

        repository.markReviewed(record)
        return true
    }

    fun buildFullRedeployPlanForGame(gameId: String): ScopedDeploymentPlan {
        val dataManifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val oldDataManifest = dataManifestRepository.load()
        val dataWinningRecords = getCurrentDataWinningRecords()

        val rootManifestRepository = DeploymentManifestRepository(
            getEffectiveRootDeploymentManifestFile(gameId)
        )

        val oldRootManifest = rootManifestRepository.load()
        val rootWinningRecords = getCurrentRootWinningRecords()

        val builder = DeploymentPlanBuilder()

        val dataPlan = builder.buildFullRedeploy(
            scope = DeploymentPlanScope.DATA,
            oldManifest = oldDataManifest,
            newWinningRecords = dataWinningRecords
        )

        val rootPlan = builder.buildFullRedeploy(
            scope = DeploymentPlanScope.GAME_ROOT,
            oldManifest = oldRootManifest,
            newWinningRecords = rootWinningRecords
        )

        return ScopedDeploymentPlan(
            dataPlan = dataPlan,
            rootPlan = rootPlan
        )
    }

    fun buildFullRedeployPlanDebugSummary(gameId: String): String {
        val plan = buildFullRedeployPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker(appContext).check(
            config = config,
            plan = plan
        )

        return buildString {
            appendLine("Full Redeploy Plan")
            appendLine("This is a recovery planning check only.")
            appendLine("No files were changed.")
            appendLine()
            appendLine(buildDeploymentPlanContextSummary(gameId, config, plan))
            appendLine()
            appendLine(plan.toDebugSummary())
            appendLine()
            appendLine(preflight.toDebugSummary())
        }
    }

    private fun forceManifestToRewriteCurrentWinners(
        oldManifest: List<DeploymentRecord>,
        currentWinners: List<FileRecord>
    ): List<DeploymentRecord> {
        val currentWinnerPaths = currentWinners
            .map { it.normalizedPath }
            .toSet()

        return oldManifest.map { record ->
            if (record.normalizedPath in currentWinnerPaths) {
                record.copy(
                    hash = "__force_full_redeploy__${record.hash}"
                )
            } else {
                record
            }
        }
    }

}