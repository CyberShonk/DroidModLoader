package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryAction
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class InstallReplacementStartupWorkflowTest {
    @Test
    fun `recovered transactions are logged without attention`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)
        val engine = FakeEngine(
            results = InstallReplacementRecoveryAction.entries.map { action ->
                InstallReplacementRecoveryResult(
                    transactionFileName = "_dml_transaction_test.properties",
                    action = action,
                    failureMessage = null
                )
            }
        )

        workflow.checkStartup(engine, profileId = "profile-a", gameId = "fallout_nv")

        assertEquals(
            listOf(
                "discarded an incomplete staged installation",
                "restored the previous installed mod",
                "completed cleanup for the promoted installation",
                "cleared a stale transaction record"
            ).map { "Install recovery: $it (_dml_transaction_test.properties)" },
            logs
        )
        assertTrue(errors.isEmpty())
        assertEquals(listOf(false), attention)
    }

    @Test
    fun `unresolved transactions are reported as errors and request attention`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)
        val engine = FakeEngine(
            results = listOf(unresolvedResult())
        )

        workflow.checkStartup(engine, profileId = "profile-a", gameId = "fallout_nv")

        assertTrue(logs.isEmpty())
        assertEquals(
            listOf(
                "Install recovery requires attention for _dml_transaction_test.properties: " +
                    "Install transaction requires manual recovery."
            ),
            errors
        )
        assertEquals(listOf(true), attention)
    }

    @Test
    fun `all recovery results are reported with one aggregate attention signal`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)
        val engine = FakeEngine(
            results = listOf(
                unresolvedResult(),
                unresolvedResult().copy(
                    transactionFileName = "_dml_transaction_two.properties",
                    failureMessage = "Ambiguous filesystem state."
                ),
                recoveredResult()
            )
        )

        workflow.checkStartup(engine, profileId = "profile-a", gameId = "fallout_nv")

        assertEquals(1, logs.size)
        assertEquals(2, errors.size)
        assertTrue(errors.last().contains("Ambiguous filesystem state."))
        assertEquals(listOf(true), attention)
    }

    @Test
    fun `recovery check failure is reported without escaping or emitting a clean signal`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)
        val engine = FakeEngine(
            failure = IOException("Forced recovery failure")
        )

        workflow.checkStartup(engine, profileId = "profile-a", gameId = "fallout_nv")

        assertTrue(logs.isEmpty())
        assertEquals(
            listOf("Could not check interrupted mod installations: Forced recovery failure"),
            errors
        )
        assertTrue(attention.isEmpty())
    }

    @Test
    fun `no transactions produces no messages and a clean signal`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)

        workflow.checkStartup(
            FakeEngine(results = emptyList()),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        assertTrue(logs.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(listOf(false), attention)
    }

    @Test
    fun `action selection determines attention rather than failure message`() {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val attention = mutableListOf<Boolean>()
        val workflow = workflow(logs, errors, attention)

        workflow.checkStartup(
            FakeEngine(results = listOf(recoveredResult().copy(failureMessage = "Diagnostic"))),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult().copy(failureMessage = null))),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        assertEquals(1, logs.size)
        assertEquals(
            listOf(
                "Install recovery requires attention for _dml_transaction_test.properties: " +
                    "The interrupted installation could not be recovered automatically."
            ),
            errors
        )
        assertEquals(listOf(false, true), attention)
    }

    @Test
    fun `subsequent clean evaluation clears previously required session attention`() {
        var requiresAttention = false
        val attention = mutableListOf<Boolean>()
        val workflow = InstallReplacementStartupWorkflow(
            appendLog = {},
            appendError = {},
            onInstallRecoveryAttention = { _, _, required ->
                requiresAttention = required
                attention.add(required)
            }
        )

        for (cleanResults in listOf(emptyList(), listOf(recoveredResult()))) {
            workflow.checkStartup(
                FakeEngine(results = listOf(unresolvedResult())),
                profileId = "profile-a",
                gameId = "fallout_nv"
            )
            assertTrue(requiresAttention)

            workflow.checkStartup(
                FakeEngine(results = cleanResults),
                profileId = "profile-a",
                gameId = "fallout_nv"
            )
            assertFalse(requiresAttention)
        }

        assertEquals(listOf(true, false, true, false), attention)
    }

    @Test
    fun `failed evaluation preserves previously required session attention`() {
        var requiresAttention = false
        val workflow = InstallReplacementStartupWorkflow(
            appendLog = {},
            appendError = {},
            onInstallRecoveryAttention = { _, _, required -> requiresAttention = required }
        )
        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult())),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        assertTrue(requiresAttention)

        workflow.checkStartup(
            FakeEngine(failure = IOException("Forced recovery failure")),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        assertTrue(requiresAttention)
    }

    @Test
    fun `recovery signal retains evaluation scope when the active selection changes`() {
        var activeProfileId = "profile-a"
        var selectedGameId = "fallout_nv"
        val attention = mutableListOf<Triple<String?, String, Boolean>>()
        val workflow = InstallReplacementStartupWorkflow(
            appendLog = {},
            appendError = {},
            onInstallRecoveryAttention = { profileId, gameId, required ->
                attention.add(Triple(profileId, gameId, required))
            }
        )
        val engine = object : InstallReplacementRecoveryEngine {
            override fun recoverInterruptedInstallReplacements():
                    List<InstallReplacementRecoveryResult> {
                activeProfileId = "profile-b"
                selectedGameId = "skyrim_le"
                return listOf(unresolvedResult())
            }
        }

        workflow.checkStartup(engine, profileId = activeProfileId, gameId = selectedGameId)

        assertEquals("profile-b", activeProfileId)
        assertEquals("skyrim_le", selectedGameId)
        assertEquals(listOf(Triple("profile-a", "fallout_nv", true)), attention)
    }

    @Test
    fun `older clean evaluation cannot clear newer same-scope attention`() {
        val uiThread = QueuedUiThread()
        val publications = mutableListOf<Triple<String?, String, Boolean>>()
        val workflow = queuedWorkflow(uiThread, publications)

        workflow.checkStartup(
            FakeEngine(results = emptyList()),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult())),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        uiThread.runLast()
        uiThread.runNext()

        assertEquals(
            listOf(Triple("profile-a", "fallout_nv", true)),
            publications
        )
    }

    @Test
    fun `older attention evaluation cannot resurrect after newer same-scope clean`() {
        val uiThread = QueuedUiThread()
        val publications = mutableListOf<Triple<String?, String, Boolean>>()
        val workflow = queuedWorkflow(uiThread, publications)

        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult())),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        workflow.checkStartup(
            FakeEngine(results = emptyList()),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        uiThread.runLast()
        uiThread.runNext()

        assertEquals(
            listOf(Triple("profile-a", "fallout_nv", false)),
            publications
        )
    }

    @Test
    fun `scope A may publish while scope B recovery is active`() {
        val uiThread = QueuedUiThread()
        val publications = mutableListOf<Triple<String?, String, Boolean>>()
        val workflow = queuedWorkflow(uiThread, publications)
        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult())),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        val scopeBEngine = BlockingEngine(results = emptyList())
        val scopeBTask = runAsync {
            workflow.checkStartup(
                scopeBEngine,
                profileId = "profile-b",
                gameId = "skyrim_le"
            )
        }

        scopeBEngine.awaitRecoveryStarted()
        val publicationsWhileScopeBActive: List<Triple<String?, String, Boolean>>
        try {
            uiThread.runNext()
            publicationsWhileScopeBActive = publications.toList()
        } finally {
            scopeBEngine.releaseRecovery()
        }
        scopeBTask.get(10, TimeUnit.SECONDS)

        assertEquals(
            listOf(Triple("profile-a", "fallout_nv", true)),
            publicationsWhileScopeBActive
        )
        uiThread.runNext()
        assertEquals(
            listOf(
                Triple("profile-a", "fallout_nv", true),
                Triple("profile-b", "skyrim_le", false)
            ),
            publications
        )
    }

    @Test
    fun `failed newer same-scope evaluation does not restore older authority`() {
        val uiThread = QueuedUiThread()
        val publications = mutableListOf<Triple<String?, String, Boolean>>()
        val errors = mutableListOf<String>()
        val workflow = queuedWorkflow(uiThread, publications, errors)
        workflow.checkStartup(
            FakeEngine(results = listOf(unresolvedResult())),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )

        workflow.checkStartup(
            FakeEngine(failure = IOException("Forced recovery failure")),
            profileId = "profile-a",
            gameId = "fallout_nv"
        )
        uiThread.runNext()

        assertTrue(publications.isEmpty())
        assertEquals(
            listOf(
                "Install recovery requires attention for _dml_transaction_test.properties: " +
                    "Install transaction requires manual recovery.",
                "Could not check interrupted mod installations: Forced recovery failure"
            ),
            errors
        )
    }

    private fun queuedWorkflow(
        uiThread: QueuedUiThread,
        publications: MutableList<Triple<String?, String, Boolean>>,
        errors: MutableList<String> = mutableListOf()
    ): InstallReplacementStartupWorkflow {
        return InstallReplacementStartupWorkflow(
            appendLog = {},
            appendError = errors::add,
            onInstallRecoveryAttention = { profileId, gameId, requiresAttention ->
                publications.add(Triple(profileId, gameId, requiresAttention))
            },
            postToUiThread = uiThread::post
        )
    }

    private fun runAsync(action: () -> Unit): FutureTask<Unit> {
        val task = FutureTask<Unit> {
            action()
            Unit
        }
        Thread(task).start()
        return task
    }

    private fun workflow(
        logs: MutableList<String>,
        errors: MutableList<String>,
        attention: MutableList<Boolean>
    ): InstallReplacementStartupWorkflow {
        return InstallReplacementStartupWorkflow(
            appendLog = logs::add,
            appendError = errors::add,
            onInstallRecoveryAttention = { _, _, required -> attention.add(required) }
        )
    }

    private fun unresolvedResult() = InstallReplacementRecoveryResult(
        transactionFileName = "_dml_transaction_test.properties",
        action = null,
        failureMessage = "Install transaction requires manual recovery."
    )

    private fun recoveredResult() = InstallReplacementRecoveryResult(
        transactionFileName = "_dml_transaction_recovered.properties",
        action = InstallReplacementRecoveryAction.RESTORED_PREVIOUS_INSTALL,
        failureMessage = null
    )

    private class QueuedUiThread {
        private val actions = mutableListOf<() -> Unit>()

        fun post(action: () -> Unit) {
            synchronized(actions) {
                actions.add(action)
            }
        }

        fun runNext() {
            val action = synchronized(actions) {
                actions.removeAt(0)
            }
            action()
        }

        fun runLast() {
            val action = synchronized(actions) {
                actions.removeAt(actions.lastIndex)
            }
            action()
        }
    }

    private class BlockingEngine(
        private val results: List<InstallReplacementRecoveryResult>
    ) : InstallReplacementRecoveryEngine {
        private val recoveryStarted = CountDownLatch(1)
        private val releaseRecovery = CountDownLatch(1)

        override fun recoverInterruptedInstallReplacements():
                List<InstallReplacementRecoveryResult> {
            recoveryStarted.countDown()
            releaseRecovery.await()
            return results
        }

        fun awaitRecoveryStarted() {
            assertTrue(recoveryStarted.await(10, TimeUnit.SECONDS))
        }

        fun releaseRecovery() {
            releaseRecovery.countDown()
        }
    }

    private class FakeEngine(
        private val results: List<InstallReplacementRecoveryResult> = emptyList(),
        private val failure: Exception? = null
    ) : InstallReplacementRecoveryEngine {
        override fun recoverInterruptedInstallReplacements():
                List<InstallReplacementRecoveryResult> {
            failure?.let { throw it }
            return results
        }
    }
}
