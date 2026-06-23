package com.shonkware.droidmodloader.ui.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.ui.SecondScreenController

internal class SecondScreenPluginCoordinator(
    private val controllerProvider: () -> SecondScreenController?,
    private val pluginsProvider: () -> List<PluginEntry>,
    private val activeProfileNameProvider: () -> String,
    private val appendLog: (String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val showToast: (String) -> Unit
) {
    var enabled by mutableStateOf(false)
        private set

    fun onResume() {
        if (!enabled) return
        controllerProvider()?.start()
        refresh()
    }

    fun onPause() {
        controllerProvider()?.stop()
    }

    fun refresh() {
        if (!enabled) return
        controllerProvider()?.update(
            plugins = pluginsProvider(),
            activeProfileName = activeProfileNameProvider()
        )
    }

    fun toggle() {
        enabled = !enabled
        val status = if (enabled) {
            controllerProvider()?.start()
            refresh()
            "Second screen plugin display enabled."
        } else {
            controllerProvider()?.stop()
            "Second screen plugin display disabled."
        }

        appendLog(status)
        updateLastOperationStatus(status)
        showToast(status)
    }
}
