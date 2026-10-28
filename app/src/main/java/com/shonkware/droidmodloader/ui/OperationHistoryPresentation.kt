package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationHistorySnapshot
import com.shonkware.droidmodloader.ui.workflow.OperationKind
import com.shonkware.droidmodloader.ui.workflow.OperationOutcome
import com.shonkware.droidmodloader.ui.workflow.OperationPhase
import com.shonkware.droidmodloader.ui.workflow.GameCatalog
import java.text.DateFormat
import java.util.Date

/** Text-only UI projection. No filesystem reads or recovery actions. */
data class OperationHistoryPresentation(
    val status: String = "Recent activity has not been loaded.",
    val entries: List<RecentOperationPresentation> = emptyList()
)

data class RecentOperationPresentation(
    val id: String,
    val title: String,
    val outcome: String,
    val context: String,
    val reason: String,
    val details: String
)

internal object OperationHistoryPresentationMapper {
    fun present(history: OperationHistorySnapshot): OperationHistoryPresentation {
        val status = when (history.availability) {
            OperationHistoryAvailability.AVAILABLE -> if (history.records.isEmpty()) "No activity recorded yet." else
                "Showing the latest ${minOf(5, history.records.size)} of ${history.records.size} recorded attempts across profiles."
            OperationHistoryAvailability.NOT_SAVED -> "History changes are not saved. ${history.reason.orEmpty()}"
            OperationHistoryAvailability.UNAVAILABLE ->
                "Saved history could not be loaded. Entries shown may be from this session or an earlier view; the list may be incomplete. ${history.reason.orEmpty()}"
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        return OperationHistoryPresentation(status, history.records.takeLast(5).reversed().map { record ->
            val kind = when (record.kind) {
                OperationKind.ARCHIVE_INSTALL -> "Archive install"
                OperationKind.DEPLOYMENT -> "Deploy"
                OperationKind.FULL_REDEPLOY -> "Full redeploy"
                OperationKind.PLUGIN_OUTPUT -> "Apply plugins"
            }
            val phase = when (record.phase) {
                OperationPhase.PREPARING -> "Preparing archive"
                OperationPhase.WAITING_FOR_CHOICES -> "Waiting for installer choices"
                OperationPhase.INSTALLING -> "Installing archive"
                OperationPhase.EXECUTING -> "Working"
            }
            val outcome = when {
                record.interrupted -> "Interrupted: outcome unknown"
                record.outcome == OperationOutcome.SUCCEEDED -> "Succeeded"
                record.outcome == OperationOutcome.FAILED -> "Failed"
                record.outcome == OperationOutcome.CANCELLED -> "Cancelled"
                record.phase == OperationPhase.WAITING_FOR_CHOICES -> phase
                else -> "In progress: $phase"
            }
            val context = if (record.context.unavailable) "Profile and folder details were too large to save." else
                "Profile: ${record.context.profileId ?: "Unassigned"} | Game: ${GameCatalog.displayName(record.context.gameId)}"
            RecentOperationPresentation(record.id, kind, outcome, context, record.reason, buildString {
                appendLine("Attempt: ${record.id}")
                appendLine("Reason: ${record.reason}")
                record.previousAttemptId?.let { appendLine("Previous attempt: $it") }
                appendLine("Last recorded phase: $phase")
                appendLine("Started (device time): ${format.format(Date(record.startedAtMillis))}")
                appendLine("Updated (device time): ${format.format(Date(record.updatedAtMillis))}")
                appendLine("Completed: ${record.finishedAtMillis?.let { format.format(Date(it)) } ?: "Not recorded"}")
                if (!record.context.unavailable) {
                    appendLine("Deployment mode: ${if (record.context.realDeployment) "Game folders" else "Test folders"}")
                    appendLine("Saved Data folder: ${record.context.dataTarget.ifBlank { "Not selected" }}")
                    appendLine("Saved Game Root folder: ${record.context.rootTarget.ifBlank { "Not selected" }}")
                    append("These are the saved folder choices, not a fresh check of folder access.")
                } else append("Profile, game and configured targets are unavailable.")
            })
        })
    }
}
