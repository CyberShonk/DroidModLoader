package com.shonkware.droidmodloader.ui.workflow

internal class OverwriteActionWorkflowController(
    private val runInBackground: (() -> Unit) -> Unit,
    private val openOverwriteFolderPanel: () -> Unit,
    private val closeOverwriteFolderPanel: () -> Unit
) {

    fun openOverwriteFolder() {
        runInBackground {
            openOverwriteFolderPanel()
        }
    }

    fun closeOverwriteFolder() {
        closeOverwriteFolderPanel()
    }
}