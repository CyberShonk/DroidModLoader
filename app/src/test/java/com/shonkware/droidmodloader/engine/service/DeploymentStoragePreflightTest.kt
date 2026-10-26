package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.install.ExtractionLimits
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.storage.StorageCapacityMeasurement
import com.shonkware.droidmodloader.engine.storage.StorageCapacityProbe
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeploymentStoragePreflightTest {
    @Test
    fun `ordinary and full deployment reject known low capacity before journal or writes`() = fixture { f ->
        f.measurement = StorageCapacityMeasurement("shared", ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES + f.source.length() - 1L)
        val before = f.snapshot()
        for (full in listOf(false, true)) {
            try {
                if (full) f.service.forceFullRedeployForGame(GAME) else f.service.deployForGame(GAME)
                fail("Expected capacity rejection")
            } catch (failure: DeploymentPreflightException) {
                assertTrue(failure.result.issues.any { it.title.contains("Not enough free storage") })
            }
            assertTrue(f.service.getDeploymentJournalDebugSummary(GAME).contains("No deploy journal found"))
            assertEquals(before, f.snapshot())
            assertFalse(f.deploy.exists())
        }
    }

    @Test
    fun `missing capacity is an explicit warning not zero or permission to reserve`() = fixture { f ->
        f.measurement = StorageCapacityMeasurement(details = "Unavailable fixture probe")
        val result = f.service.buildDeploymentPreflightForGame(GAME)
        assertTrue(result.canDeploy)
        val issue = result.issues.single { it.title.contains("storage capacity is unknown") }
        assertTrue(issue.details.contains("Available: unknown"))
        assertTrue(issue.details.contains("Backing volume: unknown"))
        assertTrue(issue.details.contains("not a reservation"))
        assertFalse(f.deploy.exists())
        val deployed = f.service.deployForGame(GAME)
        assertEquals(1, deployed.addCount)
        assertEquals("new-data", File(f.deploy, PATH).readText())
    }

    @Test
    fun `empty plan does not reject low capacity or call a probe`() = fixture { f ->
        f.winners.clear()
        f.onProbe = { throw AssertionError("Empty plan must not probe capacity") }
        assertTrue(f.service.buildDeploymentPreflightForGame(GAME).canDeploy)
        assertFalse(f.deploy.exists())
    }

    @Test
    fun `delete-only plan is not blocked by copy headroom`() = fixture { f ->
        f.service.deployForGame(GAME)
        f.winners.clear()
        f.measurement = StorageCapacityMeasurement("shared", 0L)
        f.onProbe = { throw AssertionError("Delete-only plan must not probe copy capacity") }
        val result = f.service.deployForGame(GAME)
        assertEquals(1, result.removeCount)
        assertFalse(File(f.deploy, PATH).exists())
        assertEquals("new-data", f.source.readText())
    }

    @Test
    fun `config change during capacity probing fails the captured target check before journal`() = fixture { f ->
        var changed = false
        f.onProbe = {
            if (!changed) {
                changed = true
                f.service.saveGameDeploymentConfigs(listOf(GameDeploymentConfig(GAME, "Changed fixture", "/unused", false)))
            }
        }
        try {
            f.service.deployForGame(GAME)
            fail("Expected changed target snapshot rejection")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("target identity changed"))
        }
        assertFalse(f.deploy.exists())
        assertTrue(f.service.getDeploymentJournalDebugSummary(GAME).contains("No deploy journal found"))
        assertEquals("new-data", f.source.readText())
    }

    @Test
    fun `both diagnostic plan paths use the same capacity findings`() = fixture { f ->
        f.measurement = StorageCapacityMeasurement("shared", 0L)
        for (summary in listOf(f.service.buildDeploymentPlanDebugSummary(GAME), f.service.buildFullRedeployPlanDebugSummary(GAME))) {
            assertTrue(summary.contains("Not enough free storage for the deployment copy budget"))
            assertTrue(summary.contains("Available: 0 bytes"))
            assertTrue(summary.contains("DML managed simulated target"))
        }
        assertFalse(f.deploy.exists())
    }

    @Test
    fun `location diagnostics never resolve source content or turn unknown requirements into an empty plan`() = fixture { f ->
        val service = DeploymentService(f.root, File(f.root, "temp"), File(f.root, "state/mods.json"),
            File(f.root, "state/manifest.json"), f.deploy, File(f.root, "state/config.json"),
            { throw AssertionError("Read-only location inspection must not build source indexes") },
            { throw AssertionError("Read-only location inspection must not resolve source content") })
        val before = f.snapshot()
        val (locations, notes) = service.storageDiagnosticRequirements(GAME)
        assertEquals(4, locations.size)
        assertTrue(locations.all { it.minimumBytes == null && !it.complete })
        assertTrue(notes.any { it.contains("requirements are unknown") })
        assertEquals(before, f.snapshot())
        assertFalse(f.deploy.exists())
    }

    @Test
    fun `corrupt diagnostic target configuration cannot silently select simulated storage`() = fixture { f ->
        File(f.root, "state/config.json").apply { parentFile!!.mkdirs(); writeText("broken config") }
        val before = f.snapshot()
        val (locations, notes) = f.service.storageDiagnosticRequirements(GAME)
        assertTrue(locations.isEmpty())
        assertTrue(notes.any { it.contains("no simulated fallback") })
        assertEquals(before, f.snapshot())
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("dml-capacity-preflight-").toFile()
        try { test(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val source = File(root, "source").apply { writeText("new-data") }
        val winners = mutableListOf(FileRecord(PATH, "mod", "Mod", source.path, "hash"))
        val deploy = File(root, "deploy/Data")
        var measurement = StorageCapacityMeasurement("shared", Long.MAX_VALUE)
        var onProbe: () -> Unit = {}
        val service = DeploymentService(
            File(root, "files"), File(root, "temp"), File(root, "state/mods.json"),
            File(root, "state/manifest.json"), deploy, File(root, "state/config.json"),
            { winners.toList() }, { emptyList() }, StorageCapacityProbe { onProbe(); measurement }
        )
        fun snapshot() = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }
    }

    companion object {
        private const val GAME = "fallout_nv"
        private const val PATH = "uio/settings.ini"
    }
}
