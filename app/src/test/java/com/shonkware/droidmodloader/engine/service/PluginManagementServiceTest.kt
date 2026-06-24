package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
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
