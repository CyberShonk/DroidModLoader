package com.shonkware.droidmodloader.engine.conflict

import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModFile
import com.shonkware.droidmodloader.engine.model.EffectiveFileProvider
import com.shonkware.droidmodloader.engine.model.EffectiveResolvedPath
import com.shonkware.droidmodloader.engine.model.EffectiveSourceIdentity
import com.shonkware.droidmodloader.engine.model.EffectiveSourcePath
import java.io.File

class ConflictResolver {

    /** The staged role-qualified projection reuses this winner owner. Overwrite is a
     * distinct source kind, always last; no fake enabled Mod or priority arithmetic. */
    internal fun resolveEffectiveProviders(files: List<EffectiveFileProvider>): List<EffectiveResolvedPath> {
        val unique = hashSetOf<Triple<EffectiveSourceIdentity, DeployScope, String>>()
        files.forEach { file ->
            require(unique.add(Triple(file.provider.identity, file.role, file.indexEntry.normalizedPath))) { "Duplicate source provider/path" }
        }
        val ordered = files.sortedWith(compareBy<EffectiveFileProvider> {
            if (it.provider.identity is EffectiveSourceIdentity.ProfileOverwrite) 1 else 0
        }.thenBy { it.provider.libraryPriority ?: 0 })
        return ordered.groupBy { EffectiveSourcePath(it.role, it.indexEntry.normalizedPath) }.map { (path, providers) ->
            val winner = providers.lastOrNull { it.contentEntry.isDeployable } ?: providers.last()
            EffectiveResolvedPath(path, winner, providers, providers.filterNot { it === winner })
        }.sortedWith(compareBy<EffectiveResolvedPath> { it.path.role.name }.thenBy { it.path.normalizedPath })
    }

    fun resolve(mods: List<Mod>, modFiles: List<ModFile>): List<FileRecord> {
        val enabledMods = mods
            .filter { it.enabled }
            .sortedBy { it.priority }

        val modById = enabledMods.associateBy { it.id }

        val winningFiles = linkedMapOf<String, FileRecord>()

        for (mod in enabledMods) {
            val filesForMod = modFiles.filter { it.modId == mod.id }

            for (modFile in filesForMod) {
                val sourceFilePath = File(mod.installPath, modFile.originalPath).absolutePath

                val record = FileRecord(
                    normalizedPath = modFile.normalizedPath,
                    winningModId = mod.id,
                    winningModName = mod.name,
                    sourceFilePath = sourceFilePath,
                    hash = modFile.hash
                )

                winningFiles[modFile.normalizedPath] = record
            }
        }

        return winningFiles.values.toList()
    }
}