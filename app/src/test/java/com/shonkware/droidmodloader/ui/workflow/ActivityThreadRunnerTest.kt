package com.shonkware.droidmodloader.ui.workflow

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `exclusive admission happens before scheduling and rejects all competing work`() {
        val scheduled = mutableListOf<() -> Unit>()
        val runner = ActivityThreadRunner({ true }, { it() }, scheduled::add)
        var writes = 0
        val deploy = { writes++; Unit }
        val changeProfile = { writes += 10; Unit }

        assertTrue(runner.runExclusiveInBackground(deploy))
        assertTrue(runner.isOperationAdmitted)
        assertFalse(runner.runExclusiveInBackground(deploy))
        assertFalse(runner.runExclusiveInBackground(changeProfile))
        assertEquals(1, scheduled.size)
        assertEquals(0, writes)

        scheduled.removeAt(0)()

        assertEquals(1, writes)
        assertFalse(runner.isOperationAdmitted)
        assertTrue(runner.runExclusiveInBackground(changeProfile))
        scheduled.removeAt(0)()
        assertEquals(11, writes)
    }

    @Test
    fun `simultaneous exclusive requests schedule only one worker`() {
        val scheduled = AtomicInteger()
        val admitted = AtomicInteger()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val runner = ActivityThreadRunner({ true }, { it() }, { scheduled.incrementAndGet() })
        val callers = List(2) {
            Thread {
                try {
                    ready.countDown()
                    start.await()
                    if (runner.runExclusiveInBackground { error("Must not run yet") }) {
                        admitted.incrementAndGet()
                    }
                } finally {
                    finished.countDown()
                }
            }.apply { start() }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, scheduled.get())
            assertEquals(1, admitted.get())
        } finally {
            start.countDown()
            callers.forEach { it.join(5000) }
        }
    }

    @Test
    fun `exclusive ownership is released on failure and cancellation`() {
        val scheduled = mutableListOf<() -> Unit>()
        val runner = ActivityThreadRunner({ true }, { it() }, scheduled::add)

        for (failure in listOf(IllegalStateException("broken"), CancellationException("cancelled"))) {
            assertTrue(runner.runExclusiveInBackground { throw failure })
            assertEquals(failure, assertThrows(failure.javaClass) { scheduled.removeAt(0)() })
            assertFalse(runner.isOperationAdmitted)
        }

        assertTrue(runner.runExclusiveInBackground {})
        scheduled.removeAt(0)()
        assertFalse(runner.isOperationAdmitted)
    }

    @Test
    fun `failed admission precondition does not schedule work or keep ownership`() {
        val scheduled = mutableListOf<() -> Unit>()
        val runner = ActivityThreadRunner({ true }, { it() }, scheduled::add)

        assertFalse(runner.runExclusiveInBackground(beforeSchedule = { false }) {
            error("Must not execute")
        })
        assertTrue(scheduled.isEmpty())
        assertFalse(runner.isOperationAdmitted)
        assertTrue(runner.runExclusiveInBackground {})
        scheduled.removeAt(0)()
    }

    @Test
    fun `admission remains exclusive while synchronous context publication is delayed`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val scheduled = AtomicInteger()
        val accepted = AtomicBoolean(false)
        val runner = ActivityThreadRunner({ true }, { it() }, { scheduled.incrementAndGet() })
        val requester = Thread {
            try {
                accepted.set(runner.runExclusiveInBackground(beforeSchedule = {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    true
                }) {})
            } finally {
                finished.countDown()
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(0, scheduled.get())
            assertFalse(runner.runExclusiveInBackground { error("Conflicting work must not run") })
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(accepted.get())
            assertEquals(1, scheduled.get())
        } finally {
            release.countDown()
            requester.join(5000)
        }
    }

    @Test
    fun `completion notification runs only after ownership is released`() {
        val scheduled = mutableListOf<() -> Unit>()
        val runner = ActivityThreadRunner({ true }, { it() }, scheduled::add)
        var notified = false

        assertTrue(runner.runExclusiveInBackground(
            beforeSchedule = { true },
            onFinished = {
                assertFalse(runner.isOperationAdmitted)
                notified = true
            }
        ) {})
        assertFalse(notified)
        scheduled.removeAt(0)()
        assertTrue(notified)
    }

    @Test
    fun `failed scheduling releases exclusive admission`() {
        val failure = IllegalStateException("thread start failed")
        val runner = ActivityThreadRunner({ true }, { it() }, { throw failure })

        assertEquals(failure, assertThrows(IllegalStateException::class.java) {
            runner.runExclusiveInBackground { error("Must not execute") }
        })
        assertFalse(runner.isOperationAdmitted)
    }

    @Test
    fun `background scheduling alone does not serialize work`() {
        val runner = ActivityThreadRunner(
            isOnUiThread = { false },
            postToUiThread = { it() }
        )
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)

        try {
            repeat(2) {
                runner.runInBackground {
                    try {
                        entered.countDown()
                        release.await()
                    } finally {
                        finished.countDown()
                    }
                }
            }

            assertTrue("Both workers must enter before either is released", entered.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            assertTrue("Workers must terminate", finished.await(5, TimeUnit.SECONDS))
        }
    }
}
