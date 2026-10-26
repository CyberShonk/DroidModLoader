package com.shonkware.droidmodloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexSnapshot
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.service.DeploymentService
import com.shonkware.droidmodloader.engine.storage.StorageCapacityMeasurement
import com.shonkware.droidmodloader.engine.storage.StorageCapacityProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Only newly created app-cache fixtures; no real targets, disk filling or permission changes. */
@RunWith(AndroidJUnit4::class)
class DeploymentStorageCapacityTest {
    @Test
    fun lowCapacityRejectsOrdinaryAndFullDeployBeforeJournalOrTargetWrites() = fixture { root ->
        val source = File(root, "source").apply { writeText("managed data") }
        val state = File(root, "state")
        val target = File(root, "target/Data")
        val service = DeploymentService(root, File(root, "temp"), File(state, "mods.json"),
            File(state, "manifest.json"), target, File(state, "config.json"),
            { listOf(FileRecord(PATH, "fixture", "Fixture", source.path, "hash")) }, { emptyList() },
            StorageCapacityProbe { StorageCapacityMeasurement("fixture volume", 0L) })
        val before = root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        for (full in listOf(false, true)) {
            try {
                if (full) service.forceFullRedeployForGame(GAME) else service.deployForGame(GAME)
                fail("Expected capacity preflight rejection")
            } catch (failure: DeploymentPreflightException) {
                assertTrue(failure.result.issues.any { it.title.contains("Not enough free storage") })
            }
            assertFalse(target.exists())
            assertTrue(service.getDeploymentJournalDebugSummary(GAME).contains("No deploy journal found"))
            assertEquals(before, root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
        }
    }

    @Test
    fun facadeUsesNativeVolumeFactsAndPreservesOriginalThroughDeployAndRestore() = fixture { root ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = File(root, "state").apply { mkdirs() }
        val mods = File(root, "mods").apply { mkdirs() }
        val modRoot = File(mods, "fixture").apply { mkdirs() }
        val source = File(modRoot, PATH).apply { parentFile!!.mkdirs(); writeText("managed data") }
        val mod = Mod("fixture", "Fixture", modRoot.path, true, 1, ModType.LOOSE)
        val stateFile = File(state, "mods.json")
        ModStateRepository(stateFile).saveMods(listOf(mod))
        ModFileIndexRepository(File(state, "mod_file_indexes")).save(ModFileIndexSnapshot(
            1, mod.id, mod.name, mod.installPath, 1, listOf(ModFileIndexEntry(
                PATH, PATH, ManagedContentIntegrity.digest(source.readBytes()), source.length(), source.lastModified()
            ))
        ))
        val deploy = File(root, "target/Data")
        val original = "original data"
        val target = File(deploy, PATH).apply { parentFile!!.mkdirs(); writeText(original) }
        val engine = ModEngine(context, File(root, "temp"), mods, stateFile, File(state, "deployment_manifest.json"),
            deploy, File(state, "config.json"), File(state, "plugins.json"), File(state, "plugins.txt"),
            File(state, "loadorder.txt"), File(root, "archives"), File(state, "archives.json"))
        val preflight = engine.buildDeploymentPreflightForGame(GAME)
        assertTrue(preflight.canDeploy)
        val capacity = preflight.issues.single { it.title.contains("Measured deployment copy budget fits") }
        assertFalse(capacity.details.contains("Backing volume: unknown"))
        assertTrue(capacity.details.contains("Copy budget: ${source.length() + original.toByteArray().size} bytes"))
        assertTrue(capacity.details.contains("new backups"))
        val first = engine.deployForGame(GAME)
        assertEquals(1, first.addCount)
        assertEquals("managed data", target.readText())
        val manifest = state.listFiles()!!.single { it.name.startsWith("deployment_manifest_${GAME}_simulated_") }
        val record = DeploymentManifestRepository(manifest).load().single()
        val backup = File(checkNotNull(record.backupFilePath))
        assertEquals(original, backup.readText())
        engine.saveMods(listOf(mod.copy(enabled = false)))
        val restore = engine.buildDeploymentPreflightForGame(GAME)
        assertTrue(restore.canDeploy)
        assertTrue(restore.issues.single { it.title.contains("Measured deployment copy budget fits") }.details
            .contains("Copy budget: ${2L * original.toByteArray().size} bytes"))
        assertEquals(1, engine.deployForGame(GAME).removeCount)
        assertEquals(original, target.readText())
        assertFalse(backup.exists())
        assertEquals("managed data", source.readText())
    }

    private fun fixture(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "deployment-capacity-fixture-").toFile()
        try { test(root) } finally { check(root.deleteRecursively()) }
    }

    companion object {
        private const val GAME = "fallout_nv"
        private const val PATH = "textures/capacity-fixture.dds"
    }
}
