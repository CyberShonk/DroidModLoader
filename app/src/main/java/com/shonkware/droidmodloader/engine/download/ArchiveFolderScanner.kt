package com.shonkware.droidmodloader.engine.download

import java.io.File
import java.io.IOException
import java.util.Locale

class ArchiveFolderAccessException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class ArchiveFolderEntry(
    val stableId: String,
    val sourcePath: String,
    val fileName: String,
    val archiveFormat: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

data class ArchiveFolderScanResult(
    val folderName: String,
    val entries: List<ArchiveFolderEntry>
)

class ArchiveFolderScanner {
    fun scan(folderPath: String): ArchiveFolderScanResult {
        val root = try {
            File(folderPath).canonicalFile
        } catch (t: IOException) {
            throw ArchiveFolderAccessException(
                "DML could not resolve the selected archive folder.",
                t
            )
        }

        if (!root.exists() || !root.isDirectory || !root.canRead()) {
            throw ArchiveFolderAccessException(
                "DML cannot read the selected archive folder. Choose the folder again."
            )
        }

        val entries = try {
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.canRead() }
                ?.mapNotNull { file ->
                    if (!isSupportedArchiveName(file.name)) {
                        return@mapNotNull null
                    }

                    val canonical = file.canonicalFile
                    ArchiveFolderEntry(
                        stableId = canonicalIdentityForPath(canonical.path) ?: canonical.path,
                        sourcePath = canonical.path,
                        fileName = canonical.name,
                        archiveFormat = canonical.name.substringAfterLast('.', "archive")
                            .lowercase(Locale.ROOT),
                        sizeBytes = canonical.length().coerceAtLeast(0L),
                        lastModifiedMillis = canonical.lastModified().coerceAtLeast(0L)
                    )
                }
                ?.toList()
                ?: emptyList()
        } catch (t: Throwable) {
            throw ArchiveFolderAccessException(
                "DML could not scan the selected archive folder. Choose the folder again or tap Refresh.",
                t
            )
        }

        return ArchiveFolderScanResult(
            folderName = root.name.takeIf { it.isNotBlank() } ?: root.path,
            entries = entries
        )
    }

    fun canonicalIdentityForPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { File(path).canonicalPath }.getOrNull()
    }
}

internal fun isSupportedArchiveName(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in setOf("zip", "7z", "rar")
}
