package com.shonkware.droidmodloader.engine

import android.content.Context
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanResult
import com.shonkware.droidmodloader.engine.plugins.PluginApplicationResult
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraph
import com.shonkware.droidmodloader.engine.service.DeploymentService
import com.shonkware.droidmodloader.engine.service.DownloadedArchiveService
import com.shonkware.droidmodloader.engine.service.ModInspectionService
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import com.shonkware.droidmodloader.engine.service.PluginManagementService
import java.io.File

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
    private val deploymentService = DeploymentService(
        appFilesDir = appContext.filesDir,
        tempDir = tempDir,
        stateFile = stateFile,
        deploymentManifestFile = deploymentManifestFile,
        deployRootDir = deployRootDir,
        gameConfigFile = gameConfigFile,
        currentDataWinningRecords = modLibraryService::getCurrentDataWinningRecords,
        currentRootWinningRecords = modLibraryService::getCurrentRootWinningRecords
    )
    private val modInspectionService = ModInspectionService(
        modFileIndexDir = File(stateFile.parentFile, "mod_file_indexes"),
        deploymentManifestFile = deploymentManifestFile,
        deployRootDir = deployRootDir,
        currentMods = modLibraryService::getCurrentMods,
        indexContent = modLibraryService::indexModContent,
        installedRecords = modLibraryService::loadInstalledModRecords,
        dataWinningRecords = modLibraryService::getCurrentDataWinningRecords,
        rootWinningRecords = modLibraryService::getCurrentRootWinningRecords,
        deploymentConfig = deploymentService::getGameDeploymentConfig,
        isValidTargetPath = deploymentService::validateTargetDataPath,
        effectiveManifestFile = deploymentService::effectiveDataManifestFile,
        targetScopedFileName = deploymentService::targetScopedFileName
    )
    private val downloadedArchiveService = DownloadedArchiveService(
        archiveLibraryDir = archiveLibraryDir,
        downloadedArchiveListFile = downloadedArchiveListFile
    )
    private val pluginManagementService = PluginManagementService(
        pluginListFile = pluginListFile,
        pluginsTxtFile = pluginsTxtFile,
        loadorderTxtFile = loadorderTxtFile,
        deployRootDir = deployRootDir,
        getCurrentMods = modLibraryService::getCurrentMods,
        getGameDeploymentConfig = deploymentService::getGameDeploymentConfig,
        validateTargetDataPath = deploymentService::validateTargetDataPath
    )
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
    fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>) =
        deploymentService.saveGameDeploymentConfigs(configs)
    fun loadGameDeploymentConfigs(): List<GameDeploymentConfig> =
        deploymentService.loadGameDeploymentConfigs()
    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? =
        deploymentService.getGameDeploymentConfig(gameId)
    fun validateTargetDataPath(path: String): Boolean = deploymentService.validateTargetDataPath(path)
    fun deployForGame(gameId: String): ScopedDeploymentResult = deploymentService.deployForGame(gameId)
    fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult =
        deploymentService.forceFullRedeployForGame(gameId)
    fun buildDeploymentPlanForGame(gameId: String): ScopedDeploymentPlan =
        deploymentService.buildDeploymentPlanForGame(gameId)
    fun buildDeploymentPlanDebugSummary(gameId: String): String =
        deploymentService.buildDeploymentPlanDebugSummary(gameId)

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

    fun buildCurrentResolvedDataGraph(): ResolvedDataGraph =
        modInspectionService.buildCurrentResolvedDataGraph()
    fun buildResolvedDataGraphDebugSummary(): String =
        modInspectionService.buildResolvedDataGraphDebugSummary()

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
    fun buildModFilePreview(mod: Mod): ModFilePreview =
        modInspectionService.buildModFilePreview(mod)

    fun scanDataFolderPlugins(gameId: String): List<PluginEntry> =
        pluginManagementService.scanDataFolderPlugins(gameId)
    fun applyModPriorityOrder(orderedModIds: List<String>) =
        modLibraryService.applyModPriorityOrder(orderedModIds)
    fun applyPluginPriorityOrder(orderedPluginPaths: List<String>) =
        pluginManagementService.applyPluginPriorityOrder(orderedPluginPaths)
    fun scanOverwriteFiles(gameId: String): OverwriteScanResult =
        modInspectionService.scanOverwriteFiles(gameId)
    fun hasDataBaseline(gameId: String): Boolean = modInspectionService.hasDataBaseline(gameId)
    fun rebuildDataBaseline(gameId: String): DataBaselineSnapshot =
        modInspectionService.rebuildDataBaseline(gameId)

    fun getDeploymentTargetDebugSummary(gameId: String): String =
        deploymentService.getDeploymentTargetDebugSummary(gameId)

    fun rebuildModFileIndex(modId: String): Boolean = modLibraryService.rebuildModFileIndex(modId)
    fun buildDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult =
        deploymentService.buildDeploymentPreflightForGame(gameId)
    fun requireDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult =
        deploymentService.requireDeploymentPreflightForGame(gameId)
    fun getDeploymentJournalDebugSummary(gameId: String): String =
        deploymentService.getDeploymentJournalDebugSummary(gameId)
    fun getDeploymentJournalStartupWarning(gameId: String): String? =
        deploymentService.getDeploymentJournalStartupWarning(gameId)
    fun markDeploymentJournalReviewed(gameId: String): Boolean =
        deploymentService.markDeploymentJournalReviewed(gameId)
    fun buildFullRedeployPlanForGame(gameId: String): ScopedDeploymentPlan =
        deploymentService.buildFullRedeployPlanForGame(gameId)
    fun buildFullRedeployPlanDebugSummary(gameId: String): String =
        deploymentService.buildFullRedeployPlanDebugSummary(gameId)

    fun registerDownloadedArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String? = null,
        sourceUrl: String? = null
    ): DownloadedArchiveRecord = downloadedArchiveService.registerDownloadedArchive(
        archiveFile = archiveFile,
        originalDisplayName = originalDisplayName,
        sourcePath = sourcePath,
        sourceUrl = sourceUrl
    )
    fun getDownloadedArchives(): List<DownloadedArchiveRecord> =
        downloadedArchiveService.getDownloadedArchives()
    fun getDownloadedArchiveById(archiveId: String?): DownloadedArchiveRecord? =
        downloadedArchiveService.getDownloadedArchiveById(archiveId)
    fun markDownloadedArchiveInstalled(archiveId: String?, installedModId: String) =
        downloadedArchiveService.markDownloadedArchiveInstalled(archiveId, installedModId)
    fun buildDownloadedArchiveSummary(): String =
        downloadedArchiveService.buildDownloadedArchiveSummary()

}
