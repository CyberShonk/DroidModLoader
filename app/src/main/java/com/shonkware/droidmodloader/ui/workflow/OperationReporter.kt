package com.shonkware.droidmodloader.ui.workflow

internal class OperationReporter(
    private val statusController: OperationStatusController = OperationStatusController(),
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val currentLogText: () -> String,
    private val updateLogText: (String) -> Unit,
    private val updateOperationInProgress: (Boolean) -> Unit,
    private val updateActiveOperationText: (String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val showToast: (String) -> Unit,
    private val debugLog: (String) -> Unit,
    private val errorLog: (String, Throwable?) -> Unit,
    private val appendLogFile: (String) -> Unit
) {

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

    fun beginOperation(text: String) {
        val status = statusController.begin(text)

        runOnUiThread {
            updateOperationInProgress(true)
            updateActiveOperationText(status.activeText)
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
        appendLog(status.logText)
    }

    fun finishOperation(successText: String) {
        val status = statusController.finish(successText)

        runOnUiThread {
            updateOperationInProgress(false)
            updateActiveOperationText("")
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
        appendLog(status.logText)
    }

    fun failOperation(message: String, throwable: Throwable? = null) {
        val status = statusController.fail(message)

        runOnUiThread {
            updateOperationInProgress(false)
            updateActiveOperationText("")
            updateLastOperationStatus(status.statusText)
        }

        showToast(status.toastText)
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
