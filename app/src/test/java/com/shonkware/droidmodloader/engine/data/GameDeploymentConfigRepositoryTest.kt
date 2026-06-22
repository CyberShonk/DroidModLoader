package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameDeploymentConfigRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyTreeUrisBecomeReselectionFlags() {
        val configFile = temporaryFolder.newFile("game_deployment_configs.json")
        configFile.writeText(
            """
            [
              {
                "gameId": "oblivion",
                "displayName": "Oblivion",
                "targetDataPath": "",
                "targetTreeUri": "content://legacy-data",
                "targetRootPath": "",
                "targetRootTreeUri": "content://legacy-root",
                "realDeployEnabled": true
              }
            ]
            """.trimIndent()
        )

        val config = GameDeploymentConfigRepository(configFile).load().single()

        assertTrue(config.dataPathReselectionRequired)
        assertTrue(config.rootPathReselectionRequired)
        assertNull(config.targetTreeUri)
        assertNull(config.targetRootTreeUri)
    }

    @Test
    fun directConfigRoundTripOmitsLegacyUriKeys() {
        val configFile = temporaryFolder.newFile("game_deployment_configs.json")
        val repository = GameDeploymentConfigRepository(configFile)

        repository.save(
            listOf(
                GameDeploymentConfig(
                    gameId = "fallout_nv",
                    displayName = "Fallout New Vegas",
                    targetDataPath = "/storage/emulated/0/Games/FNV/Data",
                    realDeployEnabled = true,
                    targetRootPath = "/storage/emulated/0/Games/FNV"
                )
            )
        )

        val stored = configFile.readText()
        assertFalse(stored.contains("targetTreeUri"))
        assertFalse(stored.contains("targetRootTreeUri"))

        val reloaded = repository.load().single()
        assertEquals("/storage/emulated/0/Games/FNV/Data", reloaded.targetDataPath)
        assertEquals("/storage/emulated/0/Games/FNV", reloaded.targetRootPath)
        assertFalse(reloaded.dataPathReselectionRequired)
        assertFalse(reloaded.rootPathReselectionRequired)
    }
}
