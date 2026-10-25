package com.shonkware.droidmodloader

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.ui.workflow.OperationAttempt
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryRepository
import com.shonkware.droidmodloader.ui.workflow.OperationOutcome
import com.shonkware.droidmodloader.ui.workflow.OperationPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Native JSON/filesystem proof in unique app-owned fixtures, not real game/profile storage. */
class OperationHistoryPersistenceTest {
    private val context = OperationContext("history-fixture", "skyrim", false, "", "")

    private fun withHistory(action: (File) -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = Files.createTempDirectory(cache.toPath(), "operation-history-test-").toFile()
        try {
            action(File(root, "operation-history.json"))
        } finally {
            assertTrue("Owned fixture cleanup failed", root.deleteRecursively())
        }
    }

    @Test fun reloadKeepsUnknownWaitingAndKnownSuccessSeparateWithoutReplay() = withHistory { file ->
        val repository = OperationHistoryRepository(file)
        val waiting = OperationAttempt.start(context, "Importing") { _, _, _ -> }
        waiting.waitForChoices("Choose options")
        repository.record(waiting.snapshot())
        val success = OperationAttempt.start(context.copy(profileId = "other-fixture"), "Importing") { _, _, _ -> }
        success.startInstalling("Installing")
        success.succeed("Installed")
        repository.record(success.snapshot())
        val originalBytes = file.readBytes()

        val reloaded = OperationHistoryRepository(file).load()
        assertEquals(OperationHistoryAvailability.AVAILABLE, reloaded.availability)
        val interrupted = reloaded.records.first()
        assertTrue(interrupted.interrupted)
        assertEquals(OperationPhase.WAITING_FOR_CHOICES, interrupted.phase)
        assertNull(interrupted.outcome)
        assertNull(interrupted.finishedAtMillis)
        assertEquals(waiting.snapshot().id, interrupted.id)
        assertEquals(context, interrupted.context)
        assertEquals(success.snapshot(), reloaded.records.last())
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test fun writeFailureAndCorruptHistoryPreserveEvidenceWithoutChangingOperationOutcome() = withHistory { file ->
        val original = OperationAttempt.start(context, "Importing") { _, _, _ -> }
        original.startInstalling("Installing")
        original.succeed("Installed")
        OperationHistoryRepository(file).record(original.snapshot())
        val before = file.readBytes()
        val denied = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun writeAndSync(file: File, bytes: ByteArray) {
                throw IOException("Injected history write denial")
            }
        }
        val repository = OperationHistoryRepository(CriticalMetadataFile(file, denied))
        val next = OperationAttempt.start(context, "Importing") { record, _, _ -> repository.record(record) }
        next.startInstalling("Installing")
        next.succeed("Installed")
        assertEquals(OperationOutcome.SUCCEEDED, next.snapshot().outcome)
        assertEquals(OperationOutcome.SUCCEEDED, repository.snapshot.records.last().outcome)
        assertEquals(OperationHistoryAvailability.NOT_SAVED, repository.snapshot.availability)
        assertArrayEquals(before, file.readBytes())

        file.writeText("{truncated")
        val corrupt = OperationHistoryRepository(file)
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, corrupt.load().availability)
        corrupt.record(next.snapshot())
        assertEquals("{truncated", file.readText())
    }
}
