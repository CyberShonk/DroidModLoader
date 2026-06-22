package com.shonkware.droidmodloader.engine.download

data class DownloadedArchiveRecord(
    val archiveId: String,
    val displayName: String,
    val fileName: String,
    val archiveFormat: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val fingerprint: String,
    val sourcePath: String? = null,
    val sourceUrl: String? = null,
    val nexusGameDomain: String? = null,
    val nexusModId: Long? = null,
    val nexusFileId: Long? = null,
    val nexusFileName: String? = null,
    val version: String? = null,
    val installedModId: String? = null,
    val installedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)