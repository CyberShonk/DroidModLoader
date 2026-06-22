package com.shonkware.droidmodloader.ui.workflow

import java.io.File

internal class ArchiveImportWorkflowController(
    private val appendLog: (String) -> Unit,
    private val runInBackground: (() -> Unit) -> Unit,
    private val handleImportedArchive: (File) -> Unit,
    private val showArchiveLibrarySummary: () -> Unit
) {
    fun handleArchivePath(path: String?) {
        val archive = path
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

        if (archive == null) {
            appendLog("No archive selected.")
            return
        }

        runInBackground {
            handleImportedArchive(archive)
        }
    }

    fun requestArchiveLibrarySummary() {
        runInBackground {
            showArchiveLibrarySummary()
        }
    }
}
