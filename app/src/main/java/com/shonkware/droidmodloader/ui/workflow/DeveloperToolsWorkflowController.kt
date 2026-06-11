package com.shonkware.droidmodloader.ui.workflow

internal class DeveloperToolsWorkflowController(
    private val runInBackground: (() -> Unit) -> Unit,
    private val repairV050Artifacts: () -> Unit,
    private val buildResolvedDataGraph: () -> Unit,
    private val showArchiveLibrarySummary: () -> Unit
) {

    fun repairV050Artifacts() {
        runInBackground {
            repairV050Artifacts.invoke()
        }
    }

    fun buildResolvedDataGraph() {
        runInBackground {
            buildResolvedDataGraph.invoke()
        }
    }

    fun showArchiveLibrarySummary() {
        runInBackground {
            showArchiveLibrarySummary.invoke()
        }
    }
}