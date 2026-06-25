package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryAction
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InstallReplacementStartupWorkflowTest {
    @Test
    fun `recovered transactions are logged`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val workflow = workflow(logs, errors)
        val engine = FakeEngine(
            results = listOf(
                InstallReplacementRecoveryResult(
                    transactionFileName =
                        "_dml_transaction_test.properties",
                    action =
                        InstallReplacementRecoveryAction
                            .RESTORED_PREVIOUS_INSTALL,
                    failureMessage = null
                )
            )
        )

        workflow.checkStartup(engine)

        assertEquals(1, logs.size)
        assertTrue(
            logs.single().contains(
                "restored the previous installed mod"
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unresolved transactions are reported as errors`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val workflow = workflow(logs, errors)
        val engine = FakeEngine(
            results = listOf(
                InstallReplacementRecoveryResult(
                    transactionFileName =
                        "_dml_transaction_test.properties",
                    action = null,
                    failureMessage =
                        "Install transaction requires manual recovery."
                )
            )
        )

        workflow.checkStartup(engine)

        assertTrue(logs.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(
            errors.single().contains(
                "requires manual recovery"
            )
        )
    }

    @Test
    fun `all recovery results are reported`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val workflow = workflow(logs, errors)
        val engine = FakeEngine(
            results = listOf(
                InstallReplacementRecoveryResult(
                    transactionFileName =
                        "_dml_transaction_one.properties",
                    action =
                        InstallReplacementRecoveryAction
                            .DISCARDED_STAGED_INSTALL,
                    failureMessage = null
                ),
                InstallReplacementRecoveryResult(
                    transactionFileName =
                        "_dml_transaction_two.properties",
                    action = null,
                    failureMessage = "Ambiguous filesystem state."
                )
            )
        )

        workflow.checkStartup(engine)

        assertEquals(1, logs.size)
        assertEquals(1, errors.size)
    }

    @Test
    fun `recovery check failure is reported without escaping`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val workflow = workflow(logs, errors)
        val engine = FakeEngine(
            failure = IOException(
                "Forced recovery failure"
            )
        )

        workflow.checkStartup(engine)

        assertTrue(logs.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(
            errors.single().contains(
                "Forced recovery failure"
            )
        )
    }

    @Test
    fun `no transactions produces no messages`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val workflow = workflow(logs, errors)

        workflow.checkStartup(
            FakeEngine(results = emptyList())
        )

        assertTrue(logs.isEmpty())
        assertTrue(errors.isEmpty())
    }

    private fun workflow(
        logs: MutableList<String>,
        errors: MutableList<String>
    ): InstallReplacementStartupWorkflow {
        return InstallReplacementStartupWorkflow(
            appendLog = logs::add,
            appendError = errors::add
        )
    }

    private class FakeEngine(
        private val results:
        List<InstallReplacementRecoveryResult> =
            emptyList(),
        private val failure: Exception? = null
    ) : InstallReplacementRecoveryEngine {
        override fun recoverInterruptedInstallReplacements():
                List<InstallReplacementRecoveryResult> {
            failure?.let { throw it }
            return results
        }
    }
}