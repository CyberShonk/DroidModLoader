package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.deploy.plan.*
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeploymentStorageCapacityTest {
    @Test
    fun `new deployment includes original backup on the same volume only once`() = fixture { f ->
        val source = f.source("new-data")
        f.target("old-data")
        val plan = f.plan(new = listOf(f.record(source)))
        val facts = DeploymentStorageCapacity.assess(f.targets, plan, StorageCapacityProbe {
            StorageCapacityMeasurement("shared", headroom + 15L)
        })
        assertEquals(1, facts.size)
        assertEquals(16L, facts.single().minimumRequiredBytes)
        assertEquals(2, facts.single().locations.size)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, facts.single().status)
    }

    @Test
    fun `backup volume cannot lend its spare space to the target`() = fixture { f ->
        val source = f.source("new-data")
        f.target("old-data")
        val facts = DeploymentStorageCapacity.assess(f.targets, f.plan(new = listOf(f.record(source))), StorageCapacityProbe {
            if (it == f.targets.data.backupDirectory) StorageCapacityMeasurement("backup", Long.MAX_VALUE)
            else StorageCapacityMeasurement("target", headroom + 7L)
        })
        assertEquals(2, facts.size)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, facts.first { it.volumeId == "target" }.status)
        assertEquals(StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES, facts.first { it.volumeId == "backup" }.status)
    }

    @Test
    fun `updates and force rewrites do not recopy existing backups`() = fixture { f ->
        val source = f.source("new-data")
        val backup = File(f.targets.data.backupDirectory, "original").apply { parentFile!!.mkdirs(); writeText("old backup") }
        val old = DeploymentRecord(PATH, "mod", source.path, "Mod", "old-hash", true, backup.path)
        val current = f.record(source)
        for (full in listOf(false, true)) {
            val requirements = DeploymentStorageCapacity.requirements(f.targets, f.plan(listOf(old), listOf(current), full))
            assertEquals(1, requirements.size)
            assertEquals(8L, requirements.single().minimumBytes)
            assertTrue(requirements.single().complete)
            assertEquals("old backup", backup.readText())
        }
    }

    @Test
    fun `restore budgets include one largest simultaneous fallback not every fallback`() = fixture { f ->
        val backups = listOf("12345", "123456789").mapIndexed { index, content ->
            File(f.targets.data.backupDirectory, "original$index").apply { parentFile!!.mkdirs(); writeText(content) }
        }
        val old = backups.mapIndexed { index, backup ->
            DeploymentRecord("file$index", "mod", "/unused", "Mod", "hash", true, backup.path)
        }
        val requirements = DeploymentStorageCapacity.requirements(f.targets, f.plan(old = old))
        assertEquals(2, requirements.size)
        assertEquals(14L, requirements.first { it.label.endsWith("copies") }.minimumBytes)
        assertEquals(9L, requirements.first { it.label.endsWith("fallback") }.minimumBytes)
        assertTrue(requirements.all { it.complete })
        assertTrue(backups.all { it.isFile })
    }

    @Test
    fun `delete only plan needs no copy capacity or headroom check`() = fixture { f ->
        val old = DeploymentRecord(PATH, "mod", "/unused", "Mod", "hash")
        val facts = DeploymentStorageCapacity.assess(f.targets, f.plan(old = listOf(old)), StorageCapacityProbe {
            throw AssertionError("Delete-only plan must not request a copy budget")
        })
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `source size is observed again instead of trusting planned metadata`() = fixture { f ->
        val source = f.source("new-data")
        val plan = f.plan(new = listOf(f.record(source)))
        source.writeText("larger-current-content")
        val requirements = DeploymentStorageCapacity.requirements(f.targets, plan)
        assertEquals(source.length(), requirements.single().minimumBytes)
        assertFalse(f.targets.data.deployDirectory!!.exists())
    }

    @Test
    fun `missing source after planning stays unknown not zero`() = fixture { f ->
        val source = f.source("new-data")
        val plan = f.plan(new = listOf(f.record(source)))
        check(source.delete())
        val requirements = DeploymentStorageCapacity.requirements(f.targets, plan)
        assertNull(requirements.single().minimumBytes)
        assertFalse(requirements.single().complete)
        assertTrue(requirements.single().details.contains("missing"))
    }

    @Test
    fun `backup outside owned root is not counted as accessible retained evidence`() = fixture { f ->
        val foreign = File(f.root, "foreign").apply { writeText("not owned backup") }
        val old = DeploymentRecord(PATH, "mod", "/unused", "Mod", "hash", true, foreign.path)
        val requirements = DeploymentStorageCapacity.requirements(f.targets, f.plan(old = listOf(old)))
        assertTrue(requirements.all { it.minimumBytes == null && !it.complete })
        assertEquals("not owned backup", foreign.readText())
    }

    @Test
    fun `bounded accounting keeps a lower bound and marks every uninspected budget incomplete`() = fixture { f ->
        val source = f.source("new-data")
        val plan = f.plan(new = listOf(f.record(source), f.record(source).copy(normalizedPath = "second")))
        val requirements = DeploymentStorageCapacity.requirements(f.targets, plan, operationLimit = 1)
        assertEquals(3, requirements.size)
        assertTrue(requirements.all { !it.complete && it.details.contains("limit reached") })
        assertEquals(8L, requirements.first { it.label.endsWith("copies") }.minimumBytes)
        assertFalse(f.targets.data.deployDirectory!!.exists())
        assertFalse(f.targets.data.backupDirectory.exists())
    }

    @Test
    fun `Data and Game Root copies share headroom only when their volume identity is known`() = fixture { f ->
        val source = f.source("new-data")
        val base = f.plan(new = listOf(f.record(source)))
        val plan = base.copy(rootPlan = base.dataPlan.copy(scope = DeploymentPlanScope.GAME_ROOT))
        val shared = DeploymentStorageCapacity.assess(f.targets, plan, StorageCapacityProbe {
            StorageCapacityMeasurement("shared", headroom + 16L)
        })
        assertEquals(1, shared.size)
        assertEquals(16L, shared.single().minimumRequiredBytes)
        assertEquals(StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES, shared.single().status)
        val unknown = DeploymentStorageCapacity.assess(f.targets, plan, StorageCapacityProbe {
            StorageCapacityMeasurement(availableBytes = Long.MAX_VALUE)
        })
        assertEquals(2, unknown.size)
        assertTrue(unknown.all { it.status == StorageCapacityStatus.UNKNOWN })
    }

    private val headroom = com.shonkware.droidmodloader.engine.install.ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES

    private fun fixture(test: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("dml-deploy-capacity-").toFile()
        try { test(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val targets = ResolvedDeploymentTargets("fallout_nv", null, target(GameTargetType.DATA), target(GameTargetType.GAME_ROOT))
        private fun target(type: GameTargetType) = ResolvedDeploymentTarget(
            type, DeploymentTargetIdentity("fallout_nv", if (type == GameTargetType.DATA) "simulated" else "root_simulated", "fixture"),
            File(root, "deploy/${type.name}"), File(root, "state/${type.name}.json"),
            File(root, "backups/${type.name}"), null, null, null
        )
        fun source(content: String) = File(root, "source").apply { writeText(content) }
        fun target(content: String) = File(targets.data.deployDirectory, PATH).apply { parentFile!!.mkdirs(); writeText(content) }
        fun record(source: File) = FileRecord(PATH, "mod", "Mod", source.path, "new-hash")
        fun plan(old: List<DeploymentRecord> = emptyList(), new: List<FileRecord> = emptyList(), full: Boolean = false): ScopedDeploymentPlan {
            val builder = DeploymentPlanBuilder()
            val data = if (full) builder.buildFullRedeploy(DeploymentPlanScope.DATA, old, new)
                else builder.build(DeploymentPlanScope.DATA, old, new)
            return ScopedDeploymentPlan(data, DeploymentPlan(DeploymentPlanScope.GAME_ROOT, emptyList()))
        }
    }

    companion object { private const val PATH = "uio/settings.ini" }
}
