package com.shonkware.droidmodloader.engine.overwrite

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File

class OverwriteScanner(
    private val context: Context
) {
    fun scanLocalDataFolder(root: File): List<TargetDataFileEntry> {
        if (!root.exists() || !root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).path.replace("\\", "/")
                val normalized = PathUtils.normalize(relative) ?: return@mapNotNull null

                TargetDataFileEntry(
                    normalizedPath = normalized,
                    sizeBytes = file.length()
                )
            }
            .toList()
    }

    fun scanTreeUriDataFolder(treeUri: String): List<TargetDataFileEntry> {
        if (treeUri.isBlank()) return emptyList()

        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val results = mutableListOf<TargetDataFileEntry>()

        scanDocumentFolder(
            folder = root,
            relativePrefix = "",
            results = results
        )

        return results
    }

    private fun scanDocumentFolder(
        folder: DocumentFile,
        relativePrefix: String,
        results: MutableList<TargetDataFileEntry>
    ) {
        folder.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val relative = if (relativePrefix.isBlank()) {
                name
            } else {
                "$relativePrefix/$name"
            }

            if (child.isDirectory) {
                scanDocumentFolder(
                    folder = child,
                    relativePrefix = relative,
                    results = results
                )
            } else if (child.isFile) {
                val normalized = PathUtils.normalize(relative) ?: return@forEach

                results.add(
                    TargetDataFileEntry(
                        normalizedPath = normalized,
                        sizeBytes = child.length()
                    )
                )
            }
        }
    }
}