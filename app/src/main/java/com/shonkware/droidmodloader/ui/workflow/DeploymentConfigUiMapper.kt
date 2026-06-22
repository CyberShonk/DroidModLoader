package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.GameProfile

internal object DeploymentConfigUiMapper {
    const val NO_DATA_FOLDER_SELECTED = "No folder selected"
    const val NO_ROOT_FOLDER_SELECTED = "No root folder selected"
    const val DATA_FOLDER_RESELECTION_REQUIRED = "Reselect Data folder"
    const val ROOT_FOLDER_RESELECTION_REQUIRED = "Reselect Game Root folder"

    fun emptyState(): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = "",
            realDeployEnabled = false,
            targetRootPath = "",
            dataPathReselectionRequired = false,
            rootPathReselectionRequired = false
        )
    }

    fun fromConfig(config: GameDeploymentConfig): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = config.targetDataPath,
            realDeployEnabled = config.realDeployEnabled,
            targetRootPath = config.targetRootPath,
            dataPathReselectionRequired = config.dataPathReselectionRequired,
            rootPathReselectionRequired = config.rootPathReselectionRequired
        )
    }

    fun fromProfile(profile: GameProfile): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = profile.targetDataPath,
            realDeployEnabled = profile.realDeployEnabled,
            targetRootPath = profile.targetRootPath,
            dataPathReselectionRequired = profile.dataPathReselectionRequired,
            rootPathReselectionRequired = profile.rootPathReselectionRequired
        )
    }

    fun configFromUi(
        selectedGameId: String,
        displayName: String,
        targetPathText: String,
        realDeployEnabled: Boolean,
        rootTargetPathText: String,
        dataPathReselectionRequired: Boolean,
        rootPathReselectionRequired: Boolean
    ): GameDeploymentConfig {
        val dataPath = targetPathText.trim()
        val rootPath = rootTargetPathText.trim()

        return GameDeploymentConfig(
            gameId = selectedGameId,
            displayName = displayName,
            targetDataPath = dataPath,
            realDeployEnabled = realDeployEnabled,
            targetRootPath = rootPath,
            dataPathReselectionRequired = dataPathReselectionRequired && dataPath.isBlank(),
            rootPathReselectionRequired = rootPathReselectionRequired && rootPath.isBlank()
        )
    }

    fun configFromProfile(profile: GameProfile): GameDeploymentConfig {
        return GameDeploymentConfig(
            gameId = profile.gameId,
            displayName = profile.gameDisplayName,
            targetDataPath = profile.targetDataPath,
            realDeployEnabled = profile.realDeployEnabled,
            targetRootPath = profile.targetRootPath,
            dataPathReselectionRequired = profile.dataPathReselectionRequired,
            rootPathReselectionRequired = profile.rootPathReselectionRequired
        )
    }

    fun dataPathDisplayText(
        path: String,
        reselectionRequired: Boolean
    ): String {
        return when {
            path.isNotBlank() -> path
            reselectionRequired -> DATA_FOLDER_RESELECTION_REQUIRED
            else -> NO_DATA_FOLDER_SELECTED
        }
    }

    fun rootPathDisplayText(
        path: String,
        reselectionRequired: Boolean
    ): String {
        return when {
            path.isNotBlank() -> path
            reselectionRequired -> ROOT_FOLDER_RESELECTION_REQUIRED
            else -> NO_ROOT_FOLDER_SELECTED
        }
    }
}

internal data class DeploymentConfigUiState(
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetRootPath: String,
    val dataPathReselectionRequired: Boolean,
    val rootPathReselectionRequired: Boolean
)
