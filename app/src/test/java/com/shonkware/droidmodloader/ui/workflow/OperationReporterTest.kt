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
        assertEquals(listOf("Deploying..."), state.toasts)
        assertTrue(state.logText.contains("OPERATION START: Deploying..."))

        reporter.finishOperation("Deploy complete.")

        assertFalse(state.operationInProgress)
        assertEquals("", state.activeOperationText)
        assertTrue(
            state.lastOperationStatus.matches(
                Regex("""Deploy complete\. \(\d+ ms\)""")
            )
        )
        assertEquals(listOf("Deploying...", "Deploy complete."), state.toasts)
        assertTrue(state.logText.contains("OPERATION END: Deploy complete."))
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
        assertTrue(state.fileLines.last().contains("IllegalStateException: broken"))
    }

    private fun createReporter(state: FakeState): OperationReporter {
        return OperationReporter(
            runOnUiThread = { action -> action() },
            currentLogText = { state.logText },
            updateLogText = { state.logText = it },
            updateOperationInProgress = { state.operationInProgress = it },
            updateActiveOperationText = { state.activeOperationText = it },
            updateLastOperationStatus = { state.lastOperationStatus = it },
            showToast = { state.toasts += it },
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
        val toasts = mutableListOf<String>()
        val debugLines = mutableListOf<String>()
        val errorLines = mutableListOf<String>()
        val fileLines = mutableListOf<String>()
        var errorThrowable: Throwable? = null
    }
}
