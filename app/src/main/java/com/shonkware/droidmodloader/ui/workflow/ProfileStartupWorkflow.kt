package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.AppSetupState
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.profile.ProfileRepository

internal interface ProfileStartupRepository {
    fun loadSetupState(): AppSetupState
    fun loadProfiles(): List<GameProfile>
    fun saveSetupState(state: AppSetupState)
}

internal class ProfileStartupRepositoryAdapter(
    private val repository: ProfileRepository
) : ProfileStartupRepository {
    override fun loadSetupState(): AppSetupState = repository.loadSetupState()
    override fun loadProfiles(): List<GameProfile> = repository.loadProfiles()
    override fun saveSetupState(state: AppSetupState) = repository.saveSetupState(state)
}

internal data class ProfileStartupResult(
    val setupState: AppSetupState,
    val profiles: List<GameProfile>,
    val activeProfile: GameProfile?,
    val recoveryLogMessage: String?
)

internal class ProfileStartupWorkflow {
    fun load(repository: ProfileStartupRepository): ProfileStartupResult {
        val loadedState = repository.loadSetupState()
        val profiles = repository.loadProfiles()
        var resolvedState = loadedState
        var activeProfile = profiles.firstOrNull { it.profileId == loadedState.activeProfileId }
        var recoveryLogMessage: String? = null

        if (activeProfile == null && profiles.isNotEmpty()) {
            activeProfile = profiles.first()
            resolvedState = AppSetupState(
                setupComplete = true,
                activeProfileId = activeProfile.profileId
            )
            repository.saveSetupState(resolvedState)
            recoveryLogMessage =
                "Recovered missing active profile using: ${activeProfile.profileName}"
        }

        return ProfileStartupResult(
            setupState = resolvedState,
            profiles = profiles,
            activeProfile = activeProfile,
            recoveryLogMessage = recoveryLogMessage
        )
    }
}
