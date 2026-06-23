package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployRecoveryWorkflowTest {

    @Test
    fun `startup warning updates visible recovery state`() {
        val warnings = mutableListOf<Pair<String, Boolean>>()
        val statuses = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val workflow = createWorkflow(
            engine = FakeEngine(warning = "unfinished deploy"),
            warnings = warnings,
            statuses = statuses,
            logs = logs
        )

        workflow.checkStartup(FakeEngine(warning = "unfinished deploy"))

        assertEquals(listOf("unfinished deploy" to false), warnings)
        assertEquals(listOf("Previous deploy may need review."), statuses)
        assertTrue(logs.contains("unfinished deploy"))
    }

    @Test
    fun `mark reviewed clears warning and refreshes`() {
        val warnings = mutableListOf<Pair<String, Boolean>>()
        val statuses = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var refreshes = 0
        val workflow = createWorkflow(
            engine = FakeEngine(markChanged = true),
            warnings = warnings,
            statuses = statuses,
            logs = logs,
            refreshDashboard = { refreshes++ }
        )

        workflow.markReviewed()

        assertEquals(listOf("" to false), warnings)
        assertEquals(listOf("Previous deploy warning reviewed."), statuses)
        assertTrue(logs.contains("Marked unfinished deploy journal as reviewed."))
        assertEquals(1, refreshes)
    }

    private fun createWorkflow(
        engine: DeployRecoveryEngine,
        warnings: MutableList<Pair<String, Boolean>>,
        statuses: MutableList<String>,
        logs: MutableList<String>,
        refreshDashboard: () -> Unit = {}
    ): DeployRecoveryWorkflow {
        return DeployRecoveryWorkflow(
            operationInProgressProvider = { false },
            engineProvider = { engine },
            selectedGameIdProvider = { "fallout_nv" },
            appendLog = logs::add,
            appendError = { message, _ -> logs += "ERROR:$message" },
            beginOperation = {},
            finishOperation = {},
            failOperation = { message, _ -> logs += "FAILED:$message" },
            updateWarningState = { text, show -> warnings += text to show },
            updateLastOperationStatus = statuses::add,
            refreshDashboard = refreshDashboard
        )
    }

    private class FakeEngine(
        private val warning: String? = null,
        private val markChanged: Boolean = false
    ) : DeployRecoveryEngine {
        override fun getDeploymentJournalStartupWarning(gameId: String): String? = warning
        override fun markDeploymentJournalReviewed(gameId: String): Boolean = markChanged
        override fun getDeploymentJournalDebugSummary(gameId: String): String = "journal"
    }
}
