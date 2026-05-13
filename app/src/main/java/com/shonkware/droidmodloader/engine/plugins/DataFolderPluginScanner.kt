package com.shonkware.droidmodloader.engine.plugins

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

class DataFolderPluginScanner(
    private val context: Context
) {
    fun scanLocalDataFolder(dataFolder: File): List<String> {
        if (!dataFolder.exists() || !dataFolder.isDirectory) return emptyList()

        return dataFolder.listFiles()
            ?.filter { it.isFile && isPluginName(it.name) }
            ?.map { it.name }
            ?.sortedWith(pluginNameComparator())
            ?: emptyList()
    }

    fun scanTreeUriDataFolder(treeUri: String): List<String> {
        if (treeUri.isBlank()) return emptyList()

        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()

        return root.listFiles()
            .filter { it.isFile && it.name != null && isPluginName(it.name!!) }
            .mapNotNull { it.name }
            .sortedWith(pluginNameComparator())
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