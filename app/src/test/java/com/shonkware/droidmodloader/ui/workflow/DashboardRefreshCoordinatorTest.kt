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
    fun `queued result for an old profile is not published`() {
        val state = MutableMainActivityUiState().apply { activeProfileId = "a"; summaryText = "existing" }
        val queued = mutableListOf<() -> Unit>()
        val log = mutableListOf<String>()
        val coordinator = DashboardRefreshCoordinator(state,
            { DashboardRefreshResult(emptyList(), emptyList(), emptyMap(), emptyMap(), "old") },
            { queued += it }, log::add)
        coordinator.refresh()
        state.activeProfileId = "b"
        queued.single().invoke()
        assertEquals("existing", state.summaryText)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `only latest refresh publishes after A to B to A`() {
        val state = MutableMainActivityUiState().apply { activeProfileId = "a" }
        val queued = mutableListOf<() -> Unit>()
        var builds = 0
        val coordinator = DashboardRefreshCoordinator(state,
            { DashboardRefreshResult(emptyList(), emptyList(), emptyMap(), emptyMap(), "build-${++builds}") },
            { queued += it }, {})
        coordinator.refresh()
        state.activeProfileId = "b"
        coordinator.refresh()
        state.activeProfileId = "a"
        coordinator.refresh()
        queued.forEach { it() }
        assertEquals("build-3", state.summaryText)
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
