package com.shonkware.droidmodloader.ui.workflow

import java.util.concurrent.CountDownLatch

internal class ActivityThreadRunner(
    private val isOnUiThread: () -> Boolean,
    private val postToUiThread: (() -> Unit) -> Unit
) {
    fun runInBackground(action: () -> Unit) {
        Thread(action).start()
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
