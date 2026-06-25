package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginManagementServiceTest {
    @Test
    fun `saved plugins are persisted in priority order`() {
        val fixture = fixture("persist")
        fixture.service.savePlugins(
            listOf(
                plugin("second.esp", priority = 2),
                plugin("first.esm", priority = 1, pluginType = "ESM")
            )
        )

        assertEquals(
            listOf("first.esm", "second.esp"),
            fixture.service.loadPlugins().map { it.normalizedPath }
        )
    }

    @Test
    fun `plugin priorities are normalized from one`() {
        val fixture = fixture("normalize")
        val normalized = fixture.service.normalizePluginPriorities(
            listOf(
                plugin("a.esp", priority = 20),
                plugin("b.esp", priority = 50)
            )
        )

        assertEquals(listOf(1, 2), normalized.map { it.priority })
    }

    @Test
    fun `persisted plugins follow requested order`() {
        val fixture = fixture("order")
        fixture.service.savePlugins(
            listOf(
                plugin("base.esm", priority = 1, pluginType = "ESM"),
                plugin("one.esp", priority = 2),
                plugin("two.esp", priority = 3)
            )
        )

        fixture.service.applyPluginPriorityOrder(
            listOf("two.esp", "base.esm", "one.esp")
        )

        val saved = fixture.service.loadPlugins()
        assertEquals(listOf("two.esp", "base.esm", "one.esp"), saved.map { it.normalizedPath })
        assertEquals(listOf(1, 2, 3), saved.map { it.priority })
    }

    @Test
    fun `ttw data folder scan labels core and unmanaged plugins correctly`() {
        val root = Files.createTempDirectory("dml-plugin-service-ttw").toFile()
        val stateDir = File(root, "state").apply { mkdirs() }
        val dataDir = File(root, "Data").apply { mkdirs() }
        val deployDir = File(root, "deploy").apply { mkdirs() }

        File(dataDir, "TaleOfTwoWastelands.esm").writeText("ttw")
        File(dataDir, "YUPTTW.esm").writeText("yup")
        File(dataDir, "ExampleMod.esp").writeText("mod")

        val config = GameDeploymentConfig(
            gameId = "ttw",
            displayName = "Tale of Two Wastelands",
            targetDataPath = dataDir.absolutePath,
            realDeployEnabled = true,
            targetRootPath = root.absolutePath
        )

        val service = PluginManagementService(
            pluginListFile = File(stateDir, "plugins.json"),
            pluginsTxtFile = File(stateDir, "plugins.txt"),
            loadorderTxtFile = File(stateDir, "loadorder.txt"),
            deployRootDir = deployDir,
            getCurrentMods = { emptyList() },
            getGameDeploymentConfig = { gameId ->
                config.takeIf { it.gameId == gameId }
            },
            validateTargetDataPath = { path ->
                path == dataDir.absolutePath
            }
        )

        val scanned = service.scanDataFolderPlugins("ttw")
        val pluginsByName = scanned.associateBy { it.pluginName }

        assertEquals(
            listOf(
                "TaleOfTwoWastelands.esm",
                "YUPTTW.esm",
                "ExampleMod.esp"
            ),
            scanned.map { it.pluginName }
        )

        val ttwCore = requireNotNull(
            pluginsByName["TaleOfTwoWastelands.esm"]
        )
        assertEquals("ttw_core", ttwCore.sourceModId)
        assertEquals("ttw_core", ttwCore.sourceType)
        assertEquals("TTW Core", ttwCore.sourceModName)
        assertEquals(17, ttwCore.priority)
        assertEquals("ESM", ttwCore.pluginType)
        assertTrue(ttwCore.enabled)
        assertTrue(ttwCore.locked)
        assertTrue(ttwCore.filePresentInDataFolder)

        val yupTtw = requireNotNull(
            pluginsByName["YUPTTW.esm"]
        )
        assertEquals("ttw_core", yupTtw.sourceModId)
        assertEquals("ttw_core", yupTtw.sourceType)
        assertEquals("TTW Core", yupTtw.sourceModName)
        assertEquals(18, yupTtw.priority)
        assertEquals("ESM", yupTtw.pluginType)
        assertTrue(yupTtw.enabled)
        assertTrue(yupTtw.locked)
        assertTrue(yupTtw.filePresentInDataFolder)

        val unmanaged = requireNotNull(
            pluginsByName["ExampleMod.esp"]
        )
        assertEquals("unmanaged_data", unmanaged.sourceModId)
        assertEquals("unmanaged_data", unmanaged.sourceType)
        assertEquals("Unmanaged Data Folder", unmanaged.sourceModName)
        assertEquals("ESP", unmanaged.pluginType)
        assertFalse(unmanaged.enabled)
        assertFalse(unmanaged.locked)
        assertTrue(unmanaged.filePresentInDataFolder)
        assertTrue(unmanaged.priority > yupTtw.priority)
    }

    private fun fixture(name: String): Fixture {
        val root = Files.createTempDirectory("dml-plugin-service-$name").toFile()
        val stateDir = File(root, "state").apply { mkdirs() }
        return Fixture(
            PluginManagementService(
                pluginListFile = File(stateDir, "plugins.json"),
                pluginsTxtFile = File(stateDir, "plugins.txt"),
                loadorderTxtFile = File(stateDir, "loadorder.txt"),
                deployRootDir = File(root, "deploy").apply { mkdirs() },
                getCurrentMods = { emptyList() },
                getGameDeploymentConfig = { null },
                validateTargetDataPath = { false }
            )
        )
    }

    private data class Fixture(val service: PluginManagementService)

    private fun plugin(
        path: String,
        priority: Int,
        pluginType: String = "ESP"
    ) = PluginEntry(
        normalizedPath = path,
        pluginName = path,
        sourceModId = "test-mod",
        sourceModName = "Test Mod",
        enabled = true,
        priority = priority,
        pluginType = pluginType
    )
}
