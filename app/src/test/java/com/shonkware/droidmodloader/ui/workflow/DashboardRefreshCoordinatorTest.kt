package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardRefreshCoordinatorTest {
    @Test
    fun `refresh projects result into UI state and updates second screen`() {
        val state = MutableMainActivityUiState()
        val events = mutableListOf<String>()
        val coordinator = DashboardRefreshCoordinator(
            state = state,
            buildResult = {
                DashboardRefreshResult(
                    mods = emptyList(),
                    plugins = emptyList(),
                    modContentIndexes = emptyMap(),
                    summaryText = "summary"
                )
            },
            runOnUiThread = { action ->
                events += "ui"
                action()
            },
            refreshSecondScreen = { events += "second-screen" },
            appendLog = { events += it }
        )

        coordinator.refresh()

        assertEquals("summary", state.summaryText)
        assertEquals(listOf("ui", "second-screen", "Dashboard refreshed."), events)
    }

    @Test
    fun `missing result leaves UI state unchanged`() {
        val state = MutableMainActivityUiState().apply { summaryText = "existing" }
        val coordinator = DashboardRefreshCoordinator(
            state = state,
            buildResult = { null },
            runOnUiThread = { it() },
            refreshSecondScreen = {},
            appendLog = {}
        )

        coordinator.refresh()

        assertEquals("existing", state.summaryText)
    }
}
