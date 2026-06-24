package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeploymentServiceTest {
    @Test
    fun `deployment config round trips through service`() {
        val fixture = fixture("config")
        val config = GameDeploymentConfig(
            gameId = "fallout_nv",
            displayName = "Fallout New Vegas",
            targetDataPath = "/games/fnv/Data",
            realDeployEnabled = true,
            targetRootPath = "/games/fnv"
        )

        fixture.service.saveGameDeploymentConfigs(listOf(config))

        assertEquals(config, fixture.service.getGameDeploymentConfig("fallout_nv"))
    }

    @Test
    fun `simulated target summary is stable when real deployment is unavailable`() {
        val fixture = fixture("summary")

        val summary = fixture.service.getDeploymentTargetDebugSummary("skyrim_le")

        assertTrue(summary.contains("mode=simulated"))
        assertTrue(summary.contains("deployment_manifest_skyrim_le_simulated_"))
        assertTrue(summary.contains("deployment_manifest_root_skyrim_le_root_simulated_"))
    }

    @Test
    fun `empty winners create an empty deployment plan`() {
        val fixture = fixture("plan")

        val plan = fixture.service.buildDeploymentPlanForGame("oblivion")

        assertEquals(0, plan.totalOperationCount)
    }

    private fun fixture(name: String): Fixture {
        val root = Files.createTempDirectory("dml-deployment-service-$name").toFile()
        val stateDir = File(root, "state").apply { mkdirs() }
        return Fixture(
            DeploymentService(
                appFilesDir = File(root, "files").apply { mkdirs() },
                tempDir = File(root, "temp").apply { mkdirs() },
                stateFile = File(stateDir, "mods.json"),
                deploymentManifestFile = File(stateDir, "deployment_manifest.json"),
                deployRootDir = File(root, "deploy").apply { mkdirs() },
                gameConfigFile = File(stateDir, "game_config.json"),
                currentDataWinningRecords = { emptyList() },
                currentRootWinningRecords = { emptyList() }
            )
        )
    }

    private data class Fixture(val service: DeploymentService)
}
