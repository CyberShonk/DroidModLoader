package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.model.GameProfile

internal object DeploymentConfigUiMapper {

    const val NO_DATA_FOLDER_SELECTED = "No folder selected"
    const val NO_ROOT_FOLDER_SELECTED = "No root folder selected"

    fun emptyState(): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = "",
            realDeployEnabled = false,
            targetTreeUriText = NO_DATA_FOLDER_SELECTED,
            targetRootPath = "",
            targetRootTreeUriText = NO_ROOT_FOLDER_SELECTED
        )
    }

    fun fromConfig(config: GameDeploymentConfig): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = config.targetDataPath,
            realDeployEnabled = config.realDeployEnabled,
            targetTreeUriText = config.targetTreeUri ?: NO_DATA_FOLDER_SELECTED,
            targetRootPath = config.targetRootPath,
            targetRootTreeUriText = config.targetRootTreeUri ?: NO_ROOT_FOLDER_SELECTED
        )
    }

    fun fromProfile(profile: GameProfile): DeploymentConfigUiState {
        return DeploymentConfigUiState(
            targetDataPath = profile.targetDataPath,
            realDeployEnabled = profile.realDeployEnabled,
            targetTreeUriText = profile.targetTreeUri ?: NO_DATA_FOLDER_SELECTED,
            targetRootPath = profile.targetRootPath,
            targetRootTreeUriText = profile.targetRootTreeUri ?: NO_ROOT_FOLDER_SELECTED
        )
    }

    fun configFromUi(
        selectedGameId: String,
        displayName: String,
        targetPathText: String,
        realDeployEnabled: Boolean,
        selectedTreeUriText: String,
        rootTargetPathText: String,
        selectedRootTreeUriText: String
    ): GameDeploymentConfig {
        return GameDeploymentConfig(
            gameId = selectedGameId,
            displayName = displayName,
            targetDataPath = targetPathText.trim(),
            realDeployEnabled = realDeployEnabled,
            targetTreeUri = selectedTreeUriText.toNullableTreeUri(NO_DATA_FOLDER_SELECTED),
            targetRootPath = rootTargetPathText.trim(),
            targetRootTreeUri = selectedRootTreeUriText.toNullableTreeUri(NO_ROOT_FOLDER_SELECTED)
        )
    }

    fun configFromProfile(profile: GameProfile): GameDeploymentConfig {
        return GameDeploymentConfig(
            gameId = profile.gameId,
            displayName = profile.gameDisplayName,
            targetDataPath = profile.targetDataPath,
            realDeployEnabled = profile.realDeployEnabled,
            targetTreeUri = profile.targetTreeUri,
            targetRootPath = profile.targetRootPath,
            targetRootTreeUri = profile.targetRootTreeUri
        )
    }

    private fun String.toNullableTreeUri(placeholder: String): String? {
        return trim().takeIf { value ->
            value.isNotBlank() && value != placeholder
        }
    }
}

internal data class DeploymentConfigUiState(
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetTreeUriText: String,
    val targetRootPath: String,
    val targetRootTreeUriText: String
)