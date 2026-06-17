package com.shonkware.droidmodloader.ui.archive

enum class ArchiveBrowserItemStatus {
    NEVER_INSTALLED,
    PREVIOUSLY_INSTALLED,
    INSTALLED
}

data class ArchiveBrowserUiItem(
    val stableId: String,
    val documentUri: String,
    val displayName: String,
    val fileName: String,
    val archiveFormat: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
    val status: ArchiveBrowserItemStatus,
    val installedAtMillis: Long? = null,
    val installedModName: String? = null,
    val version: String? = null,
    val sourceUrl: String? = null,
    val nexusGameDomain: String? = null,
    val nexusModId: Long? = null,
    val nexusFileId: Long? = null,
    val nexusFileName: String? = null
)

data class ArchiveBrowserUiState(
    val folderUri: String? = null,
    val folderName: String? = null,
    val items: List<ArchiveBrowserUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
