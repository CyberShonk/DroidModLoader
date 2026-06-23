package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedFolderConfigurationCoordinatorTest {
    @Test
    fun `saving data folder updates state persists profile and creates baseline`() {
        val state = MutableMainActivityUiState().apply {
            selectedGameId = "fallout_nv"
            dataPathReselectionRequired = true
        }
        val events = mutableListOf<String>()
        val coordinator = coordinator(state, events)

        coordinator.saveDataFolder("/games/FNV/Data")

        assertEquals("/games/FNV/Data", state.targetPathText)
        assertEquals("/games/FNV/Data", state.selectedDataPathText)
        assertFalse(state.dataPathReselectionRequired)
        assertTrue(state.realDeployEnabledState)
        assertEquals(
            listOf("ui", "save-config", "save-profile", "baseline:target folder selected", "refresh"),
            events.take(5)
        )
        assertTrue(events.last().contains("fallout_nv"))
    }

    @Test
    fun `saving game root does not create data baseline`() {
        val state = MutableMainActivityUiState().apply {
            selectedGameId = "skyrim_le"
            rootPathReselectionRequired = true
        }
        val events = mutableListOf<String>()
        val coordinator = coordinator(state, events)

        coordinator.saveGameRoot("/games/Skyrim")

        assertEquals("/games/Skyrim", state.rootTargetPathText)
        assertEquals("/games/Skyrim", state.selectedRootPathText)
        assertFalse(state.rootPathReselectionRequired)
        assertTrue(state.realDeployEnabledState)
        assertFalse(events.any { it.startsWith("baseline:") })
    }

    private fun coordinator(
        state: MutableMainActivityUiState,
        events: MutableList<String>
    ): SelectedFolderConfigurationCoordinator {
        return SelectedFolderConfigurationCoordinator(
            state = state,
            runOnUiThreadBlocking = { action ->
                events += "ui"
                action()
            },
            saveSelectedGameConfig = { events += "save-config" },
            saveActiveProfile = { events += "save-profile" },
            ensureDataBaselineIfMissing = { reason -> events += "baseline:$reason" },
            refreshDashboard = { events += "refresh" },
            appendLog = { message -> events += "log:$message" }
        )
    }
}
