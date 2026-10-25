package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryAction
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult

internal interface InstallReplacementRecoveryEngine {
    fun recoverInterruptedInstallReplacements():
            List<InstallReplacementRecoveryResult>
}

internal class InstallReplacementRecoveryEngineAdapter(
    val engine: ModEngine,
    val profileId: String?,
    val gameId: String
) : InstallReplacementRecoveryEngine {
    override fun recoverInterruptedInstallReplacements():
            List<InstallReplacementRecoveryResult> {
        return engine.recoverInterruptedInstallReplacements()
    }
}

internal class InstallReplacementStartupWorkflow(
    private val appendLog: (String) -> Unit,
    private val appendError: (String) -> Unit,
    private val onInstallRecoveryAttention: (
        profileId: String?,
        gameId: String,
        requiresAttention: Boolean
    ) -> Unit,
    private val postToUiThread: ((() -> Unit) -> Unit) = { action -> action() }
) {
    private data class RecoveryScope(
        val profileId: String?,
        val gameId: String
    )

    private val evaluationLock = Any()
    private val newestEvaluationByScope = mutableMapOf<RecoveryScope, Any>()

    fun checkStartup(
        engine: InstallReplacementRecoveryEngine,
        profileId: String?,
        gameId: String
    ) {
        val scope = RecoveryScope(profileId, gameId)
        val evaluation = Any()
        synchronized(evaluationLock) {
            newestEvaluationByScope[scope] = evaluation
        }

        val results = try {
            engine.recoverInterruptedInstallReplacements()
        } catch (exception: Exception) {
            appendError(
                "Could not check interrupted mod installations: " +
                        (
                                exception.message
                                    ?: exception::class.java.simpleName
                                )
            )
            null
        }

        results?.forEach { result ->
            reportResult(result)
        }
        val requiresAttention = results == null || results.any { it.action == null }
        postToUiThread {
            synchronized(evaluationLock) {
                if (newestEvaluationByScope[scope] === evaluation) {
                    onInstallRecoveryAttention(
                        scope.profileId,
                        scope.gameId,
                        requiresAttention
                    )
                }
            }
        }
    }

    private fun reportResult(
        result: InstallReplacementRecoveryResult
    ) {
        val action = result.action

        if (action != null) {
            appendLog(
                "Install recovery: " +
                        actionDescription(action) +
                        " (${result.transactionFileName})"
            )
            return
        }

        appendError(
            "Install recovery requires attention for " +
                    "${result.transactionFileName}: " +
                    (
                            result.failureMessage
                                ?: "The interrupted installation " +
                                "could not be recovered automatically."
                            )
        )
    }

    private fun actionDescription(
        action: InstallReplacementRecoveryAction
    ): String {
        return when (action) {
            InstallReplacementRecoveryAction
                .DISCARDED_STAGED_INSTALL -> {
                "discarded an incomplete staged installation"
            }

            InstallReplacementRecoveryAction
                .RESTORED_PREVIOUS_INSTALL -> {
                "restored the previous installed mod"
            }

            InstallReplacementRecoveryAction
                .COMPLETED_PROMOTED_INSTALL -> {
                "completed cleanup for the promoted installation"
            }

            InstallReplacementRecoveryAction
                .CLEARED_STALE_TRANSACTION -> {
                "cleared a stale transaction record"
            }
        }
    }
}
