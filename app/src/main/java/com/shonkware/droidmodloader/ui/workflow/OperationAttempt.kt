package com.shonkware.droidmodloader.ui.workflow

import java.util.UUID

/** Captured intent, not evidence that these targets are valid or their content is verified. */
internal data class OperationContext(
    val profileId: String?,
    val gameId: String,
    val realDeployment: Boolean,
    val dataTarget: String,
    val rootTarget: String,
    val unavailable: Boolean = false
)

internal enum class OperationKind { ARCHIVE_INSTALL, DEPLOYMENT, FULL_REDEPLOY, PLUGIN_OUTPUT }
internal enum class OperationPhase { PREPARING, WAITING_FOR_CHOICES, INSTALLING, EXECUTING }
internal enum class OperationOutcome { SUCCEEDED, FAILED, CANCELLED }
internal enum class OperationTransition { STARTED, WAITING, RESUMED, FINISHED }

internal data class OperationAttemptRecord(
    val id: String,
    val previousAttemptId: String?,
    val kind: OperationKind,
    val context: OperationContext,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val finishedAtMillis: Long?,
    val phase: OperationPhase,
    val outcome: OperationOutcome?,
    val reason: String,
    val interrupted: Boolean = false
)

/** One logical attempt. Terminal callbacks are idempotent and cannot reopen it. */
internal class OperationAttempt private constructor(
    initial: OperationAttemptRecord,
    private val clock: () -> Long,
    private val onTransition: (OperationAttemptRecord, OperationTransition, Throwable?) -> Unit
) {
    private var current = initial

    @Synchronized
    fun snapshot(): OperationAttemptRecord = current

    @Synchronized
    fun waitForChoices(message: String): Boolean {
        if (current.kind != OperationKind.ARCHIVE_INSTALL || current.outcome != null || current.phase != OperationPhase.PREPARING) return false
        update(OperationPhase.WAITING_FOR_CHOICES, message, OperationTransition.WAITING)
        return true
    }

    @Synchronized
    fun startInstalling(message: String): Boolean {
        if (current.kind != OperationKind.ARCHIVE_INSTALL || current.outcome != null || current.phase == OperationPhase.INSTALLING) return false
        update(OperationPhase.INSTALLING, message, OperationTransition.RESUMED)
        return true
    }

    fun succeed(message: String) = finish(OperationOutcome.SUCCEEDED, message, null)
    fun cancel(message: String) = finish(OperationOutcome.CANCELLED, message, null)
    fun fail(message: String, failure: Throwable? = null) = finish(OperationOutcome.FAILED, message, failure)

    @Synchronized
    private fun finish(outcome: OperationOutcome, message: String, failure: Throwable?): Boolean {
        if (current.outcome != null) return false
        // Preparation or waiting is not successful installation. Failure/cancellation can close either.
        if (outcome == OperationOutcome.SUCCEEDED &&
            current.kind == OperationKind.ARCHIVE_INSTALL && current.phase != OperationPhase.INSTALLING) return false
        val now = maxOf(clock(), current.updatedAtMillis)
        current = current.copy(updatedAtMillis = now, finishedAtMillis = now, outcome = outcome, reason = message)
        onTransition(current, OperationTransition.FINISHED, failure)
        return true
    }

    private fun update(phase: OperationPhase, message: String, transition: OperationTransition) {
        current = current.copy(phase = phase, updatedAtMillis = maxOf(clock(), current.updatedAtMillis), reason = message)
        onTransition(current, transition, null)
    }

    companion object {
        fun start(
            context: OperationContext,
            message: String,
            previousAttemptId: String? = null,
            phase: OperationPhase = OperationPhase.PREPARING,
            kind: OperationKind = OperationKind.ARCHIVE_INSTALL,
            clock: () -> Long = System::currentTimeMillis,
            onTransition: (OperationAttemptRecord, OperationTransition, Throwable?) -> Unit
        ): OperationAttempt {
            require(phase != OperationPhase.WAITING_FOR_CHOICES)
            require((kind == OperationKind.ARCHIVE_INSTALL) == (phase != OperationPhase.EXECUTING))
            val now = clock()
            val record = OperationAttemptRecord(
                UUID.randomUUID().toString(), previousAttemptId, kind,
                context, now, now, null, phase, null, message
            )
            return OperationAttempt(record, clock, onTransition).also {
                onTransition(record, OperationTransition.STARTED, null)
            }
        }
    }
}
