package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine

internal interface DeployRecoveryEngine {
    fun getDeploymentJournalStartupWarning(gameId: String): String?
    fun markDeploymentJournalReviewed(gameId: String): Boolean
    fun getDeploymentJournalDebugSummary(gameId: String): String
}

internal class DeployRecoveryEngineAdapter(
    private val engine: ModEngine
) : DeployRecoveryEngine {
    override fun getDeploymentJournalStartupWarning(gameId: String): String? =
        engine.getDeploymentJournalStartupWarning(gameId)

    override fun markDeploymentJournalReviewed(gameId: String): Boolean =
        engine.markDeploymentJournalReviewed(gameId)

    override fun getDeploymentJournalDebugSummary(gameId: String): String =
        engine.getDeploymentJournalDebugSummary(gameId)
}

internal class DeployRecoveryWorkflow(
    private val operationInProgressProvider: () -> Boolean,
    private val engineProvider: () -> DeployRecoveryEngine?,
    private val selectedGameIdProvider: () -> String,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val beginOperation: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val updateWarningState: (warningText: String, showDetails: Boolean) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val refreshDashboard: () -> Unit
) {
    fun checkStartup(engine: DeployRecoveryEngine) {
        try {
            val warning = engine.getDeploymentJournalStartupWarning(selectedGameIdProvider())
            if (warning.isNullOrBlank()) {
                updateWarningState("", false)
                return
            }

            appendLog("----- Previous Deploy Journal Warning -----")
            warning.lineSequence().forEach(appendLog)
            appendLog("----- Previous Deploy Journal Warning End -----")
            updateWarningState(warning, false)
            updateLastOperationStatus("Previous deploy may need review.")
        } catch (e: Exception) {
            appendError("Failed to check previous deploy journal: ${e.message}", e)
        }
    }

    fun markReviewed() {
        val engine = engineProvider()
        if (engine == null) {
            appendError("Could not mark deploy journal reviewed: engine unavailable.", null)
            return
        }

        try {
            val changed = engine.markDeploymentJournalReviewed(selectedGameIdProvider())
            if (changed) {
                appendLog("Marked unfinished deploy journal as reviewed.")
                updateLastOperationStatus("Previous deploy warning reviewed.")
            } else {
                appendLog("No unfinished deploy journal needed review.")
            }
            updateWarningState("", false)
        } catch (e: Exception) {
            appendError("Failed to mark deploy journal reviewed: ${e.message}", e)
        }

        refreshDashboard()
    }

    fun readLastJournalSummary() {
        if (operationInProgressProvider()) {
            appendLog("Ignoring deploy journal request: operation already in progress.")
            return
        }

        beginOperation("Reading last deploy journal...")
        try {
            val engine = engineProvider()
                ?: throw IllegalStateException("Could not create engine for active profile.")

            appendLog("----- Last Deploy Journal -----")
            engine.getDeploymentJournalDebugSummary(selectedGameIdProvider())
                .lineSequence()
                .forEach(appendLog)
            appendLog("----- Last Deploy Journal End -----")
            appendLog("No files were changed.")
            appendLog("RESULT: PASS")
            finishOperation("Deploy journal read.")
        } catch (e: Exception) {
            appendError("Failed to read deploy journal: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Deploy journal read failed: ${e.message}", e)
        }

        refreshDashboard()
    }
}
