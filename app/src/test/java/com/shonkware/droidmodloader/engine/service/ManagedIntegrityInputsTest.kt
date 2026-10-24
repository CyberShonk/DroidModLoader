package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexSnapshot
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ManagedIntegrityInputsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val integrity = ManagedContentIntegrity

    private fun inspection(indexDir: File) = ModInspectionService(
        modFileIndexDir = indexDir,
        currentMods = { error("Must not discover or rebuild current mods") },
        indexContent = { error("Must not rebuild indexes") },
        installedRecords = { error("Must not regenerate install evidence") },
        dataWinningRecords = { emptyList() }, rootWinningRecords = { emptyList() },
        resolvedDataTarget = { error("Not needed for install evidence") }
    )

    @Test fun `recorded install content uses existing index without rebuilding`() {
        val managed = temporary.newFolder("mods")
        val root = File(managed, "mod").apply { mkdirs() }
        val indexDir = temporary.newFolder("indexes")
        val state = temporary.newFile("state.json")
        val mod = Mod("mod", "Fixture", root.path, true, 1, ModType.LOOSE)
        ModStateRepository(state).saveMods(listOf(mod))
        File(root, "test.txt").writeText("old")
        ModFileIndexRepository(indexDir).save(ModFileIndexSnapshot(
            1, mod.id, mod.name, mod.installPath, 1,
            listOf(ModFileIndexEntry("test.txt", "test.txt", integrity.digest("old".toByteArray()), 3, 1))
        ))
        val before = indexDir.listFiles()!!.single().readBytes()
        File(root, "test.txt").writeText("new")
        val service = inspection(indexDir)
        val input = service.captureInstallIntegrityInputs(managed, state).single()
        assertEquals(1, integrity.check(input) { false }.changed)
        assertArrayEquals(before, indexDir.listFiles()!!.single().readBytes())
        root.deleteRecursively()
        assertEquals("Missing", integrity.check(service.captureInstallIntegrityInputs(managed, state).single()) { false }.rootStatus)
    }

    @Test fun `external and aliased install roots fail closed`() {
        val managed = temporary.newFolder("mods")
        val outside = temporary.newFolder("outside")
        val state = temporary.newFile("state.json")
        val indexes = temporary.newFolder("indexes")
        val alias = File(managed, "alias")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        for (root in listOf(outside, alias, File(managed, "../outside"))) {
            ModStateRepository(state).saveMods(listOf(Mod("mod", "Fixture", root.path, true, 1, ModType.LOOSE)))
            val input = inspection(indexes).captureInstallIntegrityInputs(managed, state).single()
            assertNull(input.root)
            assertNotNull(input.unavailable)
            assertFalse(integrity.check(input) { false }.complete)
        }
        assertTrue(indexes.listFiles()!!.isEmpty())
    }

    @Test fun `missing state and index do not produce clean empty installs`() {
        val managed = temporary.newFolder("mods")
        val state = File(temporary.root, "state.json")
        val indexes = temporary.newFolder("indexes")
        val service = inspection(indexes)
        assertNotNull(service.captureInstallIntegrityInputs(managed, state).single().unavailable)
        val root = File(managed, "mod").apply { mkdirs() }
        ModStateRepository(state).saveMods(listOf(Mod("mod", "Fixture", root.path, true, 1, ModType.LOOSE)))
        assertNotNull(service.captureInstallIntegrityInputs(managed, state).single().unavailable)
        assertTrue(indexes.listFiles()!!.isEmpty())
    }

    @Test fun `Data and Game Root use separate captured manifests without writes`() {
        val base = temporary.newFolder("fixture")
        val state = File(base, "state").apply { mkdirs() }
        val config = File(state, "config.json")
        val service = DeploymentService(base, File(base, "temp"), File(state, "mods.json"),
            File(state, "manifest.json"), File(base, "deploy/Data"), config, { emptyList() }, { emptyList() })
        val captured = service.captureTargetIntegrityInputs("fallout_nv")
        assertEquals(2, captured.size)
        assertNotEquals(captured[0].identity, captured[1].identity)
        assertNotEquals(captured[0].evidenceFile, captured[1].evidenceFile)
        assertFalse(File(base, "deploy").exists())
        captured.forEachIndexed { index, input ->
            input.root!!.mkdirs()
            File(input.root, "test.txt").writeText("$index")
            input.evidenceFile.writeText("""[{"normalizedPath":"test.txt","hash":"${integrity.digest("$index".toByteArray())}"}]""")
        }
        val inputs = service.captureTargetIntegrityInputs("fallout_nv")
        File(inputs[1].root, "test.txt").writeText("x")
        val counts = inputs.map { integrity.check(it) { false } }
        assertEquals(0, counts[0].changed)
        assertEquals(1, counts[1].changed)
        assertFalse(File(base, "temp").exists())
        assertFalse(File(state, "deployment_backups").exists())
    }

    @Test fun `configuration permission loss never falls back to simulated evidence`() {
        val base = temporary.newFolder("fixture")
        val state = File(base, "state").apply { mkdirs() }
        val config = File(state, "config.json")
        val service = DeploymentService(base, File(base, "temp"), File(state, "mods.json"),
            File(state, "manifest.json"), File(base, "simulated"), config, { emptyList() }, { emptyList() })
        service.saveGameDeploymentConfigs(listOf(GameDeploymentConfig("fallout_nv", "Fixture", "/missing", true)))
        Files.setPosixFilePermissions(state.toPath(), emptySet())
        try {
            assertFalse("Permission fixture requires an unprivileged runner", state.canRead())
            val input = service.captureTargetIntegrityInputs("fallout_nv").single()
            assertNull(input.root)
            assertTrue(input.unavailable!!.contains("unreadable"))
            assertFalse(input.identity.contains("simulated"))
        } finally {
            state.setReadable(true, true)
            state.setWritable(true, true)
            state.setExecutable(true, true)
        }
    }

    @Test fun `invalid real target never reads simulated target state`() {
        val base = temporary.newFolder("fixture")
        val state = File(base, "state").apply { mkdirs() }
        val config = File(state, "config.json")
        val service = DeploymentService(base, File(base, "temp"), File(state, "mods.json"),
            File(state, "manifest.json"), File(base, "simulated"), config, { emptyList() }, { emptyList() })
        service.saveGameDeploymentConfigs(listOf(GameDeploymentConfig("fallout_nv", "Fixture", "/missing", true)))
        val inputs = service.captureTargetIntegrityInputs("fallout_nv")
        assertTrue(inputs.all { it.root == null && it.unavailable != null })
        assertTrue(inputs.none { it.identity.contains("simulated") })
        config.writeText(" ")
        assertNotNull(service.captureTargetIntegrityInputs("fallout_nv").single().unavailable)
    }
}
