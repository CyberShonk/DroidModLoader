package com.shonkware.droidmodloader.engine.deploy.journal

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.MetadataFault
import com.shonkware.droidmodloader.engine.data.MetadataFaultOperations
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DeploymentJournalRepositoryTest {
    @Test
    fun `schema two round trip preserves target ownership`() {
        val file = journalFile("round-trip")
        val repository = DeploymentJournalRepository(file)
        val record = record("operation-a")

        repository.saveStarted(record)

        val loaded = repository.load()!!
        assertEquals(2, loaded.schemaVersion)
        assertEquals(record.dataTarget, loaded.dataTarget)
        assertEquals(record.rootTarget, loaded.rootTarget)
        assertTrue(loaded.toDebugSummary().contains("Identity key"))
    }

    @Test
    fun `legacy journal remains readable without invented target ownership`() {
        val file = journalFile("legacy")
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "operationId": "legacy-operation",
              "gameId": "fallout_nv",
              "profileId": "profile-a",
              "status": "STARTED",
              "startedAtEpochMillis": 1234,
              "completedAtEpochMillis": null,
              "planSummary": {
                "dataOperationCount": 1,
                "rootOperationCount": 0,
                "totalOperationCount": 1,
                "dataEstimatedCopyBytes": 10,
                "rootEstimatedCopyBytes": null,
                "preflightCanDeploy": true,
                "preflightErrorCount": 0,
                "preflightWarningCount": 0
              },
              "resultSummary": null,
              "failureMessage": null
            }
            """.trimIndent()
        )

        val loaded = DeploymentJournalRepository(file).load()!!

        assertEquals(1, loaded.schemaVersion)
        assertNull(loaded.dataTarget)
        assertNull(loaded.rootTarget)
        assertTrue(loaded.toDebugSummary().contains("legacy journal schema"))

        file.writeText(JSONObject(file.readText()).apply { remove("schemaVersion") }.toString())
        assertEquals(loaded, DeploymentJournalRepository(file).load())
    }

    @Test
    fun `unfinished journal cannot be overwritten even after review`() {
        val file = journalFile("unfinished")
        val repository = DeploymentJournalRepository(file)
        val first = record("operation-a")
        repository.saveStarted(first)

        try {
            repository.saveStarted(record("operation-b"))
            fail("Expected unfinished journal overwrite to be refused.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Refusing to overwrite"))
        }

        assertEquals("operation-a", repository.load()!!.operationId)

        repository.markReviewed(first)
        val reviewed = file.readBytes()
        assertThrows(IllegalStateException::class.java) { repository.saveStarted(record("operation-b")) }
        assertArrayEquals(reviewed, file.readBytes())
        assertEquals(DeploymentJournalStatus.REVIEWED, repository.load()!!.status)
    }

    @Test
    fun `only missing journal is absent and invalid evidence blocks every replacement`() {
        val file = journalFile("invalid")
        val repository = DeploymentJournalRepository(file)
        assertNull(repository.load())
        val started = record("operation-a")
        repository.saveStarted(started)
        val valid = file.readText()
        val invalid = listOf(
            "", " ", "{", "{}", valid.dropLast(1), "$valid trailing", "[]",
            JSONObject(valid).put("schemaVersion", 99).toString(),
            JSONObject(valid).put("schemaVersion", "2").toString(),
            JSONObject(valid).put("status", "UNKNOWN").toString(),
            JSONObject(valid).put("status", "COMPLETED").toString(),
            JSONObject(valid).put("status", "FAILED").toString(),
            JSONObject(valid).put("status", "REVIEWED").toString(),
            JSONObject(valid).put("completedAtEpochMillis", 2000).toString(),
            JSONObject(valid).put("status", "COMPLETED")
                .put("completedAtEpochMillis", 2000).toString(),
            JSONObject(valid).apply { remove("status") }.toString(),
            JSONObject(valid).put("operationId", false).toString(),
            JSONObject(valid).put("dataTarget", false).toString(),
            JSONObject(valid).apply { getJSONObject("planSummary").remove("totalOperationCount") }.toString()
        )
        for (text in invalid) {
            file.writeText(text)
            val before = file.readBytes()
            assertThrows(IOException::class.java) { repository.load() }
            assertThrows(IOException::class.java) { repository.saveStarted(record("operation-b")) }
            assertThrows(IOException::class.java) { repository.markReviewed(started) }
            assertThrows(IOException::class.java) { repository.markFailed(started, "failure") }
            assertThrows(IOException::class.java) { repository.markCompleted(started, result()) }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun `failed journal transitions retain STARTED evidence and still block another deploy`() {
        for (fault in MetadataFault.entries) {
            val file = journalFile("fault-${fault.name}")
            val healthy = DeploymentJournalRepository(file)
            val started = record("operation-a")
            healthy.saveStarted(started)
            val before = file.readBytes()
            val failing = DeploymentJournalRepository(CriticalMetadataFile(file, MetadataFaultOperations(fault)))
            assertThrows(IOException::class.java) { failing.markCompleted(started, result()) }
            assertThrows(IOException::class.java) { failing.markFailed(started, "failure") }
            assertThrows(IOException::class.java) { failing.markReviewed(started) }
            assertArrayEquals(before, file.readBytes())
            assertEquals(started, healthy.load())
            assertThrows(IllegalStateException::class.java) { healthy.saveStarted(record("operation-b")) }
            assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })

            // A completed operation permits replacement, but failed persistence preserves it.
            healthy.markCompleted(started, result())
            val completed = file.readBytes()
            assertThrows(IOException::class.java) { failing.saveStarted(record("operation-b")) }
            assertArrayEquals(completed, file.readBytes())
        }
    }

    @Test
    fun `stale terminal and review requests cannot replace another operation`() {
        val file = journalFile("stale")
        val repository = DeploymentJournalRepository(file)
        val first = record("operation-a")
        repository.saveStarted(first)
        repository.markCompleted(first, result())
        assertEquals(DeploymentJournalStatus.COMPLETED, repository.load()!!.status)
        val second = record("operation-b")
        repository.saveStarted(second)
        val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) { repository.markReviewed(first) }
        assertThrows(IllegalStateException::class.java) { repository.markFailed(first, "failure") }
        assertThrows(IllegalStateException::class.java) { repository.markCompleted(first, result()) }
        assertArrayEquals(before, file.readBytes())
        repository.markFailed(second, "expected failure")
        assertEquals(DeploymentJournalStatus.FAILED, repository.load()!!.status)
        val failed = file.readBytes()
        assertThrows(IllegalStateException::class.java) { repository.saveStarted(record("operation-c")) }
        assertArrayEquals(failed, file.readBytes())
    }

    @Test
    fun `missing journal cannot be recreated by a terminal or review request`() {
        val file = journalFile("missing-terminal")
        val repository = DeploymentJournalRepository(file)
        val started = record("operation-a")
        assertThrows(IllegalStateException::class.java) { repository.markReviewed(started) }
        assertThrows(IllegalStateException::class.java) { repository.markCompleted(started, result()) }
        assertThrows(IllegalStateException::class.java) { repository.markFailed(started, "failure") }
        assertNull(repository.load())
    }

    @Test
    fun `unsupported new journal is refused without replacing completed evidence`() {
        val file = journalFile("unsupported-new")
        val repository = DeploymentJournalRepository(file)
        val started = record("operation-a")
        repository.saveStarted(started)
        repository.markCompleted(started, result())
        val before = file.readBytes()
        assertThrows(IOException::class.java) {
            repository.saveStarted(record("operation-b").copy(schemaVersion = 99))
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `completed foreign journal cannot be replaced under another profile or game`() {
        val file = journalFile("wrong-scope")
        val repo = DeploymentJournalRepository(file)
        val started = record("operation-a")
        repo.saveStarted(started)
        repo.markCompleted(started, result())
        val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) { repo.saveStarted(record("other").copy(profileId = "other-profile")) }
        assertThrows(IllegalStateException::class.java) { repo.saveStarted(record("other").copy(gameId = "other-game")) }
        assertArrayEquals(before, file.readBytes())
    }

    private fun result() = DeploymentJournalResultSummary(1, 0, 0, 0, 0, 0, 1)

    private fun journalFile(name: String): File {
        val root = Files.createTempDirectory("dml-journal-$name").toFile()
        return File(root, "deployment_journal_fallout_nv.json")
    }

    private fun record(operationId: String): DeploymentJournalRecord {
        return DeploymentJournalRecord(
            operationId = operationId,
            gameId = "fallout_nv",
            profileId = "profile-a",
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = 1000,
            completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(
                dataOperationCount = 1,
                rootOperationCount = 0,
                totalOperationCount = 1,
                dataEstimatedCopyBytes = 10,
                rootEstimatedCopyBytes = null,
                preflightCanDeploy = true,
                preflightErrorCount = 0,
                preflightWarningCount = 0
            ),
            resultSummary = null,
            failureMessage = null,
            dataTarget = targetState(
                targetType = "DATA",
                mode = "real_path",
                target = "/target-a/Data",
                manifest = "/state/manifest-a.json",
                baseline = "/state/baseline-a.json",
                backups = "/state/backups-a"
            ),
            rootTarget = targetState(
                targetType = "GAME_ROOT",
                mode = "root_real_path",
                target = "/target-a",
                manifest = "/state/root-manifest-a.json",
                baseline = null,
                backups = "/state/root-backups-a"
            )
        )
    }

    private fun targetState(
        targetType: String,
        mode: String,
        target: String,
        manifest: String,
        baseline: String?,
        backups: String
    ): DeploymentJournalTargetState {
        return DeploymentJournalTargetState(
            targetType = targetType,
            gameId = "fallout_nv",
            mode = mode,
            target = target,
            identityKey = "fallout_nv|$mode|$target",
            manifestFilePath = manifest,
            baselineFilePath = baseline,
            backupDirectoryPath = backups
        )
    }
}
