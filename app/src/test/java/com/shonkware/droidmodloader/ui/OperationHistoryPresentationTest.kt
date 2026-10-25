package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.ui.workflow.OperationAttempt
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationHistorySnapshot
import com.shonkware.droidmodloader.ui.workflow.OperationKind
import com.shonkware.droidmodloader.ui.workflow.OperationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationHistoryPresentationTest {
    private val context = OperationContext("profile-a", "skyrim_le", true, "/a/Data", "/a")
    private fun attempt() = OperationAttempt.start(context, "Importing", clock = { 1000L }) { _, _, _ -> }

    @Test fun notLoadedAndEmptyAreDifferentAndNeitherClaimsHealth() {
        assertEquals("Operation history has not been loaded.", OperationHistoryPresentation().status)
        val empty = OperationHistoryPresentationMapper.present(OperationHistorySnapshot())
        assertEquals("No recorded operations.", empty.status)
        assertTrue(empty.entries.isEmpty())
        assertFalse(empty.status.contains("healthy", ignoreCase = true))
    }

    @Test fun waitingAndInterruptedRemainDistinctFromTerminalCancellationOrSuccess() {
        val waiting = attempt().apply { waitForChoices("Choose options") }.snapshot()
        val live = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(listOf(waiting))).entries.single()
        assertEquals("Waiting for installer choices", live.outcome)
        assertTrue(live.details.contains("Completed: Not recorded"))
        val interrupted = OperationHistoryPresentationMapper.present(
            OperationHistorySnapshot(listOf(waiting.copy(interrupted = true)))
        ).entries.single()
        assertEquals("Interrupted: outcome unknown", interrupted.outcome)
        assertTrue(interrupted.details.contains("Last recorded phase: Waiting for installer choices"))
        assertFalse(interrupted.details.contains("Cancelled"))
        assertFalse(interrupted.details.contains("Succeeded"))
        val cancelled = attempt().apply { cancel("Cancelled by user") }.snapshot()
        assertEquals("Cancelled", OperationHistoryPresentationMapper.present(
            OperationHistorySnapshot(listOf(cancelled))).entries.single().outcome)
    }

    @Test fun entriesKeepCapturedProfileTargetIntentTimesAndRetryLink() {
        val record = attempt().apply { startInstalling("Installing"); succeed("Installed") }.snapshot()
            .copy(previousAttemptId = "previous-attempt")
        val entry = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(listOf(record))).entries.single()
        assertEquals("Succeeded", entry.outcome)
        assertTrue(entry.context.contains("profile-a"))
        assertTrue(entry.context.contains("skyrim_le"))
        assertTrue(entry.details.contains("Attempt: ${record.id}"))
        assertTrue(entry.details.contains("Previous attempt: previous-attempt"))
        assertTrue(entry.details.contains("Configured Data: /a/Data"))
        assertTrue(entry.details.contains("Configured Game Root: /a"))
        assertTrue(entry.details.contains("Started (device time):"))
        assertTrue(entry.details.contains("not verified targets"))
    }

    @Test fun unsavedAndUnavailableHistoryNeverMasqueradeAsCompleteSavedResults() {
        val record = attempt().apply { fail("Read failed") }.snapshot()
        for (availability in listOf(OperationHistoryAvailability.NOT_SAVED, OperationHistoryAvailability.UNAVAILABLE)) {
            val view = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(listOf(record), availability, "Denied"))
            assertTrue(view.status.contains("Denied"))
            assertEquals("Failed", view.entries.single().outcome)
            assertFalse(view.status.contains("Showing the latest"))
        }
    }

    @Test fun latestFiveAcrossProfilesAreBoundedAndFullDetailsRemainAvailable() {
        val records = List(8) { index ->
            attempt().snapshot().copy(context = context.copy(profileId = "profile-$index"), reason = "r".repeat(512))
        }
        val view = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(records))
        assertEquals(5, view.entries.size)
        assertEquals(records.last().id, view.entries.first().id)
        assertTrue(view.entries.first().context.contains("profile-7"))
        assertTrue(view.entries.first().details.contains("r".repeat(512)))
        assertTrue(view.status.contains("5 of 8"))
        val unavailable = records.first().copy(context = OperationContext(null, "", false, "", "", unavailable = true))
        val entry = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(listOf(unavailable))).entries.single()
        assertTrue(entry.context.contains("unavailable"))
        assertFalse(entry.details.contains("Configured Data:"))
    }

    @Test fun allRecordedKindsHaveExplicitLabelsWithoutInstallerChoicesForOtherKinds() {
        for ((kind, label) in listOf(OperationKind.DEPLOYMENT to "Deploy", OperationKind.FULL_REDEPLOY to "Full redeploy",
            OperationKind.PLUGIN_OUTPUT to "Apply plugins")) {
            val attempt = OperationAttempt.start(context, "Running", kind = kind, phase = OperationPhase.EXECUTING) { _, _, _ -> }
            assertFalse(attempt.waitForChoices("Not an installer"))
            assertFalse(attempt.startInstalling("Not an installer"))
            attempt.succeed("Done")
            val entry = OperationHistoryPresentationMapper.present(OperationHistorySnapshot(listOf(attempt.snapshot()))).entries.single()
            assertEquals(label, entry.title)
            assertEquals("Succeeded", entry.outcome)
        }
    }
}
