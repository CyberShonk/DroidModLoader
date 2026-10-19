package com.shonkware.droidmodloader.ui.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Retained user intent; native display/window ownership stays in the Activity. */
internal class SecondScreenPluginCoordinator(
    private val appendLog: (String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit
) {
    var enabled by mutableStateOf(false)
        private set

    fun toggle() {
        enabled = !enabled
        val status = if (enabled) {
            "Second screen plugin display enabled."
        } else {
            "Second screen plugin display disabled."
        }
        appendLog(status)
        updateLastOperationStatus(status)
    }
}
