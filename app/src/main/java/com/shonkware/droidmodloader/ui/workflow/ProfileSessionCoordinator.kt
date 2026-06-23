package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.factory.ProfileScopedEngineFactory
import com.shonkware.droidmodloader.engine.profile.ProfileRepositoryFactory
import com.shonkware.droidmodloader.ui.MainActivityUiState

/**
 * Owns profile/session hydration and game-configuration projection into the
 * activity-scoped UI state.
 */
internal class ProfileSessionCoordinator(
    private val state: MainActivityUiState,
    private val profileRepositoryFactory: ProfileRepositoryFactory,
    private val profileScopedEngineFactory: ProfileScopedEngineFactory,
    private val profileStartupWorkflow: ProfileStartupWorkflow,
    private val gameConfigurationWorkflow: GameConfigurationWorkflow,
    private val runOnUiThreadBlocking: (() -> Unit) -> Unit,
    private val appendLog: (String) -> Unit
) {
    fun refreshGameOptions() {
        runOnUiThreadBlocking {
            state.gameOptions = GameCatalog.supportedGameIds
            state.selectedGameId = GameCatalog.supportedOrDefault(state.selectedGameId)
            state.setupGameId = GameCatalog.supportedOrDefault(state.setupGameId)
            state.setupGameDisplayName = GameCatalog.displayName(state.setupGameId)
            state.newProfileGameId = GameCatalog.supportedOrDefault(state.newProfileGameId)
            state.newProfileGameDisplayName = GameCatalog.displayName(state.newProfileGameId)
        }
    }

    fun loadSelectedGameConfigIntoUi() {
        val engine = profileScopedEngineFactory.create() ?: return
        val activeProfile = state.profileOptions.firstOrNull {
            it.profileId == state.activeProfileId
        }
        val previousGameId = state.selectedGameId
        val result = gameConfigurationWorkflow.load(
            engine = GameConfigurationEngineAdapter(engine),
            activeProfile = activeProfile,
            selectedGameId = state.selectedGameId
        )

        if (previousGameId != result.selectedGameId) {
            appendLog(
                "Corrected selectedGameId from $previousGameId to active profile game ${result.selectedGameId}"
            )
        }
        runOnUiThreadBlocking {
            state.selectedGameId = result.selectedGameId
            applyDeploymentConfigUiState(result.uiState)
        }
        appendLog(result.logMessage)
    }

    fun saveSelectedGameConfigFromUi() {
        val engine = profileScopedEngineFactory.create() ?: return
        val updatedConfig = gameConfigurationWorkflow.save(
            engine = GameConfigurationEngineAdapter(engine),
            input = GameConfigurationInput(
                selectedGameId = state.selectedGameId,
                targetDataPath = state.targetPathText,
                realDeployEnabled = state.realDeployEnabledState,
                targetRootPath = state.rootTargetPathText,
                dataPathReselectionRequired = state.dataPathReselectionRequired,
                rootPathReselectionRequired = state.rootPathReselectionRequired
            )
        )
        appendLog("Saved updated config from Compose state: $updatedConfig")
    }

    fun loadSetupState() {
        val repository = profileRepositoryFactory.create() ?: return
        val result = profileStartupWorkflow.load(
            ProfileStartupRepositoryAdapter(repository)
        )
        result.recoveryLogMessage?.let(appendLog)

        runOnUiThreadBlocking {
            state.setupComplete = result.setupState.setupComplete
            state.activeProfileId = result.setupState.activeProfileId
            state.activeProfileName = ProfileConfigUiMapper.activeProfileName(result.activeProfile)
            state.profileOptions = result.profiles
            applyProfileConfigUiState(
                result.activeProfile
                    ?.takeIf { result.setupState.setupComplete }
                    ?.let(ProfileConfigUiMapper::fromProfile)
                    ?: ProfileConfigUiMapper.emptyState()
            )
            clearVisibleProfileContent()
        }

        appendLog("Loaded setup state: ${result.setupState}")
        appendLog("Loaded profile count: ${result.profiles.size}")
        appendProfileContextLog()
    }

    fun applyDeploymentConfigUiState(uiState: DeploymentConfigUiState) {
        state.targetPathText = uiState.targetDataPath
        state.realDeployEnabledState = uiState.realDeployEnabled
        state.dataPathReselectionRequired = uiState.dataPathReselectionRequired
        state.selectedDataPathText = DeploymentConfigUiMapper.dataPathDisplayText(
            uiState.targetDataPath,
            uiState.dataPathReselectionRequired
        )
        state.rootTargetPathText = uiState.targetRootPath
        state.rootPathReselectionRequired = uiState.rootPathReselectionRequired
        state.selectedRootPathText = DeploymentConfigUiMapper.rootPathDisplayText(
            uiState.targetRootPath,
            uiState.rootPathReselectionRequired
        )
    }

    fun applyProfileConfigUiState(uiState: ProfileConfigUiState) {
        state.selectedGameId = uiState.selectedGameId
        state.targetPathText = uiState.targetDataPath
        state.dataPathReselectionRequired = uiState.dataPathReselectionRequired
        state.selectedDataPathText = DeploymentConfigUiMapper.dataPathDisplayText(
            uiState.targetDataPath,
            uiState.dataPathReselectionRequired
        )
        state.rootTargetPathText = uiState.targetRootPath
        state.rootPathReselectionRequired = uiState.rootPathReselectionRequired
        state.selectedRootPathText = DeploymentConfigUiMapper.rootPathDisplayText(
            uiState.targetRootPath,
            uiState.rootPathReselectionRequired
        )
        state.realDeployEnabledState = uiState.realDeployEnabled
    }

    fun clearVisibleProfileContent() {
        state.visibleMods = emptyList()
        state.visiblePlugins = emptyList()
        state.visibleModContentIndexes = emptyMap()
    }

    private fun appendProfileContextLog() {
        appendLog(
            "Profile context: activeProfileId=${state.activeProfileId}, " +
                "activeProfileName=${state.activeProfileName}, " +
                "selectedGameId=${state.selectedGameId}, " +
                "targetDataPath=${state.targetPathText}"
        )
    }
}
