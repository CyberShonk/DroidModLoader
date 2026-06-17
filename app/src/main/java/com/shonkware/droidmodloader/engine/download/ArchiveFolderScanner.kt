package com.shonkware.droidmodloader.engine.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class ArchiveFolderAccessException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class ArchiveFolderEntry(
    val stableId: String,
    val documentUri: String,
    val fileName: String,
    val archiveFormat: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

data class ArchiveFolderScanResult(
    val folderName: String,
    val entries: List<ArchiveFolderEntry>
)

class ArchiveFolderScanner(
    private val context: Context
) {
    fun scan(treeUriText: String): ArchiveFolderScanResult {
        val treeUri = Uri.parse(treeUriText)
        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (t: Throwable) {
            throw ArchiveFolderAccessException(
                "DML could not open the selected archive folder.",
                t
            )
        } ?: throw ArchiveFolderAccessException(
            "DML could not open the selected archive folder."
        )

        if (!root.exists() || !root.isDirectory || !root.canRead()) {
            throw ArchiveFolderAccessException(
                "DML no longer has access to the selected archive folder. Choose the folder again."
            )
        }

        val entries = try {
            root.listFiles()
                .asSequence()
                .filter { it.isFile }
                .mapNotNull { document ->
                    val fileName = document.name?.trim().orEmpty()
                    if (!isSupportedArchiveName(fileName)) {
                        return@mapNotNull null
                    }

                    val documentUri = document.uri.toString()
                    ArchiveFolderEntry(
                        stableId = canonicalIdentityForUri(documentUri) ?: documentUri,
                        documentUri = documentUri,
                        fileName = fileName,
                        archiveFormat = fileName.substringAfterLast('.', "archive")
                            .lowercase(Locale.ROOT),
                        sizeBytes = document.length().coerceAtLeast(0L),
                        lastModifiedMillis = document.lastModified().coerceAtLeast(0L)
                    )
                }
                .toList()
        } catch (t: Throwable) {
            throw ArchiveFolderAccessException(
                "DML could not scan the selected archive folder. Choose the folder again or tap Refresh.",
                t
            )
        }

        return ArchiveFolderScanResult(
            folderName = root.name?.takeIf { it.isNotBlank() } ?: "Selected folder",
            entries = entries
        )
    }

    fun canonicalIdentityForUri(uriText: String?): String? {
        if (uriText.isNullOrBlank()) return null

        return try {
            val uri = Uri.parse(uriText)
            val documentId = when {
                DocumentsContract.isDocumentUri(context, uri) -> {
                    DocumentsContract.getDocumentId(uri)
                }

                uri.path?.contains("/tree/") == true -> {
                    DocumentsContract.getTreeDocumentId(uri)
                }

                else -> null
            }

            if (!documentId.isNullOrBlank() && !uri.authority.isNullOrBlank()) {
                "${uri.authority}|$documentId"
            } else {
                uri.normalizeScheme().toString()
            }
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun isSupportedArchiveName(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in setOf("zip", "7z", "rar")
}
