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

    @Test
    fun `choice handoff is waiting not a terminal success and resumes the same attempt`() {
        val state = FakeState()
        val reporter = createReporter(state)
        val context = OperationContext("profile-a", "skyrim", true, "/a/Data", "/a")
        val attempt = reporter.beginArchiveInstall(context, "Importing...")
        val original = attempt.snapshot()
        attempt.waitForChoices("Choose installer options.")

        assertFalse(state.operationInProgress)
        assertEquals("Choose installer options.", state.lastOperationStatus)
        assertTrue(state.logText.contains("OPERATION WAITING [${original.id}]"))
        assertFalse(state.logText.contains("OPERATION SUCCEEDED"))

        reporter.beginOperation("Checking unrelated evidence...")
        reporter.finishOperation("Check complete.")
        val resumed = reporter.continueArchiveInstall(
            attempt, context.copy(profileId = "not-the-original"), "Installing..."
        )
        assertEquals(attempt, resumed)
        assertTrue(state.operationInProgress)
        resumed.succeed("Installed.")
        assertFalse(state.operationInProgress)
        assertEquals(original.id, resumed.snapshot().id)
        assertEquals(original.startedAtMillis, resumed.snapshot().startedAtMillis)
        assertEquals(context, resumed.snapshot().context)
        assertEquals(1, state.fileLines.count { it.contains("OPERATION SUCCEEDED") })
        assertFalse(attempt.cancel("Duplicate callback"))
        assertEquals(1, state.fileLines.count { it.contains("OPERATION SUCCEEDED") })
    }

    @Test
    fun `retry is linked to a failed attempt and late callbacks cannot finish newer work`() {
        val state = FakeState()
        val reporter = createReporter(state)
        val context = OperationContext("profile-a", "skyrim", true, "/a/Data", "/a")
        val original = reporter.beginArchiveInstall(context, "Importing...")
        original.waitForChoices("Choose")
        reporter.continueArchiveInstall(original, context, "Installing...").fail("Failed")
        val retry = reporter.continueArchiveInstall(original, context.copy(profileId = "b"), "Retrying...")
        assertTrue(retry.snapshot().id != original.snapshot().id)
        assertEquals(original.snapshot().id, retry.snapshot().previousAttemptId)
        assertEquals(context, retry.snapshot().context)
        assertFalse(original.succeed("Stale success"))
        assertTrue(state.operationInProgress)
        assertEquals("Retrying...", state.activeOperationText)
        retry.succeed("Installed")
        assertEquals(OperationOutcome.FAILED, original.snapshot().outcome)
        assertEquals(OperationOutcome.SUCCEEDED, retry.snapshot().outcome)
    }

    @Test
    fun `late waiting failure cannot overwrite an unrelated operation presentation`() {
        val state = FakeState()
        val reporter = createReporter(state)
        val context = OperationContext(null, "skyrim", false, "", "")
        val attempt = reporter.beginArchiveInstall(context, "Importing...")
        attempt.waitForChoices("Choose")
        reporter.beginOperation("Other work")
        attempt.fail("Late failure")
        assertTrue(state.operationInProgress)
        assertEquals("Other work", state.activeOperationText)
        assertEquals("Other work", state.lastOperationStatus)
        assertEquals(OperationOutcome.FAILED, attempt.snapshot().outcome)
    }

    @Test
    fun `queued attempt publications cannot overwrite newer status`() {
        val state = FakeState()
        val queued = mutableListOf<() -> Unit>()
        val reporter = createReporter(state, queued::add)
        val attempt = reporter.beginArchiveInstall(OperationContext(null, "skyrim", false, "", ""), "Importing")
        attempt.waitForChoices("Choose")
        reporter.beginOperation("New operation")
        queued.toList().forEach { it() }
        assertTrue(state.operationInProgress)
        assertEquals("New operation", state.lastOperationStatus)
    }

    @Test
    fun `history callback failure does not change the install outcome`() {
        val state = FakeState()
        val reporter = createReporter(state, recordAttempt = { throw java.io.IOException("history denied") })
        val attempt = reporter.beginArchiveInstall(OperationContext(null, "skyrim", false, "", ""), "Importing")
        attempt.startInstalling("Installing")
        attempt.succeed("Installed")
        assertEquals(OperationOutcome.SUCCEEDED, attempt.snapshot().outcome)
        assertFalse(state.operationInProgress)
        assertTrue(state.lastOperationStatus.startsWith("Installed"))
        assertTrue(state.logText.contains("Operation history is not saved"))
        assertFalse(state.logText.contains("OPERATION FAILED"))
    }

    private fun createReporter(
        state: FakeState,
        runOnUiThread: (() -> Unit) -> Unit = { it() },
        recordAttempt: (OperationAttemptRecord) -> Unit = {}
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
            appendLogFile = { state.fileLines += it },
            recordAttempt = recordAttempt
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
