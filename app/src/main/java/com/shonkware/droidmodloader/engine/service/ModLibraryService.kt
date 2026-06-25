package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.UninstallResult
import com.shonkware.droidmodloader.engine.conflict.ConflictResolver
import com.shonkware.droidmodloader.engine.data.InstalledModRecordRepository
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModContentIndexer
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.install.InstallerSelection
import com.shonkware.droidmodloader.engine.install.ModDisplayNameNormalizer
import com.shonkware.droidmodloader.engine.install.ModInstaller
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstaller
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.rules.DeployFileClassifier
import java.io.File
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecovery
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult

internal class ModLibraryService(
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
    private val modContentIndexer = ModContentIndexer()
    private val modFileIndexService = ModFileIndexService(
        ModFileIndexRepository(File(stateFile.parentFile, "mod_file_indexes"))
    )
    private val preparedArchiveInstaller = PreparedArchiveInstaller(
        tempDir = tempDir,
        modsDir = modsDir
    )

    private val installReplacementRecovery =
        InstallReplacementRecovery()

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

    fun recoverInterruptedInstallReplacements():
            List<InstallReplacementRecoveryResult> {
        return installReplacementRecovery.recoverAll(modsDir)
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


    fun rebuildModFileIndex(modId: String): Boolean {
        val mod = getCurrentMods().firstOrNull { it.id == modId } ?: return false
        modFileIndexService.rebuildIndex(mod)
        return true
    }
}
