package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.baseline.DataBaselineFileRecord
import com.shonkware.droidmodloader.engine.baseline.DataBaselineRepository
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.integrity.IntegrityInput
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.ModType
import org.json.JSONArray
import java.nio.file.Files
import com.shonkware.droidmodloader.engine.index.ModContentCategory
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewEntry
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.deploy.ResolvedDeploymentTarget
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanResult
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanner
import com.shonkware.droidmodloader.engine.overwrite.TargetDataFileEntry
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraph
import com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraphBuilder
import com.shonkware.droidmodloader.engine.resolve.ResolvedFileIdentity
import java.io.File

internal class ModInspectionService(
    modFileIndexDir: File,
    private val currentMods: () -> List<Mod>,
    private val indexContent: (Mod) -> ModContentIndex,
    private val installedRecords: (List<Mod>) -> Map<String, InstalledModRecord>,
    private val dataWinningRecords: () -> List<FileRecord>,
    private val rootWinningRecords: () -> List<FileRecord>,
    private val resolvedDataTarget: (String) -> ResolvedDeploymentTarget
) {
    private val modFileIndexService = ModFileIndexService(
        ModFileIndexRepository(modFileIndexDir)
    )
    private val overwriteScanner = OverwriteScanner()
    private val integrityIndexRepository = ModFileIndexRepository(modFileIndexDir)

    fun captureInstallIntegrityInputs(modsDir: File, stateFile: File): List<IntegrityInput> {
        val integrity = ManagedContentIntegrity
        return try {
            require(!Files.isSymbolicLink(modsDir.toPath())) { "Aliased managed storage root" }
            val array = JSONArray(integrity.readEvidence(stateFile))
            require(array.length() <= 1_000) { "Recorded install limit exceeded" }
            var remainingFiles = integrity.MAX_RECORDED_FILES
            val ids = mutableSetOf<String>()
            val roots = mutableSetOf<String>()
            (0 until array.length()).map { index ->
                val record = array.getJSONObject(index)
                val id = record.getString("id")
                val path = record.getString("installPath")
                require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate mod identity" }
                val root = File(path)
                val managedPath = modsDir.canonicalFile.toPath()
                val canonical = root.canonicalFile.toPath()
                require(root.isAbsolute && canonical != managedPath && canonical.startsWith(managedPath) &&
                    root.absoluteFile.toPath().normalize().startsWith(modsDir.absoluteFile.toPath().normalize()) &&
                    roots.add(canonical.toString())) { "Unsafe or duplicate managed install path" }
                var component = root.toPath()
                while (component != modsDir.toPath() && component.parent != null) {
                    require(!Files.isSymbolicLink(component)) { "Aliased managed install path" }
                    component = component.parent
                }
                val mod = Mod(id, record.getString("name"), path, false, 0,
                    ModType.ARCHIVE)
                integrity.capture("Installed mod", id, root, integrityIndexRepository.getIndexFile(mod)) {
                    val records = integrity.indexRecords(it, id, path)
                    require(records.size <= remainingFiles) { "Aggregate recorded install file limit exceeded" }
                    remainingFiles -= records.size
                    records
                }
            }
        } catch (failure: Exception) {
            listOf(IntegrityInput(
                "Installed mods", "Recorded install paths", null, stateFile, integrity.revision(stateFile),
                emptyList(), integrity.evidenceProblem(stateFile, failure)
            ))
        }
    }

    private fun getCurrentMods(): List<Mod> = currentMods()
    private fun indexModContent(mod: Mod): ModContentIndex = indexContent(mod)
    private fun loadInstalledModRecords(mods: List<Mod>): Map<String, InstalledModRecord> =
        installedRecords(mods)
    private fun getCurrentDataWinningRecords(): List<FileRecord> = dataWinningRecords()
    private fun getCurrentRootWinningRecords(): List<FileRecord> = rootWinningRecords()
    private fun getResolvedDataTarget(gameId: String): ResolvedDeploymentTarget =
        resolvedDataTarget(gameId)

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

    fun scanOverwriteFiles(gameId: String): OverwriteScanResult {
        val target = getResolvedDataTarget(gameId)
        if (!target.canDeploy) {
            return OverwriteScanResult(
                baselineExists = false,
                entries = emptyList(),
                message = buildString {
                    append("Data target is unavailable. Overwrite scanning did not read another target's state.")
                    if (!target.unavailableReason.isNullOrBlank()) {
                        append(" ${target.unavailableReason}")
                    }
                }
            )
        }

        val baselineRepository = getDataBaselineRepository(target)
        val baseline = baselineRepository.load()

        if (baseline == null) {
            return OverwriteScanResult(
                baselineExists = false,
                entries = emptyList(),
                message = "No Data baseline exists yet. Index the current target Data folder first."
            )
        }

        val currentTargetFiles = scanTargetDataFiles(target)
        val baselineByPath = baseline.files.associateBy { it.normalizedPath }
        val deployedPaths = DeploymentManifestRepository(target.manifestFile)
            .load()
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

    private fun getDataBaselineRepository(
        target: ResolvedDeploymentTarget
    ): DataBaselineRepository {
        return DataBaselineRepository(
            checkNotNull(target.baselineFile) {
                "Resolved Data target does not provide a baseline file."
            }
        )
    }

    fun hasDataBaseline(gameId: String): Boolean {
        val target = getResolvedDataTarget(gameId)
        return target.canDeploy && getDataBaselineRepository(target).exists()
    }

    fun rebuildDataBaseline(gameId: String): DataBaselineSnapshot {
        val target = getResolvedDataTarget(gameId)
        check(target.canDeploy) {
            buildString {
                append("Data target is unavailable. Refusing to rebuild a baseline using another target's state.")
                if (!target.unavailableReason.isNullOrBlank()) {
                    append(" ${target.unavailableReason}")
                }
            }
        }

        val targetFiles = scanTargetDataFiles(target)
        val deployedPaths = DeploymentManifestRepository(target.manifestFile)
            .load()
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
            targetDescription = target.identity.target,
            files = baselineFiles
        )

        getDataBaselineRepository(target).save(snapshot)
        return snapshot
    }

    private fun scanTargetDataFiles(
        target: ResolvedDeploymentTarget
    ): List<TargetDataFileEntry> {
        return overwriteScanner.scanLocalDataFolder(
            target.requireDeployDirectory()
        )
    }

    private fun hasBaselineFileChanged(
        baseline: DataBaselineFileRecord,
        current: TargetDataFileEntry
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
}
