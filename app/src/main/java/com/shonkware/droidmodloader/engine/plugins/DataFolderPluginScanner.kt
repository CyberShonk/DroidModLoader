package com.shonkware.droidmodloader.engine.plugins

import java.io.File

class DataFolderPluginScanner {
    fun scanLocalDataFolder(dataFolder: File): List<String> {
        if (!dataFolder.exists() || !dataFolder.isDirectory) return emptyList()

        return dataFolder.listFiles()
            ?.filter { it.isFile && isPluginName(it.name) }
            ?.map { it.name }
            ?.sortedWith(pluginNameComparator())
            ?: emptyList()
    }

    private fun isPluginName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".esm") ||
                lower.endsWith(".esp") ||
                lower.endsWith(".esl")
    }

    private fun pluginNameComparator(): Comparator<String> {
        return compareBy<String> {
            when {
                it.lowercase().endsWith(".esm") -> 0
                it.lowercase().endsWith(".esl") -> 1
                else -> 2
            }
        }.thenBy { it.lowercase() }
    }
}
