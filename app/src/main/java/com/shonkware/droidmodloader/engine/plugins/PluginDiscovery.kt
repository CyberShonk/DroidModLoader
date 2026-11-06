package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File

class PluginDiscovery {

    /** Staged supplied-source path. Legacy discovery and its callers remain unchanged. */
    fun observeCapturedWinners(
        gameId: String,
        capture: CapturedPluginWinners,
        request: PluginHeaderRequest
    ): PluginSourceObservations {
        if (!capture.complete) return PluginSourceObservations(emptyList(), PluginFactFailure.CAPTURE_UNAVAILABLE)
        val observations = mutableListOf<PluginHeaderObservation>()
        val identities = mutableSetOf<String>()
        val reader = PluginHeaderReader()
        try {
            for (record in capture.winningRecords) {
                request.checkCancellation()
                // These are resolver Data-relative keys, not paths to reconstruct or open.
                if ('/' in record.normalizedPath || '\\' in record.normalizedPath || !isPluginPath(record.normalizedPath)) continue
                if (record.normalizedPath.any { it.isISOControl() || it == ':' } || record.normalizedPath.startsWith('.')) {
                    return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.UNSAFE_SOURCE)
                }
                if (request.candidatesRead == PluginHeaderRequest.MAX_CANDIDATES) {
                    return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.CANDIDATE_LIMIT)
                }
                if (!identities.add(pluginIdentity(record.normalizedPath))) {
                    return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.AMBIGUOUS_IDENTITY)
                }
                val root = capture.ownedProviderRoots[record.winningModId]
                    ?: return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.CAPTURE_UNAVAILABLE)
                val relativeSource = File(record.sourceFilePath).absoluteFile
                    .relativeToOrNull(File(root).absoluteFile)?.path
                if (relativeSource == null || PathUtils.normalize(relativeSource) != pluginIdentity(record.normalizedPath)) {
                    return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.UNSAFE_SOURCE)
                }
                val observation = reader.read(gameId, PluginHeaderSource(
                    capture.profileId, capture.snapshotId, PluginSourceScope.MANAGED_CANDIDATE,
                    root, record.sourceFilePath, record.winningModId
                ), request)
                if (observation.failure == PluginFactFailure.CANDIDATE_LIMIT || observation.failure == PluginFactFailure.CANCELED) {
                    return PluginSourceObservations(immutablePluginFacts(observations), observation.failure)
                }
                observations.add(observation)
            }
        } catch (failure: PluginFactException) {
            return PluginSourceObservations(immutablePluginFacts(observations), failure.failure)
        } catch (_: java.nio.file.InvalidPathException) {
            return PluginSourceObservations(immutablePluginFacts(observations), PluginFactFailure.UNSAFE_SOURCE)
        }
        return PluginSourceObservations(immutablePluginFacts(observations))
    }

    fun discoverPluginsFromWinningRecords(winningRecords: List<FileRecord>): List<PluginEntry> {
        val pluginRecords = winningRecords
            .filter { isPluginPath(it.normalizedPath) }
            .sortedBy { it.normalizedPath.lowercase() }

        val results = mutableListOf<PluginEntry>()
        var nextPriority = 1

        for (record in pluginRecords) {
            val pluginName = record.normalizedPath.substringAfterLast("/")

            results.add(
                PluginEntry(
                    normalizedPath = record.normalizedPath,
                    pluginName = pluginName,
                    sourceModId = record.winningModId,
                    sourceModName = record.winningModName,
                    enabled = true,
                    priority = nextPriority,
                    pluginType = detectPluginType(pluginName)
                )
            )

            nextPriority += 1
        }

        return results
    }

    private fun isPluginPath(normalizedPath: String): Boolean {
        val lower = normalizedPath.lowercase()
        return lower.endsWith(".esp") || lower.endsWith(".esm") || lower.endsWith(".esl")
    }

    private fun detectPluginType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".esm") -> "ESM"
            lower.endsWith(".esl") -> "ESL"
            else -> "ESP"
        }
    }
}