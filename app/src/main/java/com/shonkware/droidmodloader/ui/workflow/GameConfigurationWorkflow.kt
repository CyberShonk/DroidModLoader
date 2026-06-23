package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.GameProfile

internal interface GameConfigurationEngine {
    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig?
    fun loadGameDeploymentConfigs(): List<GameDeploymentConfig>
    fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>)
}

internal class GameConfigurationEngineAdapter(
    private val engine: ModEngine
) : GameConfigurationEngine {
    override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? =
        engine.getGameDeploymentConfig(gameId)

    override fun loadGameDeploymentConfigs(): List<GameDeploymentConfig> =
        engine.loadGameDeploymentConfigs()

    override fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>) {
        engine.saveGameDeploymentConfigs(configs)
    }
}

internal data class GameConfigurationLoadResult(
    val selectedGameId: String,
    val uiState: DeploymentConfigUiState,
    val logMessage: String
)

internal data class GameConfigurationInput(
    val selectedGameId: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetRootPath: String,
    val dataPathReselectionRequired: Boolean,
    val rootPathReselectionRequired: Boolean
)

internal class GameConfigurationWorkflow {
    fun load(
        engine: GameConfigurationEngine,
        activeProfile: GameProfile?,
        selectedGameId: String
    ): GameConfigurationLoadResult {
        val resolvedGameId = activeProfile?.gameId ?: selectedGameId
        val config = engine.getGameDeploymentConfig(resolvedGameId)

        if (config != null) {
            return GameConfigurationLoadResult(
                selectedGameId = resolvedGameId,
                uiState = DeploymentConfigUiMapper.fromConfig(config),
                logMessage = "Loaded config into Compose state: $config"
            )
        }

        val fallbackProfile = activeProfile?.takeIf { it.gameId == resolvedGameId }
        if (fallbackProfile != null) {
            val recoveredConfig = DeploymentConfigUiMapper.configFromProfile(fallbackProfile)
            upsert(engine, recoveredConfig)
            return GameConfigurationLoadResult(
                selectedGameId = resolvedGameId,
                uiState = DeploymentConfigUiMapper.fromConfig(recoveredConfig),
                logMessage = "Recovered missing config from active profile: $recoveredConfig"
            )
        }

        return GameConfigurationLoadResult(
            selectedGameId = resolvedGameId,
            uiState = DeploymentConfigUiMapper.emptyState(),
            logMessage = "No config found for gameId=$resolvedGameId"
        )
    }

    fun save(
        engine: GameConfigurationEngine,
        input: GameConfigurationInput
    ): GameDeploymentConfig {
        val updatedConfig = DeploymentConfigUiMapper.configFromUi(
            selectedGameId = input.selectedGameId,
            displayName = GameCatalog.displayName(input.selectedGameId),
            targetPathText = input.targetDataPath,
            realDeployEnabled = input.realDeployEnabled,
            rootTargetPathText = input.targetRootPath,
            dataPathReselectionRequired = input.dataPathReselectionRequired,
            rootPathReselectionRequired = input.rootPathReselectionRequired
        )
        upsert(engine, updatedConfig)
        return updatedConfig
    }

    private fun upsert(
        engine: GameConfigurationEngine,
        config: GameDeploymentConfig
    ) {
        val configs = engine.loadGameDeploymentConfigs().toMutableList()
        val index = configs.indexOfFirst { it.gameId == config.gameId }
        if (index >= 0) {
            configs[index] = config
        } else {
            configs.add(config)
        }
        engine.saveGameDeploymentConfigs(configs)
    }
}
