package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.download.ArchiveFolderEntry
import com.shonkware.droidmodloader.engine.download.ArchiveFolderSelectionStore
import com.shonkware.droidmodloader.engine.download.ArchiveFolderScanResult
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiItem
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState

internal data class ArchiveBrowserHistory(
    val records: List<DownloadedArchiveRecord>,
    val currentMods: List<Mod>
)

internal class ArchiveBrowserWorkflow(
    private val preferences: ArchiveFolderSelectionStore,
    private val activeProfileIdProvider: () -> String?,
    private val runInBackground: (() -> Unit) -> Unit,
    private val isOperationInProgress: () -> Boolean,
    private val isBrowserOpen: () -> Boolean,
    private val scanFolder: (String) -> ArchiveFolderScanResult,
    private val loadHistory: () -> ArchiveBrowserHistory,
    private val canonicalIdentityForSourcePath: (String?) -> String?,
    private val showFolderSetup: () -> Unit,
    private val showBrowser: () -> Unit,
    private val updateState: (ArchiveBrowserUiState) -> Unit,
    private val installArchivePath: (String) -> Unit,
    private val appendLog: (String) -> Unit
) {
    @Volatile
    private var scanInProgress = false

    @Volatile
    private var refreshRequested = false

    @Volatile
    private var currentState = ArchiveBrowserUiState()

    fun openBrowser() {
        val profileId = activeProfileIdOrNull() ?: return
        val folderPath = preferences.getSelectedFolderPath(profileId)
        if (folderPath == null) {
            showFolderSetup()
            return
        }

        showBrowser()
        refresh()
    }

    fun selectFolder(folderPath: String) {
        val profileId = activeProfileIdOrNull() ?: return
        preferences.saveSelectedFolderPath(profileId, folderPath)
        currentState = ArchiveBrowserUiState(
            folderPath = folderPath,
            isLoading = true
        )
        updateState(currentState)
        showBrowser()
        refresh()
    }

    @Synchronized
    fun refresh() {
        if (isOperationInProgress()) {
            return
        }

        val profileId = activeProfileIdOrNull() ?: return
        val folderPath = preferences.getSelectedFolderPath(profileId)
        if (folderPath == null) {
            showFolderSetup()
            return
        }

        if (scanInProgress) {
            refreshRequested = true
            return
        }

        scanInProgress = true
        currentState = currentState.copy(
            folderPath = folderPath,
            isLoading = true,
            errorMessage = null
        )
        updateState(currentState)

        runInBackground {
            try {
                val scanResult = scanFolder(folderPath)
                val history = loadHistory()
                if (isCurrentSelection(profileId, folderPath)) {
                    currentState = ArchiveBrowserUiState(
                        folderPath = folderPath,
                        folderName = scanResult.folderName,
                        items = buildArchiveBrowserItems(
                            entries = scanResult.entries,
                            records = history.records,
                            currentMods = history.currentMods,
                            canonicalIdentityForSourcePath = canonicalIdentityForSourcePath
                        ),
                        isLoading = false,
                        errorMessage = null
                    )
                    updateState(currentState)
                }
            } catch (t: Throwable) {
                if (isCurrentSelection(profileId, folderPath)) {
                    currentState = currentState.copy(
                        folderPath = folderPath,
                        isLoading = false,
                        errorMessage = t.message
                            ?: "DML could not scan the selected archive folder."
                    )
                    updateState(currentState)
                    appendLog("Archive folder scan failed: ${t.message}")
                }
            } finally {
                scanInProgress = false
                if (refreshRequested) {
                    refreshRequested = false
                    refresh()
                }
            }
        }
    }

    fun refreshIfOpen() {
        if (isBrowserOpen()) {
            refresh()
        }
    }

    fun onProfileChanged() {
        currentState = ArchiveBrowserUiState()
        updateState(currentState)

        if (isBrowserOpen()) {
            openBrowser()
        }
    }

    fun installArchive(stableId: String) {
        if (isOperationInProgress()) {
            appendLog("Ignoring archive install request: operation already in progress.")
            return
        }

        val item = currentState.items.firstOrNull { it.stableId == stableId }
        if (item == null) {
            appendLog("Archive is no longer available. Tap Refresh and try again.")
            return
        }

        installArchivePath(item.sourcePath)
    }

    private fun activeProfileIdOrNull(): String? {
        val profileId = activeProfileIdProvider()?.takeIf { it.isNotBlank() }
        if (profileId == null) {
            appendLog("Archive browser is unavailable because no profile is active.")
        }
        return profileId
    }

    private fun isCurrentSelection(profileId: String, folderPath: String): Boolean {
        return activeProfileIdProvider() == profileId &&
            preferences.getSelectedFolderPath(profileId) == folderPath
    }
}

