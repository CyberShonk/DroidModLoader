package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployRecoveryWorkflowTest {

    @Test
    fun `startup warning exposes recovery attention without resolving journal`() {
        val recoveryStates = mutableListOf<RecoveryState>()
        val statuses = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val workflow = createWorkflow(
            engine = FakeEngine(warning = "unfinished deploy"),
            recoveryStates = recoveryStates,
            statuses = statuses,
            logs = logs
        )

        workflow.checkStartup(FakeEngine(warning = "unfinished deploy"))

        assertEquals(
            listOf(
                RecoveryState(
                    warningText = "unfinished deploy",
                    attentionRequired = true,
                    showDetails = false
                )
            ),
            recoveryStates
        )
        assertEquals(listOf("Previous deploy may need review."), statuses)
        assertTrue(logs.contains("unfinished deploy"))
    }

    @Test
    fun `startup without unfinished journal clears recovery attention`() {
        val recoveryStates = mutableListOf<RecoveryState>()
        val workflow = createWorkflow(
            engine = FakeEngine(warning = null),
            recoveryStates = recoveryStates,
            statuses = mutableListOf(),
            logs = mutableListOf()
        )

        workflow.checkStartup(FakeEngine(warning = null))

        assertEquals(
            listOf(
                RecoveryState(
                    warningText = "",
                    attentionRequired = false,
                    showDetails = false
                )
            ),
            recoveryStates
        )
    }

    @Test
    fun `mark reviewed reloads unresolved attention and refreshes`() {
        val recoveryStates = mutableListOf<RecoveryState>()
        val statuses = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var refreshes = 0
        val workflow = createWorkflow(
            engine = FakeEngine(warning = "reviewed not repaired", markChanged = true),
            recoveryStates = recoveryStates,
            statuses = statuses,
            logs = logs,
            refreshDashboard = { refreshes++ }
        )

        workflow.markReviewed()

        assertEquals(
            listOf(
                RecoveryState(
                    warningText = "reviewed not repaired",
                    attentionRequired = true,
                    showDetails = false
                )
            ),
            recoveryStates
        )
        assertEquals(listOf("Previous deploy warning reviewed.", "Previous deploy may need review."), statuses)
        assertTrue(logs.contains("Marked unfinished deploy journal as reviewed."))
        assertEquals(1, refreshes)
    }

    @Test
    fun `mark reviewed failure does not falsely clear recovery attention`() {
        val recoveryStates = mutableListOf<RecoveryState>()
        val statuses = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val workflow = createWorkflow(
            engine = ThrowingMarkEngine(),
            recoveryStates = recoveryStates,
            statuses = statuses,
            logs = logs
        )

        workflow.markReviewed()

        assertTrue(recoveryStates.isEmpty())
        assertTrue(statuses.isEmpty())
        assertTrue(logs.any { it.startsWith("ERROR:Failed to mark deploy journal reviewed") })
    }

    private fun createWorkflow(
        engine: DeployRecoveryEngine,
        recoveryStates: MutableList<RecoveryState>,
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
            updateRecoveryState = { text, attentionRequired, show ->
                recoveryStates += RecoveryState(text, attentionRequired, show)
            },
            updateLastOperationStatus = statuses::add,
            refreshDashboard = refreshDashboard
        )
    }

    private data class RecoveryState(
        val warningText: String,
        val attentionRequired: Boolean,
        val showDetails: Boolean
    )

    private class FakeEngine(
        private val warning: String? = null,
        private val markChanged: Boolean = false
    ) : DeployRecoveryEngine {
        override fun getDeploymentJournalStartupWarning(gameId: String): String? = warning
        override fun markDeploymentJournalReviewed(gameId: String): Boolean = markChanged
        override fun getDeploymentJournalDebugSummary(gameId: String): String = "journal"
    }

    private class ThrowingMarkEngine : DeployRecoveryEngine {
        override fun getDeploymentJournalStartupWarning(gameId: String): String? = null

        override fun markDeploymentJournalReviewed(gameId: String): Boolean {
            throw IllegalStateException("boom")
        }

        override fun getDeploymentJournalDebugSummary(gameId: String): String = "journal"
    }
}
