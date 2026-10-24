package com.shonkware.droidmodloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Runs only in disposable app-private fixtures; never touches a profile or a game target. */
@RunWith(AndroidJUnit4::class)
class CriticalMetadataPersistenceTest {
    @Test
    fun archiveReplacementAndInvalidEvidenceOnAndroidFilesystem() = withFixture { root ->
        val file = File(root, "archives.json")
        val archive = File(root, "example.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        val repository = DownloadedArchiveRepository(root, file)
        val first = repository.registerArchive(archive, archive.name)
        repository.markInstalled(first.archiveId, "installed-mod")
        assertEquals("installed-mod", DownloadedArchiveRepository(root, file).load().single().installedModId)
        file.writeText("[")
        val before = file.readBytes()
        assertThrows(IOException::class.java) { repository.save(emptyList()) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun journalReplacementAndStartedProtectionOnAndroidFilesystem() = withFixture { root ->
        val file = File(root, "journal.json")
        val repository = DeploymentJournalRepository(file)
        val started = DeploymentJournalRecord(
            operationId = "fixture-operation",
            gameId = "fallout_nv",
            profileId = "fixture-profile",
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = 1,
            completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(1, 0, 1, 1, null, true, 0, 0),
            resultSummary = null,
            failureMessage = null
        )
        repository.saveStarted(started)
        val reopened = DeploymentJournalRepository(file)
        assertEquals(started, reopened.load())
        assertThrows(IllegalStateException::class.java) {
            reopened.saveStarted(started.copy(operationId = "replacement"))
        }
        reopened.markReviewed(started)
        reopened.saveStarted(started.copy(operationId = "replacement"))
        assertEquals("replacement", reopened.load()!!.operationId)
        file.writeText("")
        assertThrows(IOException::class.java) { reopened.saveStarted(started) }
        assertEquals("", file.readText())
    }

    private fun withFixture(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "metadata-test-").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
