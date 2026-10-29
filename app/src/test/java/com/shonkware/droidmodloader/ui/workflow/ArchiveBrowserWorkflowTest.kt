package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.download.ArchiveFolderEntry
import com.shonkware.droidmodloader.engine.download.ArchiveFolderScanResult
import com.shonkware.droidmodloader.engine.download.ArchiveFolderSelectionStore
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
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

        workflow.selectFolder("/archives")

        assertEquals("/archives", store.folderPaths["profile-a"])
        assertEquals(1, browserCount)
        assertEquals("Downloads", states.last().folderName)
        assertEquals(listOf("One.zip"), states.last().items.map { it.fileName })
        assertFalse(states.last().isLoading)
    }


    @Test
    fun profilesKeepSeparateSelectedFolders() {
        val store = FakeFolderStore()
        var activeProfileId = "profile-a"
        val scannedFolders = mutableListOf<String>()
        var setupCount = 0

        val workflow = workflow(
            store = store,
            activeProfileIdProvider = { activeProfileId },
            scanFolder = { folderPath ->
                scannedFolders += folderPath
                ArchiveFolderScanResult("Downloads", emptyList())
            },
            showFolderSetup = { setupCount++ }
        )

        workflow.selectFolder("/archives/a")
        activeProfileId = "profile-b"
        workflow.onProfileChanged()
        workflow.selectFolder("/archives/b")
        activeProfileId = "profile-a"
        workflow.onProfileChanged()

        assertEquals("/archives/a", store.folderPaths["profile-a"])
        assertEquals("/archives/b", store.folderPaths["profile-b"])
        assertEquals(1, setupCount)
        assertEquals("/archives/a", scannedFolders.last())
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
            canonicalIdentityForSourcePath = { sourcePath -> sourcePath?.substringAfterLast('/') }
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
            canonicalIdentityForSourcePath = { sourcePath -> sourcePath?.substringAfterLast('/') }
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
            canonicalIdentityForSourcePath = { sourcePath -> sourcePath?.substringAfterLast('/') }
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
    fun installRoutesSelectedArchivePathAndRespectsBusyState() {
        val store = FakeFolderStore("/archives")
        val installedPaths = mutableListOf<String>()
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
            installArchivePath = { installedPaths += it }
        )

        workflow.openBrowser()
        workflow.installArchive("one")
        busy = true
        workflow.installArchive("one")

        assertEquals(listOf("/archives/one"), installedPaths)
    }

    @Test
    fun rejectedScanDoesNotReadHistoryOrLeaveLoadingState() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val workflow = workflow(
            store = FakeFolderStore().apply { folderPaths["profile-a"] = "/archives" },
            runInBackground = { false },
            scanFolder = { error("Rejected scan must not touch the filesystem") },
            updateState = { states += it }
        )

        workflow.refresh()
        workflow.refresh()

        assertFalse(states.last().isLoading)
        assertEquals(4, states.size)
    }

    @Test
    fun queuedScanResultIsNotPublishedAfterProfileChanges() {
        var profileId = "profile-a"
        val publications = mutableListOf<() -> Unit>()
        val states = mutableListOf<ArchiveBrowserUiState>()
        val workflow = workflow(
            store = FakeFolderStore().apply { folderPaths["profile-a"] = "/archives" },
            activeProfileIdProvider = { profileId },
            postToUiThread = publications::add,
            updateState = { states += it }
        )

        workflow.refresh()
        assertTrue(states.last().isLoading)
        profileId = "profile-b"
        val count = states.size
        publications.forEach { it() }

        assertEquals(count, states.size)
    }

    @Test
    fun historicalInspectionSurvivesRefreshButIsNeverAnInstallOrSourceCache() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val installed = mutableListOf<String>()
        val workflow = workflow(FakeFolderStore("/archives"),
            scanFolder = { ArchiveFolderScanResult("Downloads", listOf(folderEntry("one", "One.zip", 100))) },
            updateState = states::add, installArchivePath = installed::add)
        workflow.openBrowser()
        val report = ArchiveInspectionReport("One.zip", 1234, failureCode = "UNSUPPORTED_VARIANT")
        workflow.captureInspectionPublisher("profile-a", "/archives/one")(report)
        workflow.refresh()
        assertEquals(report, states.last().inspection)
        assertTrue(installed.isEmpty())
        workflow.installArchive("one")
        assertEquals(listOf("/archives/one"), installed)
    }

    @Test
    fun inspectionPublicationRechecksProfileAndFolderOnTheUiThread() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val queued = mutableListOf<() -> Unit>()
        var profile = "profile-a"
        val workflow = workflow(FakeFolderStore("/archives"), activeProfileIdProvider = { profile },
            scanFolder = { ArchiveFolderScanResult("Downloads", listOf(folderEntry("one", "One.zip", 100))) },
            updateState = states::add, postToUiThread = queued::add)
        workflow.openBrowser()
        queued.toList().forEach { it() }
        queued.clear()
        workflow.captureInspectionPublisher("profile-a", "/archives/one")(ArchiveInspectionReport("One.zip", 1234))
        profile = "profile-b"
        val before = states.size
        queued.forEach { it() }
        assertEquals(before, states.size)
        assertEquals(null, states.last().inspection)
        profile = "profile-a"
        queued.clear()
        workflow.captureInspectionPublisher("profile-a", "/archives/one")(ArchiveInspectionReport("One.zip", 1234))
        workflow.selectFolder("/elsewhere")
        queued.toList().forEach { it() }
        assertEquals(null, states.last().inspection)
    }

    @Test
    fun capturedInspectionCannotReturnAfterAProfileOrFolderRoundTrip() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val workflow = workflow(FakeFolderStore("/archives"),
            scanFolder = { ArchiveFolderScanResult("Downloads", listOf(folderEntry("one", "One.zip", 100))) },
            updateState = states::add)
        workflow.openBrowser()
        val report = ArchiveInspectionReport("One.zip", 1234)
        val oldFolder = workflow.captureInspectionPublisher("profile-a", "/archives/one")
        workflow.selectFolder("/other")
        workflow.selectFolder("/archives")
        oldFolder(report)
        assertEquals(null, states.last().inspection)
        val oldProfile = workflow.captureInspectionPublisher("profile-a", "/archives/one")
        workflow.onProfileChanged()
        oldProfile(report)
        assertEquals(null, states.last().inspection)
    }

    @Test
    fun newerAttemptRejectsAnOlderQueuedObservationEvenInTheSameFolder() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val queued = mutableListOf<() -> Unit>()
        val workflow = workflow(FakeFolderStore("/archives"),
            scanFolder = { ArchiveFolderScanResult("Downloads", listOf(folderEntry("one", "One.zip", 100))) },
            updateState = states::add, postToUiThread = queued::add)
        workflow.openBrowser()
        queued.toList().forEach { it() }
        queued.clear()
        val old = workflow.captureInspectionPublisher("profile-a", "/archives/one")
        val latest = workflow.captureInspectionPublisher("profile-a", "/archives/one")
        val latestReport = ArchiveInspectionReport("One.zip", 2000, failureCode = "UNSAFE_ENTRY_PATH")
        latest(latestReport)
        old(ArchiveInspectionReport("One.zip", 1000, failureCode = "IO_FAILURE"))
        queued.toList().forEach { it() }
        assertEquals(latestReport, states.last().inspection)
    }

    @Test
    fun inspectionPublicationRejectsChangedGameOrTargetsWithoutAnotherScan() {
        val states = mutableListOf<ArchiveBrowserUiState>()
        val queued = mutableListOf<() -> Unit>()
        var currentContext = OperationContext("profile-a", "skyrim_le", true, "/game/Data", "/game")
        var scans = 0
        val workflow = workflow(FakeFolderStore("/archives"), scanFolder = {
            scans++
            ArchiveFolderScanResult("Downloads", listOf(folderEntry("one", "One.zip", 100)))
        }, updateState = states::add, postToUiThread = queued::add)
        workflow.openBrowser()
        queued.toList().forEach { it() }
        queued.clear()
        val captured = currentContext
        val publisher = workflow.captureInspectionPublisher("profile-a", "/archives/one") { captured == currentContext }
        publisher(ArchiveInspectionReport("One.zip", 1234))
        currentContext = currentContext.copy(gameId = "fallout_nv")
        queued.toList().forEach { it() }
        assertEquals(null, states.last().inspection)
        queued.clear()
        currentContext = captured
        publisher(ArchiveInspectionReport("One.zip", 1234))
        currentContext = currentContext.copy(dataTarget = "/other/Data")
        queued.toList().forEach { it() }
        assertEquals(null, states.last().inspection)
        assertEquals(1, scans)
    }

    private fun workflow(
        store: FakeFolderStore,
        activeProfileIdProvider: () -> String? = { "profile-a" },
        isOperationInProgress: () -> Boolean = { false },
        scanFolder: (String) -> ArchiveFolderScanResult = {
            ArchiveFolderScanResult("Downloads", emptyList())
        },
        showFolderSetup: () -> Unit = {},
        showBrowser: () -> Unit = {},
        updateState: (ArchiveBrowserUiState) -> Unit = {},
        installArchivePath: (String) -> Unit = {},
        runInBackground: (() -> Unit) -> Boolean = { it(); true },
        postToUiThread: (() -> Unit) -> Unit = { it() }
    ): ArchiveBrowserWorkflow {
        return ArchiveBrowserWorkflow(
            preferences = store,
            activeProfileIdProvider = activeProfileIdProvider,
            runInBackground = runInBackground,
            isOperationInProgress = isOperationInProgress,
            isBrowserOpen = { true },
            scanFolder = scanFolder,
            loadHistory = { ArchiveBrowserHistory(emptyList(), emptyList()) },
            canonicalIdentityForSourcePath = { it },
            showFolderSetup = showFolderSetup,
            showBrowser = showBrowser,
            updateState = updateState,
            installArchivePath = installArchivePath,
            appendLog = {},
            postToUiThread = postToUiThread
        )
    }

    private fun folderEntry(
        stableId: String,
        fileName: String,
        lastModifiedMillis: Long
    ): ArchiveFolderEntry {
        return ArchiveFolderEntry(
            stableId = stableId,
            sourcePath = "/archives/$stableId",
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
            sourcePath = "/archives/$sourceIdentity",
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
        initialFolderPath: String? = null
    ) : ArchiveFolderSelectionStore {
        val folderPaths = mutableMapOf<String, String>().apply {
            if (initialFolderPath != null) {
                put("profile-a", initialFolderPath)
            }
        }

        override fun getSelectedFolderPath(profileId: String): String? = folderPaths[profileId]

        override fun saveSelectedFolderPath(profileId: String, path: String) {
            folderPaths[profileId] = path
        }

        override fun clearSelectedFolderPath(profileId: String) {
            folderPaths.remove(profileId)
        }

        override fun isReselectionRequired(profileId: String): Boolean = false
    }
}
