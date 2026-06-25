package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryAction
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult

internal interface InstallReplacementRecoveryEngine {
    fun recoverInterruptedInstallReplacements():
            List<InstallReplacementRecoveryResult>
}

internal class InstallReplacementRecoveryEngineAdapter(
    private val engine: ModEngine
) : InstallReplacementRecoveryEngine {
    override fun recoverInterruptedInstallReplacements():
            List<InstallReplacementRecoveryResult> {
        return engine.recoverInterruptedInstallReplacements()
    }
}

internal class InstallReplacementStartupWorkflow(
    private val appendLog: (String) -> Unit,
    private val appendError: (String) -> Unit
) {
    fun checkStartup(
        engine: InstallReplacementRecoveryEngine
    ) {
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
            return
        }

        results.forEach { result ->
            reportResult(result)
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