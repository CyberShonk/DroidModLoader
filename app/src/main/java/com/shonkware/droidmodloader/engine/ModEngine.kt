package com.shonkware.droidmodloader.engine

import com.shonkware.droidmodloader.engine.build.StagingManager
import com.shonkware.droidmodloader.engine.build.DiffEngine
import com.shonkware.droidmodloader.engine.build.FileChange
import com.shonkware.droidmodloader.engine.conflict.ConflictResolver
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.install.ModInstaller
import com.shonkware.droidmodloader.engine.io.FileScanner
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
import com.shonkware.droidmodloader.engine.plugins.PluginDiscovery
import com.shonkware.droidmodloader.engine.data.PluginOutputRepository

data class UninstallResult(
    val removed: Boolean,
    val removedModId: String,
    val addCount: Int,
    val removeCount: Int,
    val updateCount: Int
)

class ModEngine(
    private val appContext: Context,
    private val tempDir: File,
    private val modsDir: File,
    private val stagingDir: File,
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
    private val stagingManager = StagingManager(stagingDir)
    private val stateRepository = ModStateRepository(stateFile)
    private val installedModRecordRepository = InstalledModRecordRepository()
    private val deployFileClassifier = DeployFileClassifier()
    private val deploymentManifestRepository = DeploymentManifestRepository(deploymentManifestFile)
    private val deploymentManager = DeploymentManager(deployRootDir)
    private val gameDeploymentConfigRepository = GameDeploymentConfigRepository(gameConfigFile)
    private val pluginListRepository = PluginListRepository(pluginListFile)
    private val pluginDiscovery = PluginDiscovery()
    private val pluginOutputRepository = PluginOutputRepository(
        pluginsTxtFile = pluginsTxtFile,
        loadorderTxtFile = loadorderTxtFile
    )

    fun installArchive(archive: File, priority: Int, enabled: Boolean = true): Mod {
        val extractedDir = modInstaller.installArchive(archive)
        return buildModFromInstalledFolder(extractedDir, priority, enabled)
    }

    fun buildModFromInstalledFolder(
        modDir: File,
        priority: Int,
        enabled: Boolean = true
    ): Mod {
        return Mod(
            id = modDir.name,
            name = modDir.name,
            installPath = modDir.absolutePath,
            enabled = enabled,
            priority = priority,
            modType = detectModType(modDir)
        )
    }

    fun scanMod(mod: Mod): List<ModFile> {
        val modDir = File(mod.installPath)
        val scanner = FileScanner()
        scanner.scanDirectory(modDir, modDir, mod.name)

        return convertScannerResultsToModFiles(
            modId = mod.id,
            sourceModName = mod.name,
            fileMap = scanner.getFileMap()
        )
    }

    fun scanMods(mods: List<Mod>): List<ModFile> {
        val allModFiles = mutableListOf<ModFile>()

        for (mod in mods) {
            allModFiles.addAll(scanMod(mod))
        }

        return allModFiles
    }

    fun resolve(mods: List<Mod>): List<FileRecord> {
        val modFiles = scanMods(mods)
        val deployableModFiles = filterDeployableModFiles(modFiles)
        return resolver.resolve(mods, deployableModFiles)
    }

    fun rebuildStaging(mods: List<Mod>): List<FileRecord> {
        val winningRecords = resolve(mods)
        stagingManager.rebuildStaging(winningRecords)
        return winningRecords
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
            Mod(
                id = modDir.name,
                name = modDir.name,
                installPath = modDir.absolutePath,
                enabled = true,
                priority = (index + 1) * 10,
                modType = detectModType(modDir)
            )
        }
    }

    fun rebuildStagingFromInstalledMods(): List<FileRecord> {
        val mods = getInstalledModsFromFolders()
        return rebuildStaging(mods)
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

    fun saveCurrentMods(mods: List<Mod>) {
        saveMods(mods.sortedBy { it.priority })
    }

    fun rebuildStagingFromCurrentState(): List<FileRecord> {
        val mods = getCurrentMods()
        return rebuildStaging(mods)
    }

    fun uninstallModAndApplyDiff(modId: String): UninstallResult {
        val currentMods = getCurrentMods().sortedBy { it.priority }
        val modToRemove = currentMods.firstOrNull { it.id == modId }
            ?: return UninstallResult(
                removed = false,
                removedModId = modId,
                addCount = 0,
                removeCount = 0,
                updateCount = 0
            )

        val oldWinningRecords = resolve(currentMods)

        val remainingMods = currentMods
            .filterNot { it.id == modId }
            .mapIndexed { index, mod ->
                mod.copy(priority = (index + 1) * 10)
            }

        saveCurrentMods(remainingMods)

        val newWinningRecords = resolve(remainingMods)

        val diffEngine = DiffEngine()
        val changes = diffEngine.diff(oldWinningRecords, newWinningRecords)

        stagingManager.applyChanges(changes)

        val modDir = File(modToRemove.installPath)
        if (modDir.exists()) {
            modDir.deleteRecursively()
        }

        return UninstallResult(
            removed = true,
            removedModId = modId,
            addCount = changes.count { it is FileChange.Add },
            removeCount = changes.count { it is FileChange.Remove },
            updateCount = changes.count { it is FileChange.Update }
        )
    }

    fun resetAllAppData(importsDir: File): Boolean {
        return try {
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (modsDir.exists()) modsDir.deleteRecursively()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
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
                    ?.filter { it.name.startsWith("deployment_manifest") && it.extension == "json" }
                    ?.forEach { it.delete() }
            }

            tempDir.mkdirs()
            modsDir.mkdirs()
            stagingDir.mkdirs()
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

    private fun convertScannerResultsToModFiles(
        modId: String,
        sourceModName: String,
        fileMap: Map<String, List<com.shonkware.droidmodloader.engine.io.FileInfo>>): List<ModFile> {
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

    private fun writeInstalledModRecord(
        modDir: File,
        sourceType: String,
        sourceArchiveName: String?) {
        val record = InstalledModRecord(
            modId = modDir.name,
            displayName = modDir.name,
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
            deployFileClassifier.isDeployableToCurrentStaging(scope)
        }
    }

    fun getCurrentWinningRecords(): List<FileRecord> {
        return resolve(getCurrentMods())
    }

    fun deployCurrentState(): DeploymentResult {
        val oldManifest = deploymentManifestRepository.load()
        val newWinningRecords = getCurrentWinningRecords()

        val (newManifest, result) = deploymentManager.deploy(oldManifest, newWinningRecords)
        deploymentManifestRepository.save(newManifest)

        return result
    }

    fun clearDeploymentManifest() {
        deploymentManifestRepository.clear()
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
    fun deployForGame(gameId: String): DeploymentResult {
        val config = getGameDeploymentConfig(gameId)

        val effectiveManifestFile = if (config != null) {
            File(deploymentManifestFile.parentFile, "deployment_manifest_${config.gameId}.json")
        } else {
            deploymentManifestFile
        }

        val effectiveManifestRepository = DeploymentManifestRepository(effectiveManifestFile)
        val oldManifest = effectiveManifestRepository.load()
        val newWinningRecords = getCurrentWinningRecords()

        val usingTreeUri =
            config != null &&
                    config.realDeployEnabled &&
                    !config.targetTreeUri.isNullOrBlank()

        val (newManifest, result) = if (usingTreeUri) {
            val treeUri = Uri.parse(config!!.targetTreeUri)
            val treeDeploymentManager = TreeUriDeploymentManager(
                context = appContext,
                contentResolver = appContext.contentResolver,
                treeUri = treeUri
            )
            treeDeploymentManager.deploy(oldManifest, newWinningRecords)
        } else {
            val effectiveDeployRoot = if (
                config != null &&
                config.realDeployEnabled &&
                validateTargetDataPath(config.targetDataPath)
            ) {
                File(config.targetDataPath)
            } else {
                deployRootDir
            }

            val effectiveDeploymentManager = DeploymentManager(effectiveDeployRoot)
            effectiveDeploymentManager.deploy(oldManifest, newWinningRecords)
        }

        effectiveManifestRepository.save(newManifest)
        return result
    }

    fun discoverPluginsFromCurrentMods(): List<PluginEntry> {
        val winningRecords = getCurrentWinningRecords()
        return pluginDiscovery.discoverPluginsFromWinningRecords(winningRecords)
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
        return plugins.sortedBy { it.priority }.mapIndexed { index, plugin ->
            plugin.copy(priority = (index + 1) * 10)
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

    fun readExportedPluginsTxt(): String {
        return pluginOutputRepository.readPluginsTxt()
    }

    fun readExportedLoadorderTxt(): String {
        return pluginOutputRepository.readLoadorderTxt()
    }
}