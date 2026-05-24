package com.shonkware.droidmodloader.engine.resolve

import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.Mod

class ResolvedDataGraphBuilder {

    fun build(
        mods: List<Mod>,
        contentIndexesByModId: Map<String, ModContentIndex>,
        fileIdentitiesByModId: Map<String, Map<String, ResolvedFileIdentity>>,
        installedRecordsByModId: Map<String, InstalledModRecord>
    ): ResolvedDataGraph {
        val enabledMods = mods
            .filter { it.enabled }
            .sortedBy { it.priority }

        val providersByPath = linkedMapOf<String, MutableList<ResolvedProvider>>()

        for (mod in enabledMods) {
            val contentIndex = contentIndexesByModId[mod.id] ?: continue
            val fileIdentities = fileIdentitiesByModId[mod.id].orEmpty()
            val installedRecord = installedRecordsByModId[mod.id]

            for (entry in contentIndex.entries) {
                val identity = fileIdentities[entry.normalizedPath]

                val provider = ResolvedProvider(
                    modId = mod.id,
                    modName = mod.name,
                    modPriority = mod.priority,
                    sourceArchiveName = installedRecord?.sourceArchiveName,
                    normalizedPath = entry.normalizedPath,
                    originalPath = entry.originalPath,
                    contentHash = identity?.contentHash,
                    fileSizeBytes = identity?.fileSizeBytes,
                    category = entry.category,
                    deployScope = entry.deployScope,
                    isDeployable = entry.isDeployable,
                    reason = entry.reason
                )

                providersByPath
                    .getOrPut(entry.normalizedPath) { mutableListOf() }
                    .add(provider)
            }
        }

        val resolvedPaths = providersByPath.map { (normalizedPath, providers) ->
            val sortedProviders = providers.sortedBy { it.modPriority }

            val winner = sortedProviders.lastOrNull { it.isDeployable }
                ?: sortedProviders.lastOrNull()

            val losingProviders = if (winner == null) {
                emptyList()
            } else {
                sortedProviders.filterNot { provider ->
                    provider.modId == winner.modId &&
                            provider.originalPath == winner.originalPath &&
                            provider.normalizedPath == winner.normalizedPath
                }
            }

            val identicalHashProviders = if (winner?.contentHash.isNullOrBlank()) {
                emptyList()
            } else {
                sortedProviders.filter { provider ->
                    provider.contentHash == winner?.contentHash &&
                            !(provider.modId == winner.modId &&
                                    provider.originalPath == winner.originalPath &&
                                    provider.normalizedPath == winner.normalizedPath)
                }
            }

            ResolvedPath(
                normalizedPath = normalizedPath,
                winnerProvider = winner,
                allProviders = sortedProviders,
                losingProviders = losingProviders,
                identicalHashProviders = identicalHashProviders,
                category = winner?.category ?: sortedProviders.first().category,
                deployScope = winner?.deployScope ?: sortedProviders.first().deployScope,
                isDeployable = winner?.isDeployable ?: false,
                reasonWinnerWon = buildWinnerReason(
                    winner = winner,
                    providers = sortedProviders,
                    identicalHashProviders = identicalHashProviders
                )
            )
        }.sortedBy { it.normalizedPath }

        return ResolvedDataGraph(
            profileModCount = mods.size,
            enabledModCount = enabledMods.size,
            paths = resolvedPaths
        )
    }

    private fun buildWinnerReason(
        winner: ResolvedProvider?,
        providers: List<ResolvedProvider>,
        identicalHashProviders: List<ResolvedProvider>
    ): String {
        if (winner == null) {
            return "No provider selected."
        }

        if (providers.size == 1) {
            return "Only provider for this path."
        }

        if (identicalHashProviders.isNotEmpty() &&
            identicalHashProviders.size == providers.size - 1
        ) {
            return "Higher-priority provider selected, but all providers appear to have identical content."
        }

        return "Highest-priority enabled provider selected."
    }
}