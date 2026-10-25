package com.shonkware.droidmodloader.ui.workflow

/** Event recorder for workflow tests; the lifecycle is the production implementation. */
internal class RecordingOperationAttempts {
    val context = OperationContext("profile-a", "skyrim", true, "/game/Data", "/game")
    val attempts = mutableListOf<OperationAttempt>()
    val records = mutableListOf<OperationAttemptRecord>()
    val beginMessages = mutableListOf<String>()
    val waitingMessages = mutableListOf<String>()
    val finishMessages = mutableListOf<String>()
    val cancelMessages = mutableListOf<String>()
    val failures = mutableListOf<Pair<String, Throwable?>>()

    fun start(message: String): OperationAttempt = create(message, null, OperationPhase.PREPARING)

    fun continueInstall(previous: OperationAttempt?, message: String): OperationAttempt {
        if (previous != null && previous.snapshot().outcome == null) {
            check(previous.startInstalling(message))
            return previous
        }
        return create(message, previous, OperationPhase.INSTALLING)
    }

    private fun create(message: String, previous: OperationAttempt?, phase: OperationPhase): OperationAttempt =
        OperationAttempt.start(
            previous?.snapshot()?.context ?: context, message,
            previousAttemptId = previous?.snapshot()?.id, phase = phase
        ) { record, transition, failure ->
            records += record
            when (transition) {
                OperationTransition.STARTED, OperationTransition.RESUMED -> beginMessages += record.reason
                OperationTransition.WAITING -> waitingMessages += record.reason
                OperationTransition.FINISHED -> when (record.outcome) {
                    OperationOutcome.SUCCEEDED -> finishMessages += record.reason
                    OperationOutcome.CANCELLED -> cancelMessages += record.reason
                    OperationOutcome.FAILED -> failures += record.reason to failure
                    null -> error("Terminal event without outcome")
                }
            }
        }.also { attempts += it }
}
