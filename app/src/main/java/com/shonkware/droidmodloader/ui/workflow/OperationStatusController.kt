package com.shonkware.droidmodloader.ui.workflow

internal class OperationStatusController {

    private var startedAtMillis: Long = 0L

    fun begin(text: String): OperationStatusStart {
        startedAtMillis = System.currentTimeMillis()

        return OperationStatusStart(
            activeText = text,
            statusText = text,
            toastText = text,
            logText = "OPERATION START: $text"
        )
    }

    fun finish(successText: String): OperationStatusEnd {
        val durationText = OperationLogFormatter.formatOperationDuration(startedAtMillis)
        startedAtMillis = 0L

        val completedText = "$successText ($durationText)"

        return OperationStatusEnd(
            statusText = completedText,
            toastText = successText,
            logText = "OPERATION END: $completedText"
        )
    }

    fun fail(message: String): OperationStatusEnd {
        val durationText = OperationLogFormatter.formatOperationDuration(startedAtMillis)
        startedAtMillis = 0L

        val failedText = "$message ($durationText)"

        return OperationStatusEnd(
            statusText = failedText,
            toastText = message,
            logText = "OPERATION FAILED: $failedText"
        )
    }
}

internal data class OperationStatusStart(
    val activeText: String,
    val statusText: String,
    val toastText: String,
    val logText: String
)

internal data class OperationStatusEnd(
    val statusText: String,
    val toastText: String,
    val logText: String
)