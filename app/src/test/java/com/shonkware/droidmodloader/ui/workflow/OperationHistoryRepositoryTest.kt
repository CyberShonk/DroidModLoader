package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.MetadataFault
import com.shonkware.droidmodloader.engine.data.MetadataFaultOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OperationHistoryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = OperationContext("profile-a", "skyrim", true, "/a/Data", "/a")
    private fun file() = File(temporary.root, "operation-history.json")
    private fun attempt(context: OperationContext = this.context) = OperationAttempt.start(context, "Importing") { _, _, _ -> }
    private fun finished(context: OperationContext = this.context): OperationAttemptRecord = attempt(context).apply {
        startInstalling("Installing")
        succeed("Installed")
    }.snapshot()

    @Test fun missingHistoryIsEmptyWithoutCreatingAFile() {
        assertEquals(OperationHistorySnapshot(), OperationHistoryRepository(file()).load())
        assertFalse(file().exists())
    }

    @Test fun lifecycleRoundTripRetainsOneIdentityAndHonestTerminalOutcome() {
        val repository = OperationHistoryRepository(file())
        val attempt = attempt()
        repository.record(attempt.snapshot())
        attempt.waitForChoices("Choose options")
        repository.record(attempt.snapshot())
        assertNull(repository.snapshot.records.single().outcome)
        assertFalse(repository.load().records.single().interrupted)
        attempt.startInstalling("Installing")
        repository.record(attempt.snapshot())
        attempt.succeed("Installed")
        repository.record(attempt.snapshot())
        assertEquals(listOf(attempt.snapshot()), OperationHistoryRepository(file()).load().records)
        assertTrue(file().length() <= OperationHistoryRepository.MAX_BYTES)
    }

    @Test fun restartMarksUnfinishedUnknownWithoutInventingTerminalTimeOrRewritingEvidence() {
        val repository = OperationHistoryRepository(file())
        val attempt = attempt()
        attempt.waitForChoices("Choose")
        repository.record(attempt.snapshot())
        val originalBytes = file().readBytes()
        val restarted = OperationHistoryRepository(file())
        val record = restarted.load().records.single()
        assertTrue(record.interrupted)
        assertEquals(OperationPhase.WAITING_FOR_CHOICES, record.phase)
        assertNull(record.outcome)
        assertNull(record.finishedAtMillis)
        assertEquals(attempt.snapshot().updatedAtMillis, record.updatedAtMillis)
        assertArrayEquals(originalBytes, file().readBytes())
        attempt.startInstalling("Must not resume persisted attempt")
        assertEquals(record, restarted.record(attempt.snapshot()).records.single())
        assertArrayEquals(originalBytes, file().readBytes())
    }

    @Test fun corruptTruncatedUnsupportedAndOversizedEvidenceIsUnavailableAndNeverOverwritten() {
        val valid = OperationHistoryRepository(file()).also { it.record(finished()) }
        assertEquals(OperationHistoryAvailability.AVAILABLE, valid.snapshot.availability)
        val original = file().readText()
        val invalid = listOf(
            "{", original.dropLast(3), original + " trailing",
            JSONObject(original).put("schemaVersion", 2).toString(),
            JSONObject(original).put("records", JSONArray().put(JSONObject())).toString(),
            "[".repeat(1000) + "]".repeat(1000),
            "x".repeat(OperationHistoryRepository.MAX_BYTES + 1)
        )
        invalid.forEach { text ->
            file().writeText(text)
            val repository = OperationHistoryRepository(file())
            assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.load().availability)
            assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.record(finished()).availability)
            assertEquals(text, file().readText())
        }
    }

    @Test fun malformedUtf8AndNonregularPathsAreUnavailable() {
        file().writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val original = file().readBytes()
        val repository = OperationHistoryRepository(file())
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.load().availability)
        repository.record(finished())
        assertArrayEquals(original, file().readBytes())
        val directory = File(temporary.root, "directory").apply { mkdir() }
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, OperationHistoryRepository(directory).load().availability)
    }

    @Test fun criticalWriteFailuresKeepPriorBytesAndExposeUnsavedOutcomeInMemory() {
        OperationHistoryRepository(file()).record(finished())
        val bytes = file().readBytes()
        for (fault in MetadataFault.entries.filter { it != MetadataFault.READ }) {
            val repository = OperationHistoryRepository(CriticalMetadataFile(file(), MetadataFaultOperations(fault)))
            val actualOutcome = finished()
            val snapshot = repository.record(actualOutcome)
            assertEquals(fault.name, OperationHistoryAvailability.NOT_SAVED, snapshot.availability)
            assertEquals(OperationOutcome.SUCCEEDED, snapshot.records.last().outcome)
            assertArrayEquals(bytes, file().readBytes())
            assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".tmp") })
        }
    }

    @Test fun readDenialPreservesEvidenceAndDoesNotMasqueradeAsEmptyAvailableHistory() {
        OperationHistoryRepository(file()).record(finished())
        val bytes = file().readBytes()
        val repository = OperationHistoryRepository(CriticalMetadataFile(file(), MetadataFaultOperations(MetadataFault.READ)))
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.load().availability)
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.record(finished()).availability)
        assertArrayEquals(bytes, file().readBytes())
    }

    @Test fun outsideChangesAfterLoadAreNotOverwritten() {
        val repository = OperationHistoryRepository(file())
        repository.record(finished())
        file().writeText("broken outside this session")
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, repository.record(finished()).availability)
        assertEquals("broken outside this session", file().readText())
    }

    @Test fun duplicateAndConflictingCallbacksCannotRewriteClosedOrDifferentAttempts() {
        val repository = OperationHistoryRepository(file())
        val record = finished()
        repository.record(record)
        val before = file().readBytes()
        repository.record(record.copy(outcome = OperationOutcome.FAILED, reason = "Late failure"))
        assertArrayEquals(before, file().readBytes())
        assertEquals(record, repository.snapshot.records.single())
        val running = attempt().snapshot()
        repository.record(running)
        val saved = file().readBytes()
        assertEquals(OperationHistoryAvailability.NOT_SAVED,
            repository.record(running.copy(context = context.copy(profileId = "other"))).availability)
        assertArrayEquals(saved, file().readBytes())
        assertEquals(context, repository.snapshot.records.last().context)
    }

    @Test fun retentionEvictsOldClosedRecordsNotWaitingChoicesOrJournals() {
        val journal = File(temporary.root, "deploy-journal.json").apply { writeText("authoritative") }
        val repository = OperationHistoryRepository(file())
        val waiting = attempt().apply { waitForChoices("Choose") }.snapshot()
        repository.record(waiting)
        val oldest = finished()
        repository.record(oldest)
        repeat(60) { repository.record(finished()) }
        assertEquals(OperationHistoryRepository.MAX_RECORDS, repository.snapshot.records.size)
        assertTrue(repository.snapshot.records.any { it.id == waiting.id })
        assertFalse(repository.snapshot.records.any { it.id == oldest.id })
        assertEquals("authoritative", journal.readText())
        assertEquals(OperationHistoryAvailability.AVAILABLE, repository.snapshot.availability)
    }

    @Test fun liveRecordLimitRefusesNewHistoryWithoutDroppingExistingLiveAttempts() {
        val repository = OperationHistoryRepository(file())
        repeat(OperationHistoryRepository.MAX_RECORDS) { repository.record(attempt().snapshot()) }
        val before = file().readBytes()
        val ids = repository.snapshot.records.map { it.id }
        assertEquals(OperationHistoryAvailability.NOT_SAVED, repository.record(attempt().snapshot()).availability)
        assertEquals(ids, repository.snapshot.records.map { it.id })
        assertArrayEquals(before, file().readBytes())
    }

    @Test fun encodedByteLimitAndContextReasonLimitsAreEnforced() {
        val repository = OperationHistoryRepository(file())
        val bulky = "\u0001".repeat(OperationHistoryRepository.MAX_CONTEXT_CHARS)
        repeat(20) {
            repository.record(finished(OperationContext(bulky, bulky, true, bulky, bulky)))
        }
        assertTrue(repository.snapshot.records.size < 20)
        assertTrue(file().length() <= OperationHistoryRepository.MAX_BYTES)
        val record = finished(context.copy(dataTarget = "x".repeat(2049))).copy(reason = "r".repeat(600))
        repository.record(record)
        val loaded = OperationHistoryRepository(file()).load().records.last()
        assertTrue(loaded.context.unavailable)
        assertEquals("", loaded.context.dataTarget)
        assertEquals(512, loaded.reason.length)
        assertEquals(OperationOutcome.SUCCEEDED, loaded.outcome)
    }

    @Test fun invalidTypesDuplicateIdsAndImpossibleOutcomesAreUnavailable() {
        OperationHistoryRepository(file()).record(finished())
        val root = JSONObject(file().readText())
        val record = root.getJSONArray("records").getJSONObject(0)
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.put("reason", 123) },
            { it.put("id", "not-a-uuid") },
            { it.put("startedAtMillis", -1) },
            { it.put("finishedAtMillis", JSONObject.NULL) },
            { it.put("phase", "WAITING_FOR_CHOICES") },
            { it.getJSONObject("context").put("gameId", 12) }
        )
        corruptions.forEach { corrupt ->
            val bad = JSONObject(record.toString()).also(corrupt)
            file().writeText(JSONObject().put("schemaVersion", 1).put("records", JSONArray().put(bad)).toString())
            assertEquals(OperationHistoryAvailability.UNAVAILABLE, OperationHistoryRepository(file()).load().availability)
        }
        file().writeText(JSONObject().put("schemaVersion", 1).put("records", JSONArray().put(record).put(record)).toString())
        assertEquals(OperationHistoryAvailability.UNAVAILABLE, OperationHistoryRepository(file()).load().availability)
    }
}
