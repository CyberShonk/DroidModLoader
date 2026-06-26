package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallFileCopier
import java.io.File

internal class ArchiveImportFileStore(
    private val externalFilesDirProvider: () -> File?,
    private val appendError: (String) -> Unit
) {
    fun copyFileToArchiveLibraryFile(
        sourceFile: File,
        displayName: String,
        cancellationSignal:
        InstallCancellationSignal =
            InstallCancellationSignal.NONE
    ): File {
        cancellationSignal
            .throwIfCancellationRequested()

        val canonicalSource =
            sourceFile.canonicalFile

        require(
            canonicalSource.exists() &&
                    canonicalSource.isFile
        ) {
            "Selected archive does not exist: " +
                    canonicalSource.path
        }

        require(canonicalSource.canRead()) {
            "Selected archive is not readable: " +
                    canonicalSource.path
        }

        val archiveLibraryDir =
            getArchiveLibraryDir()
                ?: throw IllegalStateException(
                    "Archive library directory is unavailable."
                )

        if (
            !archiveLibraryDir.exists() &&
            !archiveLibraryDir.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create archive library directory: " +
                        archiveLibraryDir.absolutePath
            )
        }

        cancellationSignal
            .throwIfCancellationRequested()

        val destinationFile = uniqueFile(
            directory = archiveLibraryDir,
            preferredName = displayName
        )

        InstallFileCopier.copyRecursively(
            source = canonicalSource,
            target = destinationFile,
            cancellationSignal =
                cancellationSignal
        )

        return destinationFile
    }

    private fun getArchiveLibraryDir(): File? {
        val externalBaseDir =
            externalFilesDirProvider()

        if (externalBaseDir == null) {
            appendError(
                "External files directory is null"
            )
            return null
        }

        return File(
            externalBaseDir,
            "downloads/archive_library"
        )
    }

    private fun uniqueFile(
        directory: File,
        preferredName: String
    ): File {
        val safeName = preferredName
            .replace(
                Regex("""[\\/:*?"<>|]"""),
                "_"
            )
            .ifBlank {
                "imported_archive"
            }

        val baseName = safeName
            .substringBeforeLast(
                '.',
                safeName
            )

        val extension = safeName
            .substringAfterLast(
                '.',
                missingDelimiterValue = ""
            )

        var candidate =
            File(directory, safeName)

        var index = 1

        while (candidate.exists()) {
            val nextName =
                if (extension.isBlank()) {
                    "$baseName ($index)"
                } else {
                    "$baseName ($index).$extension"
                }

            candidate =
                File(directory, nextName)

            index++
        }

        return candidate
    }
}