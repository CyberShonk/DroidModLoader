package com.shonkware.droidmodloader.engine

import android.content.Context
import com.shonkware.droidmodloader.engine.baseline.DataBaselineFileRecord
import com.shonkware.droidmodloader.engine.baseline.DataBaselineRepository
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.GameDeploymentConfigRepository
import com.shonkware.droidmodloader.engine.deploy.DeploymentManager
import com.shonkware.droidmodloader.engine.deploy.DeploymentResult
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalResultSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanBuilder
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanScope
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightChecker
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRepository
import com.shonkware.droidmodloader.engine.index.ModContentCategory
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewEntry
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanResult
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanner
import com.shonkware.droidmodloader.engine.plugins.PluginApplicationResult
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraph
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraphBuilder
import com.shonkware.droidmodloader.engine.resolve.ResolvedFileIdentity
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import com.shonkware.droidmodloader.engine.service.PluginManagementService
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import java.io.File
import java.security.MessageDigest

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
    private val loadorderTxtFile: File,
    private val archiveLibraryDir: File,
    private val downloadedArchiveListFile: File
) {

    private val modLibraryService = ModLibraryService(
        tempDir = tempDir,
        modsDir = modsDir,
        stateFile = stateFile,
        deploymentManifestFile = deploymentManifestFile,
        deployRootDir = deployRootDir,
        gameConfigFile = gameConfigFile,
        pluginListFile = pluginListFile,
        pluginsTxtFile = pluginsTxtFile,
        loadorderTxtFile = loadorderTxtFile
    )
    private val downloadedArchiveRepository = DownloadedArchiveRepository(
        archiveLibraryDir = archiveLibraryDir,
        archiveListFile = downloadedArchiveListFile
    )
    private val gameDeploymentConfigRepository = GameDeploymentConfigRepository(gameConfigFile)
    private val modFileIndexService = ModFileIndexService(
        ModFileIndexRepository(File(stateFile.parentFile, "mod_file_indexes"))
    )
    private val overwriteScanner = OverwriteScanner()
    private val directPathValidator = DirectPathValidator()
    private val pluginManagementService by lazy {
        PluginManagementService(
            pluginListFile = pluginListFile,
            pluginsTxtFile = pluginsTxtFile,
            loadorderTxtFile = loadorderTxtFile,
            deployRootDir = deployRootDir,
            getCurrentMods = modLibraryService::getCurrentMods,
            getGameDeploymentConfig = ::getGameDeploymentConfig,
            validateTargetDataPath = ::validateTargetDataPath
        )
    }
    fun buildModFromInstalledFolder(modDir: File, priority: Int, enabled: Boolean = true): Mod =
        modLibraryService.buildModFromInstalledFolder(modDir, priority, enabled)
    fun scanMod(mod: Mod): List<ModFile> = modLibraryService.scanMod(mod)
    fun scanMods(mods: List<Mod>): List<ModFile> = modLibraryService.scanMods(mods)
    fun resolve(mods: List<Mod>): List<FileRecord> = modLibraryService.resolve(mods)
    fun saveMods(mods: List<Mod>) = modLibraryService.saveMods(mods)
    fun loadMods(): List<Mod> = modLibraryService.loadMods()
    fun getInstalledModsFromFolders(): List<Mod> = modLibraryService.getInstalledModsFromFolders()
    fun saveInstalledModsFromFolders(): List<Mod> = modLibraryService.saveInstalledModsFromFolders()
    fun getCurrentMods(): List<Mod> = modLibraryService.getCurrentMods()
    fun getEnabledCurrentMods(): List<Mod> = modLibraryService.getEnabledCurrentMods()
    fun saveCurrentMods(mods: List<Mod>) = modLibraryService.saveCurrentMods(mods)
    fun uninstallModAndApplyDiff(modId: String): UninstallResult =
        modLibraryService.uninstallModAndApplyDiff(modId)
    fun resetAllAppData(importsDir: File): Boolean = modLibraryService.resetAllAppData(importsDir)
    fun hasSavedState(): Boolean = modLibraryService.hasSavedState()
    fun getCurrentModSummary(): Triple<Int, Int, Boolean> = modLibraryService.getCurrentModSummary()
    fun installArchiveWithRecord(
        archive: File,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String = "imported_zip"
    ): Mod = modLibraryService.installArchiveWithRecord(archive, priority, enabled, sourceType)
    fun registerExistingInstalledFolderWithRecord(
        modDir: File,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String
    ): Mod = modLibraryService.registerExistingInstalledFolderWithRecord(modDir, priority, enabled, sourceType)
    fun loadInstalledModRecord(mod: Mod): InstalledModRecord? =
        modLibraryService.loadInstalledModRecord(mod)
    fun loadInstalledModRecords(mods: List<Mod>): Map<String, InstalledModRecord> =
        modLibraryService.loadInstalledModRecords(mods)
    fun getDeployScopeForPath(normalizedPath: String): DeployScope =
        modLibraryService.getDeployScopeForPath(normalizedPath)
    fun classifyModFiles(modFiles: List<ModFile>): Map<DeployScope, List<ModFile>> =
        modLibraryService.classifyModFiles(modFiles)
    fun filterDeployableModFiles(modFiles: List<ModFile>): List<ModFile> =
        modLibraryService.filterDeployableModFiles(modFiles)
    fun getCurrentDataWinningRecords(): List<FileRecord> =
        modLibraryService.getCurrentDataWinningRecords()
    fun getCurrentRootWinningRecords(): List<FileRecord> =
        modLibraryService.getCurrentRootWinningRecords()
    fun getCurrentWinningRecords(): List<FileRecord> = modLibraryService.getCurrentWinningRecords()
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
        return directPathValidator.validateDirectory(
            path = path,
            requireWritable = true
        ).isValid
    }
    fun deployForGame(gameId: String): ScopedDeploymentResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker().check(
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

        val preflight = DeploymentPreflightChecker().check(
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
        targetPath: String,
        fallbackRootDir: File,
        backupRootDir: File
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        val deployTarget = if (realDeployEnabled && validateTargetDataPath(targetPath)) {
            File(targetPath)
        } else {
            fallbackRootDir
        }

        return DeploymentManager(
            deployRootDir = deployTarget,
            backupRootDir = backupRootDir
        ).deploy(oldManifest, newWinningRecords)
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

        val preflight = DeploymentPreflightChecker().check(
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
            config.dataPathReselectionRequired -> "reselection required"
            config.targetDataPath.isNotBlank() -> "direct path selected"
            else -> "not selected"
        }

        val rootTargetStatus = when {
            config == null -> "no config"
            config.rootPathReselectionRequired -> "reselection required"
            config.targetRootPath.isNotBlank() -> "direct path selected"
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

        return !config.rootPathReselectionRequired &&
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
    fun discoverPluginsFromCurrentMods(): List<PluginEntry> = pluginManagementService.discoverPluginsFromCurrentMods()
    fun saveDiscoveredPlugins(): List<PluginEntry> = pluginManagementService.saveDiscoveredPlugins()
    fun loadPlugins(): List<PluginEntry> = pluginManagementService.loadPlugins()
    fun getCurrentPlugins(): List<PluginEntry> = pluginManagementService.getCurrentPlugins()
    fun clearPluginList() = pluginManagementService.clearPluginList()
    fun savePlugins(plugins: List<PluginEntry>) = pluginManagementService.savePlugins(plugins)
    fun saveCurrentPlugins(plugins: List<PluginEntry>) = pluginManagementService.saveCurrentPlugins(plugins)
    fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> =
        pluginManagementService.normalizePluginPriorities(plugins)
    fun applySavedPluginConfiguration(gameId: String): PluginApplicationResult =
        pluginManagementService.applySavedPluginConfiguration(gameId)
    fun readExportedPluginsTxt(): String = pluginManagementService.readExportedPluginsTxt()
    fun readExportedLoadorderTxt(): String = pluginManagementService.readExportedLoadorderTxt()

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
    fun normalizeModPriorities(mods: List<Mod>): List<Mod> = modLibraryService.normalizeModPriorities(mods)
    fun indexModContent(mod: Mod): ModContentIndex = modLibraryService.indexModContent(mod)
    fun indexCurrentModContent(): Map<String, ModContentIndex> = modLibraryService.indexCurrentModContent()
    fun prepareArchiveInstall(archive: File): PreparedArchiveInstall =
        modLibraryService.prepareArchiveInstall(archive)
    fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        enabled: Boolean = true,
        sourceType: String = "imported_archive"
    ): Mod = modLibraryService.finalizePreparedArchiveInstall(
        prepared,
        selectedOptionIds,
        priority,
        enabled,
        sourceType
    )
    fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) =
        modLibraryService.cancelPreparedArchiveInstall(prepared)
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
    fun scanDataFolderPlugins(gameId: String): List<PluginEntry> =
        pluginManagementService.scanDataFolderPlugins(gameId)
    fun applyModPriorityOrder(orderedModIds: List<String>) =
        modLibraryService.applyModPriorityOrder(orderedModIds)
    fun applyPluginPriorityOrder(orderedPluginPaths: List<String>) =
        pluginManagementService.applyPluginPriorityOrder(orderedPluginPaths)

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

        return baseline.modifiedEpochMillis != null &&
                current.modifiedEpochMillis != null &&
                baseline.modifiedEpochMillis != current.modifiedEpochMillis
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
    fun rebuildModFileIndex(modId: String): Boolean = modLibraryService.rebuildModFileIndex(modId)

    fun buildDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        return DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )
    }
    fun requireDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val result = DeploymentPreflightChecker().check(
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

        val preflight = DeploymentPreflightChecker().check(
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

    fun registerDownloadedArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String? = null,
        sourceUrl: String? = null
    ): DownloadedArchiveRecord {
        return downloadedArchiveRepository.registerArchive(
            archiveFile = archiveFile,
            originalDisplayName = originalDisplayName,
            sourcePath = sourcePath,
            sourceUrl = sourceUrl
        )
    }
    fun getDownloadedArchives(): List<DownloadedArchiveRecord> {
        return downloadedArchiveRepository.load()
    }
    fun getDownloadedArchiveById(archiveId: String?): DownloadedArchiveRecord? {
        return downloadedArchiveRepository.findById(archiveId)
    }
    fun markDownloadedArchiveInstalled(
        archiveId: String?,
        installedModId: String
    ) {
        downloadedArchiveRepository.markInstalled(
            archiveId = archiveId,
            installedModId = installedModId
        )
    }
    fun buildDownloadedArchiveSummary(): String {
        return downloadedArchiveRepository.buildSummary()
    }

}
