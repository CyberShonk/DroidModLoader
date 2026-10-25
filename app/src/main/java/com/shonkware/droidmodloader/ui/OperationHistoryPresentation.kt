package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationHistorySnapshot
import com.shonkware.droidmodloader.ui.workflow.OperationKind
import com.shonkware.droidmodloader.ui.workflow.OperationOutcome
import com.shonkware.droidmodloader.ui.workflow.OperationPhase
import java.text.DateFormat
import java.util.Date

/** Text-only UI projection. No filesystem reads or recovery actions. */
data class OperationHistoryPresentation(
    val status: String = "Operation history has not been loaded.",
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
            OperationHistoryAvailability.AVAILABLE -> if (history.records.isEmpty()) "No recorded operations." else
                "Showing the latest ${minOf(5, history.records.size)} of ${history.records.size} retained attempts across profiles."
            OperationHistoryAvailability.NOT_SAVED -> "History changes are not saved. ${history.reason.orEmpty()}"
            OperationHistoryAvailability.UNAVAILABLE ->
                "Saved history is unavailable. Entries shown are last known or in memory, not a complete saved history. ${history.reason.orEmpty()}"
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
                OperationPhase.EXECUTING -> "Executing"
            }
            val outcome = when {
                record.interrupted -> "Interrupted: outcome unknown"
                record.outcome == OperationOutcome.SUCCEEDED -> "Succeeded"
                record.outcome == OperationOutcome.FAILED -> "Failed"
                record.outcome == OperationOutcome.CANCELLED -> "Cancelled"
                record.phase == OperationPhase.WAITING_FOR_CHOICES -> phase
                else -> "In progress: $phase"
            }
            val context = if (record.context.unavailable) "Captured context unavailable (exceeds history limit)." else
                "Profile: ${record.context.profileId ?: "Unassigned"} | Game: ${record.context.gameId}"
            RecentOperationPresentation(record.id, kind, outcome, context, record.reason, buildString {
                appendLine("Attempt: ${record.id}")
                appendLine("Reason: ${record.reason}")
                record.previousAttemptId?.let { appendLine("Previous attempt: $it") }
                appendLine("Last recorded phase: $phase")
                appendLine("Started (device time): ${format.format(Date(record.startedAtMillis))}")
                appendLine("Updated (device time): ${format.format(Date(record.updatedAtMillis))}")
                appendLine("Completed: ${record.finishedAtMillis?.let { format.format(Date(it)) } ?: "Not recorded"}")
                if (!record.context.unavailable) {
                    appendLine("Captured deployment intent: ${if (record.context.realDeployment) "Physical" else "Simulated"}")
                    appendLine("Configured Data: ${record.context.dataTarget.ifBlank { "Not selected" }}")
                    appendLine("Configured Game Root: ${record.context.rootTarget.ifBlank { "Not selected" }}")
                    append("Configured paths describe intent, not verified targets.")
                } else append("Profile, game and configured targets are unavailable.")
            })
        })
    }
}