internal fun buildArchiveBrowserItems(
    entries: List<ArchiveFolderEntry>,
    records: List<DownloadedArchiveRecord>,
    currentMods: List<Mod>,
    canonicalIdentityForSourcePath: (String?) -> String?
): List<ArchiveBrowserUiItem> {
    val currentModsById = currentMods.associateBy { it.id }

    val newestInstalledRecordByModId = records
        .asSequence()
        .filter { record ->
            val modId = record.installedModId
            modId != null && modId in currentModsById
        }
        .groupBy { it.installedModId.orEmpty() }
        .mapValues { (_, matchingRecords) ->
            matchingRecords.maxWithOrNull(
                compareBy<DownloadedArchiveRecord> { it.installedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.createdAtMillis }
            )
        }

    val recordsBySourceIdentity = records
        .mapNotNull { record ->
            canonicalIdentityForSourcePath(record.sourcePath)?.let { identity ->
                identity to record
            }
        }
        .groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        )

    return entries
        .map { entry ->
            val matchingRecords = recordsBySourceIdentity[entry.stableId].orEmpty()
            val installedRecord = matchingRecords
                .filter { record ->
                    val modId = record.installedModId ?: return@filter false
                    newestInstalledRecordByModId[modId]?.archiveId == record.archiveId
                }
                .maxByOrNull { it.installedAtMillis ?: Long.MIN_VALUE }

            val previousRecord = matchingRecords
                .filter { it.installedAtMillis != null || it.installedModId != null }
                .maxByOrNull { it.installedAtMillis ?: it.createdAtMillis }

            val metadataRecord = installedRecord
                ?: previousRecord
                ?: matchingRecords.maxByOrNull { it.createdAtMillis }

            val status = when {
                installedRecord != null -> ArchiveBrowserItemStatus.INSTALLED
                previousRecord != null -> ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED
                else -> ArchiveBrowserItemStatus.NEVER_INSTALLED
            }

            ArchiveBrowserUiItem(
                stableId = entry.stableId,
                sourcePath = entry.sourcePath,
                displayName = metadataRecord?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.fileName.substringBeforeLast('.'),
                fileName = entry.fileName,
                archiveFormat = entry.archiveFormat,
                sizeBytes = entry.sizeBytes,
                downloadedAtMillis = entry.lastModifiedMillis,
                status = status,
                installedAtMillis = when (status) {
                    ArchiveBrowserItemStatus.INSTALLED -> installedRecord?.installedAtMillis
                    ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED -> previousRecord?.installedAtMillis
                    ArchiveBrowserItemStatus.NEVER_INSTALLED -> null
                },
                installedModName = installedRecord
                    ?.installedModId
                    ?.let(currentModsById::get)
                    ?.name,
                version = metadataRecord?.version,
                sourceUrl = metadataRecord?.sourceUrl,
                nexusGameDomain = metadataRecord?.nexusGameDomain,
                nexusModId = metadataRecord?.nexusModId,
                nexusFileId = metadataRecord?.nexusFileId,
                nexusFileName = metadataRecord?.nexusFileName
            )
        }
        .sortedWith { left, right ->
            val leftInstalled = left.status == ArchiveBrowserItemStatus.INSTALLED
            val rightInstalled = right.status == ArchiveBrowserItemStatus.INSTALLED

            when {
                leftInstalled != rightInstalled -> {
                    if (leftInstalled) 1 else -1
                }

                leftInstalled -> {
                    compareDescending(
                        left.installedAtMillis ?: Long.MIN_VALUE,
                        right.installedAtMillis ?: Long.MIN_VALUE
                    ).takeIf { it != 0 }
                        ?: left.fileName.compareTo(right.fileName, ignoreCase = true)
                }

                else -> {
                    compareDescending(
                        left.downloadedAtMillis,
                        right.downloadedAtMillis
                    ).takeIf { it != 0 }
                        ?: left.fileName.compareTo(right.fileName, ignoreCase = true)
                }
            }
        }
}

private fun compareDescending(left: Long, right: Long): Int {
    return right.compareTo(left)
}
