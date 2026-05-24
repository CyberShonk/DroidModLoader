package com.shonkware.droidmodloader.engine.resolve

import com.shonkware.droidmodloader.engine.index.ModContentCategory
import com.shonkware.droidmodloader.engine.model.DeployScope

data class ResolvedDataGraph(
    val profileModCount: Int,
    val enabledModCount: Int,
    val paths: List<ResolvedPath>
) {
    val totalPathCount: Int
        get() = paths.size

    val conflictCount: Int
        get() = paths.count { it.hasConflict }

    val identicalConflictCount: Int
        get() = paths.count { it.hasOnlyIdenticalProviders }

    val dataPathCount: Int
        get() = paths.count { it.deployScope == DeployScope.DATA }

    val gameRootPathCount: Int
        get() = paths.count { it.deployScope == DeployScope.GAME_ROOT }

    val deployablePathCount: Int
        get() = paths.count { it.isDeployable }

    val pluginPathCount: Int
        get() = paths.count { it.category == ModContentCategory.PLUGIN }

    val configPathCount: Int
        get() = paths.count { it.category == ModContentCategory.CONFIG }

    val unknownPathCount: Int
        get() = paths.count { it.category == ModContentCategory.UNKNOWN }

    val conflictingPaths: List<ResolvedPath>
        get() = paths.filter { it.hasConflict }

    fun toDebugSummary(maxConflictPaths: Int = 12): String {
        return buildString {
            appendLine("Resolved Data Graph")
            appendLine("  Profile mods: $profileModCount")
            appendLine("  Enabled mods: $enabledModCount")
            appendLine("  Total resolved paths: $totalPathCount")
            appendLine("  Deployable paths: $deployablePathCount")
            appendLine("  Data paths: $dataPathCount")
            appendLine("  Game Root paths: $gameRootPathCount")
            appendLine("  Plugin paths: $pluginPathCount")
            appendLine("  Config paths: $configPathCount")
            appendLine("  Unknown paths: $unknownPathCount")
            appendLine("  Conflict paths: $conflictCount")
            appendLine("  Identical conflict paths: $identicalConflictCount")

            val sampleConflicts = conflictingPaths
                .sortedBy { it.normalizedPath }
                .take(maxConflictPaths)

            if (sampleConflicts.isNotEmpty()) {
                appendLine()
                appendLine("Sample conflicts:")

                for (path in sampleConflicts) {
                    appendLine("  ${path.normalizedPath}")
                    appendLine("    Winner: ${path.winnerProvider?.modName ?: "(none)"}")
                    appendLine("    Providers: ${path.providerCount}")
                    appendLine("    Reason: ${path.reasonWinnerWon}")
                }
            }
        }
    }
}

data class ResolvedPath(
    val normalizedPath: String,
    val winnerProvider: ResolvedProvider?,
    val allProviders: List<ResolvedProvider>,
    val losingProviders: List<ResolvedProvider>,
    val identicalHashProviders: List<ResolvedProvider>,
    val category: ModContentCategory,
    val deployScope: DeployScope,
    val isDeployable: Boolean,
    val reasonWinnerWon: String
) {
    val hasConflict: Boolean
        get() = allProviders.size > 1

    val hasOnlyIdenticalProviders: Boolean
        get() {
            val winnerHash = winnerProvider?.contentHash
            return hasConflict &&
                    !winnerHash.isNullOrBlank() &&
                    allProviders.all { it.contentHash == winnerHash }
        }

    val providerCount: Int
        get() = allProviders.size
}

data class ResolvedProvider(
    val modId: String,
    val modName: String,
    val modPriority: Int,
    val sourceArchiveName: String?,
    val normalizedPath: String,
    val originalPath: String,
    val contentHash: String?,
    val fileSizeBytes: Long?,
    val category: ModContentCategory,
    val deployScope: DeployScope,
    val isDeployable: Boolean,
    val reason: String
)

data class ResolvedFileIdentity(
    val contentHash: String?,
    val fileSizeBytes: Long?,
    val modifiedEpochMillis: Long?
)