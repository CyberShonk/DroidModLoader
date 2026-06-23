package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.AppSetupState
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.profile.ProfileRepository

internal data class FirstSetupInput(
    val profileNameText: String,
    val gameId: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean
)

internal data class AdditionalProfileInput(
    val profileNameText: String,
    val gameId: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean
)

internal data class DashboardProfileInput(
    val targetPathText: String,
    val rootTargetPathText: String,
    val realDeployEnabled: Boolean,
    val dataPathReselectionRequired: Boolean,
    val rootPathReselectionRequired: Boolean
)

internal class ProfileManagementWorkflow(
    private val repositoryProvider: () -> ProfileRepository?,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val gameDisplayNameProvider: (String) -> String,
    private val firstSetupInputProvider: () -> FirstSetupInput,
    private val additionalProfileInputProvider: () -> AdditionalProfileInput,
    private val activeProfileIdProvider: () -> String?,
    private val dashboardProfileInputProvider: () -> DashboardProfileInput,
    private val applyFirstSetupUiState: (List<GameProfile>, GameProfile) -> Unit,
    private val applyCreatedProfileUiState: (List<GameProfile>, GameProfile) -> Unit,
    private val applySwitchedProfileUiState: (GameProfile) -> Unit,
    private val applySavedProfileUiState: (List<GameProfile>, GameProfile) -> Unit,
    private val applyDeletedProfileUiStateAsync: (List<GameProfile>, GameProfile?) -> Unit,
    private val applyDeletedProfileUiStateBlocking: (List<GameProfile>, GameProfile?) -> Unit,
    private val saveSelectedGameConfigFromUi: () -> Unit,
    private val loadSelectedGameConfigIntoUi: () -> Unit,
    private val syncPluginsFromCurrentState: () -> Unit,
    private val refreshDashboard: () -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit
) {

    fun completeFirstSetup() {
        val repo = repositoryProvider() ?: return
        val input = firstSetupInputProvider()

        val profileId = "${input.gameId}_${currentTimeMillis()}"
        val cleanProfileName = input.profileNameText.trim().ifBlank { "Default" }

        val profile = GameProfile(
            profileId = profileId,
            profileName = cleanProfileName,
            gameId = input.gameId,
            gameDisplayName = gameDisplayNameProvider(input.gameId),
            targetDataPath = input.targetDataPath.trim(),
            realDeployEnabled = input.realDeployEnabled,
            targetRootPath = "",
            dataPathReselectionRequired = false,
            rootPathReselectionRequired = false,
            iniPresetId = null
        )

        val existingProfiles = repo.loadProfiles().toMutableList()
        existingProfiles.add(profile)

        repo.saveProfiles(existingProfiles)
        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profileId
            )
        )

        applyFirstSetupUiState(existingProfiles, profile)
        saveSelectedGameConfigFromUi()

        appendLog("Created first profile: $profile")
        updateLastOperationStatus("Setup complete.")
        refreshDashboard()
    }

    fun createAdditionalProfile() {
        val repo = repositoryProvider() ?: return
        val input = additionalProfileInputProvider()

        val cleanProfileName = input.profileNameText.trim().ifBlank {
            "${gameDisplayNameProvider(input.gameId)} Profile"
        }

        val profileId = "${input.gameId}_${currentTimeMillis()}"

        val profile = GameProfile(
            profileId = profileId,
            profileName = cleanProfileName,
            gameId = input.gameId,
            gameDisplayName = gameDisplayNameProvider(input.gameId),
            targetDataPath = input.targetDataPath.trim(),
            realDeployEnabled = input.realDeployEnabled,
            targetRootPath = "",
            dataPathReselectionRequired = false,
            rootPathReselectionRequired = false,
            iniPresetId = null
        )

        val profiles = repo.loadProfiles().toMutableList()
        profiles.add(profile)
        repo.saveProfiles(profiles)
        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profile.profileId
            )
        )

        applyCreatedProfileUiState(profiles, profile)
        saveSelectedGameConfigFromUi()
        syncPluginsFromCurrentState()
        refreshDashboard()

        appendLog("Created and switched to profile: $profile")
        updateLastOperationStatus("Profile created and selected: ${profile.profileName}")
    }

    fun switchActiveProfile(profileId: String) {
        val repo = repositoryProvider() ?: return
        val profiles = repo.loadProfiles()
        val profile = profiles.firstOrNull { it.profileId == profileId }

        if (profile == null) {
            appendError("Could not switch profile: profile not found: $profileId")
            return
        }

        saveActiveProfileFromDashboard()

        repo.saveSetupState(
            AppSetupState(
                setupComplete = true,
                activeProfileId = profile.profileId
            )
        )

        applySwitchedProfileUiState(profile)
        loadSelectedGameConfigIntoUi()
        syncPluginsFromCurrentState()
        refreshDashboard()

        appendLog("Switched active profile: $profile")
        updateLastOperationStatus("Switched profile: ${profile.profileName}")
    }

    fun saveDashboardSettings() {
        saveSelectedGameConfigFromUi()
        saveActiveProfileFromDashboard()
        refreshDashboard()
    }

    fun saveActiveProfileFromDashboard() {
        val repo = repositoryProvider() ?: return
        val currentProfileId = activeProfileIdProvider()

        if (currentProfileId == null) {
            appendError("Cannot save active profile: no active profile.")
            return
        }

        val profiles = repo.loadProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.profileId == currentProfileId }

        if (index == -1) {
            appendError("Active profile not found: $currentProfileId")
            return
        }

        val oldProfile = profiles[index]
        val dashboardInput = dashboardProfileInputProvider()
        val updatedProfile = ProfileConfigUiMapper.updatedProfileFromDashboard(
            profile = oldProfile,
            displayName = gameDisplayNameProvider(oldProfile.gameId),
            targetPathText = dashboardInput.targetPathText,
            rootTargetPathText = dashboardInput.rootTargetPathText,
            realDeployEnabled = dashboardInput.realDeployEnabled,
            dataPathReselectionRequired = dashboardInput.dataPathReselectionRequired,
            rootPathReselectionRequired = dashboardInput.rootPathReselectionRequired
        )

        profiles[index] = updatedProfile
        repo.saveProfiles(profiles)

        applySavedProfileUiState(profiles, updatedProfile)
        appendLog("Saved active profile: $updatedProfile")
    }

    fun deleteProfile(profileId: String) {
        val repo = repositoryProvider() ?: return

        val profiles = repo.loadProfiles().toMutableList()
        val profileToDelete = profiles.firstOrNull { it.profileId == profileId }

        if (profileToDelete == null) {
            appendError("Profile not found: $profileId")
            return
        }

        profiles.removeAll { it.profileId == profileId }
        repo.saveProfiles(profiles)

        val currentActiveProfileId = activeProfileIdProvider()
        val newActiveProfile = if (currentActiveProfileId == profileId) {
            profiles.firstOrNull()
        } else {
            profiles.firstOrNull { it.profileId == currentActiveProfileId }
        }

        repo.saveSetupState(
            AppSetupState(
                setupComplete = profiles.isNotEmpty(),
                activeProfileId = newActiveProfile?.profileId
            )
        )

        applyDeletedProfileUiStateAsync(profiles, newActiveProfile)

        appendLog("Deleted profile settings only: ${profileToDelete.profileName}")
        updateLastOperationStatus("Deleted profile: ${profileToDelete.profileName}")

        applyDeletedProfileUiStateBlocking(profiles, newActiveProfile)
    }
}
