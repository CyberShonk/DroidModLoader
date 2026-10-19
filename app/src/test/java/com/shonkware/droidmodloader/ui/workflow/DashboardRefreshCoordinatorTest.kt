package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.flags.ModFlag
import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import org.junit.Assert.assertEquals
import org.junit.Test
class DashboardRefreshCoordinatorTest {
    @Test
    fun `refresh projects result into observable UI state`() {
        val state = MutableMainActivityUiState()
        val events = mutableListOf<String>()
        val flags = mapOf(
            "test-mod" to setOf(ModFlag.NO_VALID_GAME_DATA)
        )
        val coordinator = DashboardRefreshCoordinator(
            state = state,
            buildResult = {
                DashboardRefreshResult(
                    mods = emptyList(),
                    plugins = emptyList(),
                    modContentIndexes = emptyMap(),
                    modFlags = flags,
                    summaryText = "summary"
                )
            },
            runOnUiThread = { action ->
                events += "ui"
                action()
            },
            appendLog = { events += it }
        )
        coordinator.refresh()

        assertEquals("summary", state.summaryText)
        assertEquals(flags, state.visibleModFlags)
        assertEquals(listOf("ui", "Dashboard refreshed."), events)
    }
    @Test
    fun `missing result leaves UI state unchanged`() {
        val state = MutableMainActivityUiState().apply { summaryText = "existing" }
        val coordinator = DashboardRefreshCoordinator(
            state = state,
            buildResult = { null },
            runOnUiThread = { it() },
            appendLog = {}
        )

        coordinator.refresh()

        assertEquals("existing", state.summaryText)
    }
}
