package com.shonkware.droidmodloader.ui.workflow

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class ActivityThreadRunner(
    private val isOnUiThread: () -> Boolean,
    private val postToUiThread: (() -> Unit) -> Unit,
    private val startBackgroundThread: (() -> Unit) -> Unit = { Thread(it).start() }
) {
    private val operationAdmitted = AtomicBoolean(false)

    val isOperationAdmitted: Boolean
        get() = operationAdmitted.get()

    fun runInBackground(action: () -> Unit) {
        startBackgroundThread(action)
    }

    /** Admission belongs to the retained session, not its asynchronously published UI state. */
    fun runExclusiveInBackground(action: () -> Unit): Boolean =
        runExclusiveInBackground(beforeSchedule = { true }, action = action)

    fun runExclusiveInBackground(
        beforeSchedule: () -> Boolean,
        onFinished: () -> Unit = {},
        action: () -> Unit
    ): Boolean {
        if (!operationAdmitted.compareAndSet(false, true)) return false

        try {
            if (!beforeSchedule()) {
                operationAdmitted.set(false)
                return false
            }
            runInBackground {
                try {
                    action()
                } finally {
                    operationAdmitted.set(false)
                    onFinished()
                }
            }
        } catch (failure: Throwable) {
            operationAdmitted.set(false)
            throw failure
        }
        return true
    }

    fun runOnUiThreadBlocking(action: () -> Unit) {
        if (isOnUiThread()) {
            action()
            return
        }

        val latch = CountDownLatch(1)
        postToUiThread {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }
}
