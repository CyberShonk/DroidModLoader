package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.MainActivityUiState

internal class DashboardRefreshCoordinator(
    private val state: MainActivityUiState,
    private val buildResult: () -> DashboardRefreshResult?,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val appendLog: (String) -> Unit
) {
    fun refresh() {
        val result = buildResult() ?: return
        runOnUiThread {
            state.visibleMods = result.mods
            state.visiblePlugins = result.plugins
            state.visibleModContentIndexes = result.modContentIndexes
            state.visibleModFlags = result.modFlags
            state.summaryText = result.summaryText
        }
        appendLog("Dashboard refreshed.")
    }
}
