package com.shonkware.droidmodloader.ui.workflow

internal class OperationReporter(
    private val statusController: OperationStatusController = OperationStatusController(),
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val currentLogText: () -> String,
    private val updateLogText: (String) -> Unit,
    private val updateOperationInProgress: (Boolean) -> Unit,
    private val updateActiveOperationText: (String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val debugLog: (String) -> Unit,
    private val errorLog: (String, Throwable?) -> Unit,
    private val appendLogFile: (String) -> Unit,
    private val recordAttempt: (OperationAttemptRecord) -> Unit = {}
) {
    private var activeAttemptId: String? = null
    @Volatile private var presentationRevision = 0L

    fun beginArchiveInstall(context: OperationContext, text: String): OperationAttempt =
        OperationAttempt.start(context, text, onTransition = ::reportAttempt)

    fun continueArchiveInstall(
        previous: OperationAttempt?,
        context: OperationContext,
        text: String
    ): OperationAttempt {
        if (previous != null) {
            val record = previous.snapshot()
            if (record.outcome == null) {
                check(previous.startInstalling(text)) { "Installer attempt is already running" }
                return previous
            }
            return OperationAttempt.start(
                record.context, text, previousAttemptId = record.id,
                phase = OperationPhase.INSTALLING, onTransition = ::reportAttempt
            )
        }
        return OperationAttempt.start(
            context, text, phase = OperationPhase.INSTALLING, onTransition = ::reportAttempt
        )
    }

    @Synchronized
    private fun reportAttempt(record: OperationAttemptRecord, transition: OperationTransition, failure: Throwable?) {
        try {
            recordAttempt(record)
        } catch (failure: Exception) {
            appendError("Operation history is not saved; operation outcome is unchanged.", failure)
        }
        val running = transition == OperationTransition.STARTED || transition == OperationTransition.RESUMED
        if (running) activeAttemptId = record.id
        val ownsPresentation = activeAttemptId == record.id
        val elapsed = record.updatedAtMillis - record.startedAtMillis
        val status = if (record.outcome == null) record.reason else "${record.reason} ($elapsed ms elapsed)"
        if (ownsPresentation) {
            if (!running) activeAttemptId = null
            val revision = ++presentationRevision
            runOnUiThread {
                if (presentationRevision == revision) {
                    updateOperationInProgress(running)
                    updateActiveOperationText(if (running) record.reason else "")
                    updateLastOperationStatus(status)
                }
            }
        }
        val label = record.outcome?.name ?: transition.name
        val line = "OPERATION $label [${record.id}]: $status"
        if (record.outcome == OperationOutcome.FAILED) appendError(line, failure) else appendLog(line)
    }

    fun appendLog(message: String) {
        val line = OperationLogFormatter.formatLogLine(message)

        debugLog(line)
        appendLogFile(line)
        appendUiLine(line)
    }

    fun appendError(message: String, throwable: Throwable? = null) {
        val line = OperationLogFormatter.formatLogLine("ERROR: $message")

        errorLog(line, throwable)
        appendLogFile(
            if (throwable == null) {
                line
            } else {
                line + "\n" + throwable.stackTraceToString()
            }
        )
        appendUiLine(line)
    }

    @Synchronized
    fun beginOperation(text: String) {
        activeAttemptId = null
        presentationRevision++
        val status = statusController.begin(text)

        runOnUiThread {
            updateOperationInProgress(true)
            updateActiveOperationText(status.activeText)
            updateLastOperationStatus(status.statusText)
        }

        appendLog(status.logText)
    }

    fun finishOperation(successText: String) {
        val status = statusController.finish(successText)

        runOnUiThread {
            updateOperationInProgress(false)
            updateActiveOperationText("")
            updateLastOperationStatus(status.statusText)
        }

        appendLog(status.logText)
    }

    fun cancelOperation(message: String) {
        val status =
            statusController.cancel(message)

        runOnUiThread {
            updateOperationInProgress(false)
            updateActiveOperationText("")
            updateLastOperationStatus(
                status.statusText
            )
        }

        appendLog(status.logText)
    }

    fun failOperation(message: String, throwable: Throwable? = null) {
        val status = statusController.fail(message)

        runOnUiThread {
            updateOperationInProgress(false)
            updateActiveOperationText("")
            updateLastOperationStatus(status.statusText)
        }

        appendError(status.logText, throwable)
    }

    private fun appendUiLine(line: String) {
        runOnUiThread {
            val current = currentLogText()
            updateLogText(
                if (current.isBlank()) {
                    line
                } else {
                    current + "\n" + line
                }
            )
        }
    }
}
