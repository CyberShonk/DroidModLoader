package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OperationAttemptTest {
    private val context = OperationContext("profile-a", "skyrim", true, "/a/Data", "/a")

    @Test
    fun waitingIsNotSuccessAndInstallingRetainsIdentityContextAndStartTime() {
        var now = 100L
        val records = mutableListOf<OperationAttemptRecord>()
        val attempt = OperationAttempt.start(context, "Import", clock = { now }) { record, _, _ -> records += record }
        val original = attempt.snapshot()
        assertFalse(attempt.succeed("Only prepared"))
        now = 200L
        assertTrue(attempt.waitForChoices("Choose"))
        assertFalse(attempt.waitForChoices("Duplicate"))
        assertFalse(attempt.succeed("Not installed"))
        assertNull(attempt.snapshot().finishedAtMillis)
        assertNull(attempt.snapshot().outcome)

        now = 300L
        assertTrue(attempt.startInstalling("Install"))
        now = 400L
        assertTrue(attempt.succeed("Installed"))
        assertFalse(attempt.fail("Late failure"))
        assertFalse(attempt.cancel("Late cancel"))
        assertFalse(attempt.startInstalling("Late restart"))

        assertEquals(4, records.size)
        assertTrue(records.all { it.id == original.id && it.context == context && it.startedAtMillis == 100L })
        assertEquals(OperationOutcome.SUCCEEDED, attempt.snapshot().outcome)
        assertEquals(400L, attempt.snapshot().finishedAtMillis)
        assertEquals("Installed", attempt.snapshot().reason)
    }

    @Test
    fun terminalTimeDoesNotMoveBackwardsWhenWallClockChanges() {
        var now = 100L
        val attempt = OperationAttempt.start(context, "Import", clock = { now }) { _, _, _ -> }
        now = 50L
        attempt.fail("Failed")
        assertEquals(100L, attempt.snapshot().finishedAtMillis)
    }

    @Test
    fun waitingCanBeCancelledOrFailedButCannotBeReopened() {
        for (outcome in listOf(OperationOutcome.CANCELLED, OperationOutcome.FAILED)) {
            val attempt = OperationAttempt.start(context, "Import") { _, _, _ -> }
            attempt.waitForChoices("Choose")
            if (outcome == OperationOutcome.CANCELLED) attempt.cancel("Cancelled") else attempt.fail("Failed")
            assertEquals(outcome, attempt.snapshot().outcome)
            assertFalse(attempt.waitForChoices("Choose again"))
            assertFalse(attempt.startInstalling("Install again"))
        }
    }

    @Test
    fun simultaneousDuplicateTerminalCallbacksEmitOneOutcome() {
        val outcomes = mutableListOf<OperationOutcome>()
        val attempt = OperationAttempt.start(context, "Import") { record, _, _ -> record.outcome?.let { outcomes += it } }
        attempt.startInstalling("Installing")
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val callbacks = listOf<() -> Unit>({ attempt.succeed("Done") }, { attempt.cancel("Cancelled") })
        callbacks.forEach { callback ->
            Thread {
                ready.countDown()
                try {
                    if (release.await(5, TimeUnit.SECONDS)) callback()
                } finally { done.countDown() }
            }.start()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, outcomes.size)
        assertEquals(outcomes.single(), attempt.snapshot().outcome)
    }
}
