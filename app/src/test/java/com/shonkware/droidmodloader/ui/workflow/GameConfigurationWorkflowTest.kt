package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameConfigurationWorkflowTest {

    @Test
    fun `load recovers missing config from active profile`() {
        val engine = FakeEngine()
        val profile = GameProfile(
            profileId = "p1",
            profileName = "FNV",
            gameId = "fallout_nv",
            gameDisplayName = "Fallout New Vegas",
            targetDataPath = "/games/fnv/Data",
            realDeployEnabled = true,
            targetRootPath = "/games/fnv"
        )

        val result = GameConfigurationWorkflow().load(engine, profile, "skyrim_le")

        assertEquals("fallout_nv", result.selectedGameId)
        assertEquals("/games/fnv/Data", result.uiState.targetDataPath)
        assertEquals(1, engine.saved.size)
        assertTrue(result.logMessage.startsWith("Recovered missing config"))
    }

    @Test
    fun `save replaces existing game config`() {
        val engine = FakeEngine(
            configs = mutableListOf(
                GameDeploymentConfig("fallout_nv", "FNV", "/old", false)
            )
        )

        val saved = GameConfigurationWorkflow().save(
            engine,
            GameConfigurationInput(
                selectedGameId = "fallout_nv",
                targetDataPath = "/new/Data",
                realDeployEnabled = true,
                targetRootPath = "/new",
                dataPathReselectionRequired = false,
                rootPathReselectionRequired = false
            )
        )

        assertEquals("/new/Data", saved.targetDataPath)
        assertEquals(1, engine.saved.size)
        assertEquals("/new/Data", engine.saved.single().targetDataPath)
    }

    private class FakeEngine(
        private val configs: MutableList<GameDeploymentConfig> = mutableListOf()
    ) : GameConfigurationEngine {
        var saved: List<GameDeploymentConfig> = configs.toList()

        override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? =
            saved.firstOrNull { it.gameId == gameId }

        override fun loadGameDeploymentConfigs(): List<GameDeploymentConfig> = saved

        override fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>) {
            saved = configs.toList()
        }
    }
}
