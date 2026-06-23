package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityThreadRunnerTest {

    @Test
    fun `blocking action runs immediately on ui thread`() {
        var calls = 0
        val runner = ActivityThreadRunner(
            isOnUiThread = { true },
            postToUiThread = { error("should not post") }
        )

        runner.runOnUiThreadBlocking { calls++ }

        assertEquals(1, calls)
    }

    @Test
    fun `blocking action posts and waits off ui thread`() {
        var calls = 0
        val runner = ActivityThreadRunner(
            isOnUiThread = { false },
            postToUiThread = { action -> action() }
        )

        runner.runOnUiThreadBlocking { calls++ }

        assertEquals(1, calls)
    }
}
