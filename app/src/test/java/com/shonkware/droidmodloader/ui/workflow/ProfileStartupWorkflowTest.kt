package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.AppSetupState
import com.shonkware.droidmodloader.engine.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileStartupWorkflowTest {

    @Test
    fun `missing active profile falls back to first profile and persists setup state`() {
        val profile = GameProfile(
            profileId = "p1",
            profileName = "Default",
            gameId = "skyrim_le",
            gameDisplayName = "Skyrim Legendary Edition",
            targetDataPath = "/games/skyrim/Data",
            realDeployEnabled = true
        )
        val repository = FakeRepository(
            setupState = AppSetupState(setupComplete = true, activeProfileId = "missing"),
            profiles = listOf(profile)
        )

        val result = ProfileStartupWorkflow().load(repository)

        assertEquals("p1", result.setupState.activeProfileId)
        assertEquals(profile, result.activeProfile)
        assertNotNull(result.recoveryLogMessage)
        assertEquals(result.setupState, repository.savedState)
    }

    private class FakeRepository(
        private val setupState: AppSetupState,
        private val profiles: List<GameProfile>
    ) : ProfileStartupRepository {
        var savedState: AppSetupState? = null
        override fun loadSetupState(): AppSetupState = setupState
        override fun loadProfiles(): List<GameProfile> = profiles
        override fun saveSetupState(state: AppSetupState) {
            savedState = state
        }
    }
}
