package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameProfile

internal object ProfileConfigUiMapper {

    fun fromProfile(profile: GameProfile): ProfileConfigUiState {
        return ProfileConfigUiState(
            selectedGameId = profile.gameId,
            targetDataPath = profile.targetDataPath,
            targetTreeUriText = profile.targetTreeUri ?: DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED,
            targetRootPath = profile.targetRootPath,
            targetRootTreeUriText = profile.targetRootTreeUri ?: DeploymentConfigUiMapper.NO_ROOT_FOLDER_SELECTED,
            realDeployEnabled = profile.realDeployEnabled
        )
    }

    fun emptyState(): ProfileConfigUiState {
        return ProfileConfigUiState(
            selectedGameId = "No Game Selected",
            targetDataPath = "",
            targetTreeUriText = DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED,
            targetRootPath = "",
            targetRootTreeUriText = DeploymentConfigUiMapper.NO_ROOT_FOLDER_SELECTED,
            realDeployEnabled = false
        )
    }

    fun updatedProfileFromDashboard(
        profile: GameProfile,
        displayName: String,
        targetPathText: String,
        selectedTreeUriText: String,
        rootTargetPathText: String,
        selectedRootTreeUriText: String,
        realDeployEnabled: Boolean
    ): GameProfile {
        return profile.copy(
            gameId = profile.gameId,
            gameDisplayName = displayName,
            targetDataPath = targetPathText.trim(),
            targetTreeUri = dataTreeUriFromText(selectedTreeUriText),
            targetRootPath = rootTargetPathText.trim(),
            targetRootTreeUri = rootTreeUriFromText(selectedRootTreeUriText),
            realDeployEnabled = realDeployEnabled
        )
    }

    fun dataTreeUriFromText(text: String): String? {
        return text
            .trim()
            .takeIf { value ->
                value.isNotBlank() &&
                        value != DeploymentConfigUiMapper.NO_DATA_FOLDER_SELECTED
            }
    }

    fun rootTreeUriFromText(text: String): String? {
        return text
            .trim()
            .takeIf { value ->
                value.isNotBlank() &&
                        value != DeploymentConfigUiMapper.NO_ROOT_FOLDER_SELECTED
            }
    }
}

internal data class ProfileConfigUiState(
    val selectedGameId: String,
    val targetDataPath: String,
    val targetTreeUriText: String,
    val targetRootPath: String,
    val targetRootTreeUriText: String,
    val realDeployEnabled: Boolean
)