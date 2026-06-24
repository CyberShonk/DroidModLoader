package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRepository
import java.io.File

internal class DownloadedArchiveService(
    archiveLibraryDir: File,
    downloadedArchiveListFile: File
) {
    private val downloadedArchiveRepository = DownloadedArchiveRepository(
        archiveLibraryDir = archiveLibraryDir,
        archiveListFile = downloadedArchiveListFile
    )

    fun registerDownloadedArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String? = null,
        sourceUrl: String? = null
    ): DownloadedArchiveRecord {
        return downloadedArchiveRepository.registerArchive(
            archiveFile = archiveFile,
            originalDisplayName = originalDisplayName,
            sourcePath = sourcePath,
            sourceUrl = sourceUrl
        )
    }

    fun getDownloadedArchives(): List<DownloadedArchiveRecord> {
        return downloadedArchiveRepository.load()
    }

    fun getDownloadedArchiveById(archiveId: String?): DownloadedArchiveRecord? {
        return downloadedArchiveRepository.findById(archiveId)
    }

    fun markDownloadedArchiveInstalled(
        archiveId: String?,
        installedModId: String
    ) {
        downloadedArchiveRepository.markInstalled(
            archiveId = archiveId,
            installedModId = installedModId
        )
    }

    fun buildDownloadedArchiveSummary(): String {
        return downloadedArchiveRepository.buildSummary()
    }
}
