package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.MainActivityUiState
import java.util.concurrent.atomic.AtomicLong

internal class DashboardRefreshCoordinator(
    private val state: MainActivityUiState,
    private val buildResult: () -> DashboardRefreshResult?,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val appendLog: (String) -> Unit
) {
    private val refreshRevision = AtomicLong()

    /** True means facts were collected and publication was scheduled, not that a queued result won. */
    fun refresh(): Boolean {
        val revision = refreshRevision.incrementAndGet()
        val context = state.deploymentHealthContextKey()
        val result = buildResult() ?: return false
        runOnUiThread {
            if (revision != refreshRevision.get() || context != state.deploymentHealthContextKey()) return@runOnUiThread
            state.deploymentHealth = result.deploymentHealth
            state.deploymentHealthContext = context
            state.visibleMods = result.mods
            state.visiblePlugins = result.plugins
            state.visibleModContentIndexes = result.modContentIndexes
            state.visibleModFlags = result.modFlags
            state.summaryText = result.summaryText
            appendLog("Dashboard refreshed.")
        }
        return true
    }
}
