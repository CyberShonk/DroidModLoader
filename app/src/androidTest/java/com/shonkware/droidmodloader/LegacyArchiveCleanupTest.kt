package com.shonkware.droidmodloader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRepository
import com.shonkware.droidmodloader.engine.download.LegacyArchiveCleanup
import com.shonkware.droidmodloader.ui.ArchiveLibraryPanelDialog
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.workflow.ActivityThreadRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Disposable fixtures only: never runs cleanup against the application's real archive library. */
class LegacyArchiveCleanupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun externalFilesystemCleanupPreservesOriginalsHistoryAndUncertainCopies() {
        val external = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val root = File.createTempFile("legacy-cleanup-fixture-", "", external)
        check(root.delete() && root.mkdir())
        try {
            val base = File(root, "app").apply { mkdirs() }
            val internal = File(root, "internal").apply { mkdirs() }
            val original = File(root, "original.zip").apply { writeBytes(ByteArray(150_003) { (it % 251).toByte() }) }
            val library = File(base, "downloads/archive_library").apply { mkdirs() }
            val verified = original.copyTo(File(library, "original (1).zip"))
            val uncertain = original.copyTo(File(library, "original (2).zip"))
            val sourceCopy = original.copyTo(File(library, "source-for-other-profile.zip"))
            val otherCopy = original.copyTo(File(library, "other-profile.zip"))
            val record: (File, File) -> DownloadedArchiveRecord = { copy, source ->
                DownloadedArchiveRecord(copy.name, copy.name, copy.name, "zip", copy.name,
                    copy.length(), copy.lastModified(), "metadata-only", sourcePath = source.path)
            }
            val metadata = File(base, "state/profiles/deleted/downloaded_archives.json")
            DownloadedArchiveRepository(library, metadata).save(listOf(record(verified, original), record(sourceCopy, original)))
            val otherMetadata = File(base, "state/profiles/other/downloaded_archives.json")
            DownloadedArchiveRepository(library, otherMetadata).save(listOf(record(otherCopy, sourceCopy)))
            val before = metadata.readText()
            val pending = File(internal, "profiles/other/installer_sessions/pending/extracted/sentinel").apply {
                parentFile.mkdirs(); writeText("pending")
            }
            val result = LegacyArchiveCleanup(base, internal, { emptySet() }).clean()
            assertEquals(1, result.removedFiles)
            assertEquals(original.length(), result.removedBytes)
            assertEquals(3, result.preservedFiles)
            assertFalse(verified.exists())
            for (file in listOf(original, uncertain, sourceCopy, otherCopy, pending)) assertTrue(file.exists())
            assertEquals(before, metadata.readText())
            assertEquals(0, LegacyArchiveCleanup(base, internal, { emptySet() }).clean().removedFiles)
        } finally { check(root.deleteRecursively()) }
    }

    @Test fun confirmationAndBusyAdmissionPreventAccidentalOrDuplicateWork() {
        var busy by mutableStateOf(false)
        val scheduled = mutableListOf<() -> Unit>()
        var executions = 0
        val runner = ActivityThreadRunner({ true }, { it() }, { scheduled += it })
        compose.setContent {
            ArchiveLibraryPanelDialog(
                state = ArchiveBrowserUiState(), operationInProgress = busy, archiveImportInProgress = false,
                searchText = "", listState = LazyListState(), onSearchTextChanged = {}, onRefresh = {},
                onChangeFolder = {}, onInstallArchive = {}, onCancelArchiveImport = {}, onClose = {},
                onCleanOldArchiveCopies = { runner.runExclusiveInBackground { executions++ } }
            )
        }
        compose.onNodeWithText("Remove Old DML Copies…").performClick()
        compose.runOnIdle { assertTrue(scheduled.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(scheduled.isEmpty()) }
        repeat(2) {
            compose.onNodeWithText("Remove Old DML Copies…").performClick()
            compose.onNodeWithText("Remove Copies").performClick()
        }
        compose.runOnIdle {
            assertEquals(1, scheduled.size) // Admission holds even before worker progress is published.
            assertEquals(0, executions)
            busy = true
        }
        compose.onNodeWithText("Remove Old DML Copies…").assertIsNotEnabled()
        scheduled.single().invoke()
        compose.runOnIdle {
            assertEquals(1, executions)
            assertFalse(runner.isOperationAdmitted)
        }
    }
}
