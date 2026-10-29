package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.InstallerDialogAction

internal class InstallerWorkflowController(
    private val finalizeInstallerInstall: () -> Unit,
    private val cancelInstallerInstall: () -> Unit,
    private val toggleInstallerOption: (String) -> Unit,
    private val handleInstallerAction: (InstallerDialogAction) -> Unit = {}
) {

    fun action(action: InstallerDialogAction) {
        handleInstallerAction(action)
    }

    fun finalizeInstall() {
        finalizeInstallerInstall()
    }

    fun cancelInstall() {
        cancelInstallerInstall()
    }

    fun toggleOption(optionId: String) {
        toggleInstallerOption(optionId)
    }
}