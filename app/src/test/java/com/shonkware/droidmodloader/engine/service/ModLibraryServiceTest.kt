package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModLibraryServiceTest {
    @Test
    fun `installed folders are ordered and classified without saved state`() {
        val fixture = fixture("folder-scan")
        File(fixture.modsDir, "Zeta").apply {
            mkdirs()
            File(this, "textures/test.dds").apply {
                parentFile?.mkdirs()
                writeText("texture")
            }
        }
        File(fixture.modsDir, "Alpha").apply {
            mkdirs()
            File(this, "Alpha.esp").writeText("plugin")
        }

        val mods = fixture.service.getInstalledModsFromFolders()

        assertEquals(listOf("Alpha", "Zeta"), mods.map { it.id })
        assertEquals(listOf(1, 2), mods.map { it.priority })
        assertEquals(ModType.ARCHIVE, mods[0].modType)
        assertEquals(ModType.LOOSE, mods[1].modType)
        assertFalse(fixture.service.hasSavedState())
    }

    @Test
    fun `saving current mods normalizes priorities and persists state`() {
        val fixture = fixture("saved-state")
        val mods = listOf(
            fixture.mod("second", priority = 20),
            fixture.mod("first", priority = 10)
        )

        fixture.service.saveCurrentMods(mods)

        assertEquals(listOf(1, 2), fixture.service.loadMods().map { it.priority })
        assertTrue(fixture.service.hasSavedState())
    }

    @Test
    fun `uninstall removes folder and saved entry`() {
        val fixture = fixture("uninstall")
        val modDir = File(fixture.modsDir, "remove-me").apply {
            mkdirs()
            File(this, "file.txt").writeText("one")
        }
        fixture.service.saveCurrentMods(
            listOf(fixture.mod("remove-me", priority = 1, installPath = modDir.absolutePath))
        )

        val result = fixture.service.uninstallModAndApplyDiff("remove-me")

        assertTrue(result.removed)
        assertEquals(1, result.deletedFileCount)
        assertFalse(modDir.exists())
        assertTrue(fixture.service.getCurrentMods().isEmpty())
    }

    private fun fixture(name: String): Fixture {
        val root = Files.createTempDirectory("dml-mod-library-$name").toFile()
        val tempDir = File(root, "temp").apply { mkdirs() }
        val modsDir = File(root, "mods").apply { mkdirs() }
        val stateDir = File(root, "state").apply { mkdirs() }
        return Fixture(
            modsDir = modsDir,
            service = ModLibraryService(
                tempDir = tempDir,
                modsDir = modsDir,
                stateFile = File(stateDir, "mods.json"),
                deploymentManifestFile = File(stateDir, "deployment_manifest.json"),
                deployRootDir = File(root, "deploy"),
                gameConfigFile = File(stateDir, "game_config.json"),
                pluginListFile = File(stateDir, "plugins.json"),
                pluginsTxtFile = File(stateDir, "plugins.txt"),
                loadorderTxtFile = File(stateDir, "loadorder.txt")
            )
        )
    }

    private data class Fixture(
        val modsDir: File,
        val service: ModLibraryService
    ) {
        fun mod(
            id: String,
            priority: Int,
            installPath: String = File(modsDir, id).absolutePath
        ): Mod = Mod(
            id = id,
            name = id,
            installPath = installPath,
            enabled = true,
            priority = priority,
            modType = ModType.LOOSE
        )
    }
}
