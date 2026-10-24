package com.shonkware.droidmodloader.engine.deploy.health

import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.deploy.journal.*
import com.shonkware.droidmodloader.engine.model.*
import com.shonkware.droidmodloader.engine.service.DeploymentService
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeploymentHealthCollectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val noWinners = emptyList<FileRecord>() to emptyList<FileRecord>()

    private data class Fixture(val base: File, val state: File, val service: DeploymentService) {
        fun health(winners: Pair<List<FileRecord>, List<FileRecord>>? = emptyList<FileRecord>() to emptyList()) =
            DeploymentHealthProjector.project(service.collectDeploymentHealthFacts("profile", "fallout_nv", winners))
    }

    private fun fixture(): Fixture {
        val base = temporary.newFolder()
        val state = File(base, "state").apply { mkdirs() }
        return Fixture(base, state, DeploymentService(base, File(base, "temp"), File(state, "mods.json"),
            File(state, "manifest.json"), File(base, "deploy/Data"), File(state, "config.json"),
            { error("Health must use its captured resolver result") }, { error("Must not resolve twice") }))
    }

    @Test fun `fresh empty source state is simulated and unverified without creating target directories`() {
        val f = fixture()
        val health = f.health()
        assertEquals(DeploymentHealthPhase.UNVERIFIED, health.phase)
        assertTrue(health.targets.all { it.readiness == TargetReadiness.SIMULATED && it.pendingChanges == 0 })
        assertFalse(File(f.base, "deploy").exists())
        assertFalse(File(f.base, "temp").exists())
        assertTrue(f.state.listFiles()!!.isEmpty())
    }

    @Test fun `unknown source indexes and malformed manifests cannot become clean empty plans`() {
        val f = fixture()
        assertEquals(DeploymentHealthPhase.UNKNOWN, f.health(null).phase)
        val manifest = f.service.captureTargetIntegrityInputs("fallout_nv").first().evidenceFile
        for (text in listOf("", "broken", "[{}]", """[{"normalizedPath":"../escape"}]""")) {
            manifest.writeText(text)
            val health = f.health()
            assertEquals(DeploymentHealthPhase.UNKNOWN, health.phase)
            assertNull(health.targets.first().pendingChanges)
            assertEquals(text, manifest.readText())
        }
    }

    @Test fun `invalid real Data and valid Game Root stay separate rather than becoming simulated`() {
        val f = fixture()
        val root = File(f.base, "game").apply { mkdirs() }
        File(root, "FalloutNV.exe").writeText("fixture")
        val data = File(root, "Data").apply { mkdirs() }
        File(data, "Fallout3.esm").writeText("wrong game")
        f.service.saveGameDeploymentConfigs(listOf(GameDeploymentConfig("fallout_nv", "Fixture", data.path, true, root.path)))
        val health = f.health()
        assertEquals(DeploymentHealthPhase.TARGET_UNAVAILABLE, health.phase)
        assertEquals(TargetReadiness.UNAVAILABLE, health.targets[0].readiness)
        assertEquals(TargetReadiness.READY, health.targets[1].readiness)
        assertTrue(health.targets.none { it.readiness == TargetReadiness.SIMULATED })
    }

    @Test fun `recorded winners reuse normal resolver for priority enabled state and Data Root scopes`() {
        val f = fixture()
        val library = ModLibraryService(File(f.base, "temp"), File(f.base, "mods"), File(f.state, "mods.json"),
            File(f.state, "manifest.json"), File(f.base, "deploy/Data"), File(f.state, "config.json"),
            File(f.state, "plugins.json"), File(f.state, "plugins.txt"), File(f.state, "loadorder.txt"))
        fun mod(id: String, priority: Int) = Mod(id, id, File(f.base, "mods/$id").path, true, priority, ModType.LOOSE)
        val a = mod("a", 1)
        val b = mod("b", 2)
        val path = "textures/shared.dds"
        listOf(a, b).forEach { File(it.installPath, path).apply { parentFile.mkdirs(); writeText(it.id) } }
        File(a.installPath, "nvse_loader.exe").writeText("fixture")
        val files = listOf(ModFile(a.id, a.name, path, path, "a".repeat(64)),
            ModFile(b.id, b.name, path, path, "b".repeat(64)),
            ModFile(a.id, a.name, "nvse_loader.exe", "nvse_loader.exe", "c".repeat(64)))
        val winners = library.resolveRecordedDeploymentWinners(listOf(a, b), files)
        assertEquals("b", winners.first.single().winningModId)
        assertEquals("nvse_loader.exe", winners.second.single().normalizedPath)
        val inputs = f.service.captureTargetIntegrityInputs("fallout_nv")
        inputs.zip(listOf(winners.first, winners.second)).forEach { (input, records) ->
            DeploymentManifestRepository(input.evidenceFile).save(records.map {
                DeploymentRecord(it.normalizedPath, it.winningModId, it.sourceFilePath, it.winningModName, it.hash)
            })
        }
        assertEquals(DeploymentHealthPhase.UNVERIFIED, f.health(winners).phase)
        val reordered = library.resolveRecordedDeploymentWinners(listOf(a.copy(priority = 3), b), files)
        assertEquals("a", reordered.first.single().winningModId)
        assertEquals(DeploymentHealthPhase.PENDING_CHANGES, f.health(reordered).phase)
        val disabled = library.resolveRecordedDeploymentWinners(listOf(a.copy(enabled = false), b.copy(enabled = false)), files)
        assertEquals(2, f.health(disabled).targets.sumOf { it.pendingChanges!! })
        assertFalse(File(f.state, "mod_file_indexes").exists())
    }

    private fun journal(f: Fixture): Pair<DeploymentJournalRepository, DeploymentJournalRecord> {
        val repo = DeploymentJournalRepository(File(f.state, "deployment_journal_fallout_nv.json"))
        val record = DeploymentJournalRecord(operationId = "fixture-operation", gameId = "fallout_nv",
            profileId = f.state.name, status = DeploymentJournalStatus.STARTED, startedAtEpochMillis = 1,
            completedAtEpochMillis = null, planSummary = DeploymentJournalPlanSummary(0, 0, 0, null, null, true, 0, 0),
            resultSummary = null, failureMessage = null)
        repo.saveStarted(record)
        return repo to record
    }

    @Test fun `unfinished and failed journals need attention and completed journal does not verify disk`() {
        val f = fixture()
        val (repo, record) = journal(f)
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, f.health().phase)
        repo.markCompleted(record, DeploymentJournalResultSummary(0, 0, 0, 0, 0, 0, 0))
        assertEquals(DeploymentHealthPhase.UNVERIFIED, f.health().phase)
        val (again, started) = journal(f)
        again.markFailed(started, "fixture failure")
        assertEquals(DeploymentRecoveryFact.FAILED, f.health().recovery)
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, f.health().phase)
    }

    @Test fun `review cannot repair or verify and corrupt journal is unknown`() {
        val f = fixture()
        journal(f)
        assertTrue(f.service.markDeploymentJournalReviewed("fallout_nv"))
        val reviewed = f.health()
        assertEquals(DeploymentHealthPhase.UNVERIFIED, reviewed.phase)
        assertEquals(DeploymentRecoveryFact.REVIEWED, reviewed.recovery)
        File(f.state, "deployment_journal_fallout_nv.json").writeText("broken")
        assertEquals(DeploymentHealthPhase.UNKNOWN, f.health().phase)
    }
}
