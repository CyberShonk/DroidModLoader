package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationReporterTest {

    @Test
    fun `begin and finish update operation state and log`() {
        val state = FakeState()
        val reporter = createReporter(state)

        reporter.beginOperation("Deploying...")

        assertTrue(state.operationInProgress)
        assertEquals("Deploying...", state.activeOperationText)
        assertEquals("Deploying...", state.lastOperationStatus)
        assertTrue(state.logText.contains("OPERATION START: Deploying..."))

        reporter.finishOperation("Deploy complete.")

        assertFalse(state.operationInProgress)
        assertEquals("", state.activeOperationText)
        assertTrue(
            state.lastOperationStatus.matches(
                Regex("""Deploy complete\. \(\d+ ms\)""")
            )
        )
        assertTrue(state.logText.contains("OPERATION END: Deploy complete."))

        val completedStatus = state.lastOperationStatus
        reporter.appendLog("Dashboard refreshed.")

        assertEquals(completedStatus, state.lastOperationStatus)
        assertTrue(state.logText.contains("OPERATION END: $completedStatus"))
        assertTrue(state.fileLines.any { it.contains("OPERATION END: $completedStatus") })
    }

    @Test
    fun `failure records error without leaving operation active`() {
        val state = FakeState()
        val reporter = createReporter(state)
        val failure = IllegalStateException("broken")

        reporter.beginOperation("Testing...")
        reporter.failOperation("Testing failed.", failure)

        assertFalse(state.operationInProgress)
        assertEquals("", state.activeOperationText)
        assertTrue(
            state.lastOperationStatus.matches(
                Regex("""Testing failed\. \(\d+ ms\)""")
            )
        )
        assertTrue(state.logText.contains("ERROR: OPERATION FAILED: Testing failed."))
        assertEquals(failure, state.errorThrowable)
        assertTrue(state.errorLines.single().contains("OPERATION FAILED: ${state.lastOperationStatus}"))
        assertTrue(state.fileLines.last().contains("IllegalStateException: broken"))
    }

    @Test
    fun `cancellation ends operation without recording error`() {
        val state = FakeState()
        val reporter = createReporter(state)

        reporter.beginOperation(
            "Installing..."
        )
        reporter.cancelOperation(
            "Installer cancelled."
        )

        assertFalse(state.operationInProgress)
        assertEquals(
            "",
            state.activeOperationText
        )
        assertTrue(
            state.lastOperationStatus.matches(
                Regex(
                    """Installer cancelled\. \(\d+ ms\)"""
                )
            )
        )
        assertTrue(
            state.logText.contains(
                "OPERATION CANCELLED: " +
                        "Installer cancelled."
            )
        )
        assertTrue(state.fileLines.last().contains("OPERATION CANCELLED: ${state.lastOperationStatus}"))
        assertTrue(state.errorLines.isEmpty())
        assertEquals(
            null,
            state.errorThrowable
        )
    }

    @Test
    fun `busy state is a delayed UI projection not synchronous admission`() {
        val state = FakeState()
        val publications = mutableListOf<() -> Unit>()
        val reporter = createReporter(state, publications::add)

        reporter.beginOperation("Deploying...")

        assertFalse(state.operationInProgress)
        assertTrue(state.fileLines.single().contains("OPERATION START: Deploying..."))

        publications.toList().forEach { it() }

        assertTrue(state.operationInProgress)
        assertEquals("Deploying...", state.activeOperationText)
    }

    private fun createReporter(
        state: FakeState,
        runOnUiThread: (() -> Unit) -> Unit = { it() }
    ): OperationReporter {
        return OperationReporter(
            runOnUiThread = runOnUiThread,
            currentLogText = { state.logText },
            updateLogText = { state.logText = it },
            updateOperationInProgress = { state.operationInProgress = it },
            updateActiveOperationText = { state.activeOperationText = it },
            updateLastOperationStatus = { state.lastOperationStatus = it },
            debugLog = { state.debugLines += it },
            errorLog = { line, throwable ->
                state.errorLines += line
                state.errorThrowable = throwable
            },
            appendLogFile = { state.fileLines += it }
        )
    }

    private class FakeState {
        var logText = ""
        var operationInProgress = false
        var activeOperationText = ""
        var lastOperationStatus = "Ready."
        val debugLines = mutableListOf<String>()
        val errorLines = mutableListOf<String>()
        val fileLines = mutableListOf<String>()
        var errorThrowable: Throwable? = null
    }
}
