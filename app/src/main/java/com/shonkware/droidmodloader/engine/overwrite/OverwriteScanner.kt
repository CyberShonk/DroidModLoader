package com.shonkware.droidmodloader.engine.overwrite

import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File

class OverwriteScanner {
    fun scanLocalDataFolder(root: File): List<TargetDataFileEntry> {
        if (!root.exists() || !root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).path.replace("\\", "/")
                val normalized = PathUtils.normalize(relative) ?: return@mapNotNull null

                TargetDataFileEntry(
                    normalizedPath = normalized,
                    sizeBytes = file.length(),
                    modifiedEpochMillis = file.lastModified()
                )
            }
            .toList()
    }
}
