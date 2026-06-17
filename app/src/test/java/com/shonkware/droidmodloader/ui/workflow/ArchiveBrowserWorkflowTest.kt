package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.download.ArchiveFolderEntry
import com.shonkware.droidmodloader.engine.download.ArchiveFolderScanResult
import com.shonkware.droidmodloader.engine.download.ArchiveFolderSelectionStore
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBrowserWorkflowTest {
    @Test
    fun openWithoutSavedFolderRequestsSetup() {
        val store = FakeFolderStore()
        var setupCount = 0
        var browserCount = 0

        val workflow = workflow(
            store = store,
            showFolderSetup = { setupCount++ },
            showBrowser = { browserCount++ }
        )

        workflow.openBrowser()

        assertEquals(1, setupCount)
        assertEquals(0, browserCount)
    }

    @Test
    fun selectingFolderSavesScansAndShowsBrowser() {
        val store = FakeFolderStore()
        val states = mutableListOf<ArchiveBrowserUiState>()
        var browserCount = 0

        val workflow = workflow(
            store = store,
            scanFolder = {
                ArchiveFolderScanResult(
                    folderName = "Downloads",
                    entries = listOf(folderEntry("one", "One.zip", 100L))
                )
            },
            showBrowser = { browserCount++ },
            updateState = { states += it }
        )

        workflow.selectFolder("content://folder")

        assertEquals("content://folder", store.folderUri)
        assertEquals(1, browserCount)
        assertEquals("Downloads", states.last().folderName)
        assertEquals(listOf("One.zip"), states.last().items.map { it.fileName })
        assertFalse(states.last().isLoading)
    }

    @Test
    fun itemsPutInstallableArchivesFirstByDownloadDate() {
        val entries = listOf(
            folderEntry("old", "Old.zip", 100L),
            folderEntry("new", "New.7z", 300L),
            folderEntry("installed", "Installed.rar", 500L)
        )
        val records = listOf(
            archiveRecord(
                id = "installed-record",
                sourceIdentity = "installed",
                installedModId = "installed-mod",
                installedAtMillis = 200L
            )
        )

        val items = buildArchiveBrowserItems(
            entries = entries,
            records = records,
            currentMods = listOf(mod("installed-mod", "Installed Mod")),
            canonicalIdentityForSourceUri = { sourceUri -> sourceUri?.removePrefix("source:") }
        )

        assertEquals(listOf("new", "old", "installed"), items.map { it.stableId })
        assertEquals(ArchiveBrowserItemStatus.INSTALLED, items.last().status)
    }

    @Test
    fun removedModIsShownAsPreviouslyInstalled() {
        val items = buildArchiveBrowserItems(
            entries = listOf(folderEntry("previous", "Previous.zip", 400L)),
            records = listOf(
                archiveRecord(
                    id = "previous-record",
                    sourceIdentity = "previous",
                    installedModId = "removed-mod",
                    installedAtMillis = 250L
                )
            ),
            currentMods = emptyList(),
            canonicalIdentityForSourceUri = { sourceUri -> sourceUri?.removePrefix("source:") }
        )

        assertEquals(ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED, items.single().status)
        assertEquals(250L, items.single().installedAtMillis)
    }

    @Test
    fun onlyNewestRecordForCurrentModIsMarkedInstalled() {
        val entries = listOf(
            folderEntry("older-version", "Mod-1.zip", 100L),
            folderEntry("newer-version", "Mod-2.zip", 200L)
        )
        val records = listOf(
            archiveRecord(
                id = "old-record",
                sourceIdentity = "older-version",
                installedModId = "same-mod",
                installedAtMillis = 100L
            ),
            archiveRecord(
                id = "new-record",
                sourceIdentity = "newer-version",
                installedModId = "same-mod",
                installedAtMillis = 300L
            )
        )

        val items = buildArchiveBrowserItems(
            entries = entries,
            records = records,
            currentMods = listOf(mod("same-mod", "Same Mod")),
            canonicalIdentityForSourceUri = { sourceUri -> sourceUri?.removePrefix("source:") }
        )

        assertEquals(
            ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED,
            items.first { it.stableId == "older-version" }.status
        )
        assertEquals(
            ArchiveBrowserItemStatus.INSTALLED,
            items.first { it.stableId == "newer-version" }.status
        )
    }

    @Test
    fun installRoutesSelectedDocumentUriAndRespectsBusyState() {
        val store = FakeFolderStore("content://folder")
        val installedUris = mutableListOf<String>()
        var busy = false

        val workflow = workflow(
            store = store,
            isOperationInProgress = { busy },
            scanFolder = {
                ArchiveFolderScanResult(
                    folderName = "Downloads",
                    entries = listOf(folderEntry("one", "One.zip", 100L))
                )
            },
            installArchiveUri = { installedUris += it }
        )

        workflow.openBrowser()
        workflow.installArchive("one")
        busy = true
        workflow.installArchive("one")

        assertEquals(listOf("content://one"), installedUris)
    }

    private fun workflow(
        store: FakeFolderStore,
        isOperationInProgress: () -> Boolean = { false },
        scanFolder: (String) -> ArchiveFolderScanResult = {
            ArchiveFolderScanResult("Downloads", emptyList())
        },
        showFolderSetup: () -> Unit = {},
        showBrowser: () -> Unit = {},
        updateState: (ArchiveBrowserUiState) -> Unit = {},
        installArchiveUri: (String) -> Unit = {}
    ): ArchiveBrowserWorkflow {
        return ArchiveBrowserWorkflow(
            preferences = store,
            runInBackground = { task -> task() },
            isOperationInProgress = isOperationInProgress,
            isBrowserOpen = { true },
            scanFolder = scanFolder,
            loadHistory = { ArchiveBrowserHistory(emptyList(), emptyList()) },
            canonicalIdentityForSourceUri = { it },
            showFolderSetup = showFolderSetup,
            showBrowser = showBrowser,
            updateState = updateState,
            installArchiveUri = installArchiveUri,
            appendLog = {}
        )
    }

    private fun folderEntry(
        stableId: String,
        fileName: String,
        lastModifiedMillis: Long
    ): ArchiveFolderEntry {
        return ArchiveFolderEntry(
            stableId = stableId,
            documentUri = "content://$stableId",
            fileName = fileName,
            archiveFormat = fileName.substringAfterLast('.'),
            sizeBytes = 100L,
            lastModifiedMillis = lastModifiedMillis
        )
    }

    private fun archiveRecord(
        id: String,
        sourceIdentity: String,
        installedModId: String? = null,
        installedAtMillis: Long? = null
    ): DownloadedArchiveRecord {
        return DownloadedArchiveRecord(
            archiveId = id,
            displayName = id,
            fileName = "$id.zip",
            archiveFormat = "zip",
            relativePath = "$id.zip",
            sizeBytes = 100L,
            modifiedAtMillis = 1L,
            fingerprint = "fingerprint-$id",
            sourceUri = "source:$sourceIdentity",
            installedModId = installedModId,
            installedAtMillis = installedAtMillis,
            createdAtMillis = installedAtMillis ?: 1L
        )
    }

    private fun mod(id: String, name: String): Mod {
        return Mod(
            id = id,
            name = name,
            installPath = "/mods/$id",
            enabled = true,
            priority = 1,
            modType = ModType.LOOSE
        )
    }

    private class FakeFolderStore(
        var folderUri: String? = null
    ) : ArchiveFolderSelectionStore {
        override fun getSelectedFolderUri(): String? = folderUri

        override fun saveSelectedFolderUri(treeUri: String) {
            folderUri = treeUri
        }

        override fun clearSelectedFolderUri() {
            folderUri = null
        }
    }
}
