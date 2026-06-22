package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameProfile

internal object ProfileConfigUiMapper {

    fun fromProfile(profile: GameProfile): ProfileConfigUiState {
        return ProfileConfigUiState(
            selectedGameId = profile.gameId,
            targetDataPath = profile.targetDataPath,
            targetRootPath = profile.targetRootPath,
            realDeployEnabled = profile.realDeployEnabled,
            dataPathReselectionRequired = profile.dataPathReselectionRequired,
            rootPathReselectionRequired = profile.rootPathReselectionRequired
        )
    }

    fun emptyState(): ProfileConfigUiState {
        return ProfileConfigUiState(
            selectedGameId = "No Game Selected",
            targetDataPath = "",
            targetRootPath = "",
            realDeployEnabled = false,
            dataPathReselectionRequired = false,
            rootPathReselectionRequired = false
        )
    }

    fun updatedProfileFromDashboard(
        profile: GameProfile,
        displayName: String,
        targetPathText: String,
        rootTargetPathText: String,
        realDeployEnabled: Boolean,
        dataPathReselectionRequired: Boolean,
        rootPathReselectionRequired: Boolean
    ): GameProfile {
        val dataPath = targetPathText.trim()
        val rootPath = rootTargetPathText.trim()

        return profile.copy(
            gameDisplayName = displayName,
            targetDataPath = dataPath,
            targetRootPath = rootPath,
            realDeployEnabled = realDeployEnabled,
            dataPathReselectionRequired = dataPathReselectionRequired && dataPath.isBlank(),
            rootPathReselectionRequired = rootPathReselectionRequired && rootPath.isBlank()
        )
    }
}

internal data class ProfileConfigUiState(
    val selectedGameId: String,
    val targetDataPath: String,
    val targetRootPath: String,
    val realDeployEnabled: Boolean,
    val dataPathReselectionRequired: Boolean,
    val rootPathReselectionRequired: Boolean
)
