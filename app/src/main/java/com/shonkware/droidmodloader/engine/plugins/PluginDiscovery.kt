package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.PluginEntry

class PluginDiscovery {

    fun discoverPluginsFromWinningRecords(winningRecords: List<FileRecord>): List<PluginEntry> {
        val pluginRecords = winningRecords
            .filter { isPluginPath(it.normalizedPath) }
            .sortedBy { it.normalizedPath.lowercase() }

        val results = mutableListOf<PluginEntry>()
        var nextPriority = 10

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

            nextPriority += 10
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