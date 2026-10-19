package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenPluginCoordinatorTest {
    private fun coordinator(
        logs: MutableList<String>,
        statuses: MutableList<String>
    ) = SecondScreenPluginCoordinator(
        appendLog = { logs += it },
        updateLastOperationStatus = { statuses += it }
    )

    @Test
    fun `enable path sets enabled status and logs it`() {
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(logs, statuses)

        coordinator.toggle()

        assertTrue(coordinator.enabled)
        assertEquals("Second screen plugin display enabled.", statuses.single())
        assertEquals(listOf("Second screen plugin display enabled."), logs)
    }

    @Test
    fun `disable path sets disabled status and logs it`() {
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(logs, statuses)

        coordinator.toggle()
        coordinator.toggle()

        assertFalse(coordinator.enabled)
        assertEquals("Second screen plugin display disabled.", statuses.last())
        assertEquals(
            listOf("Second screen plugin display enabled.", "Second screen plugin display disabled."),
            logs
        )
        assertEquals(logs, statuses)
    }

    @Test
    fun `repeated observations do not change intent or emit duplicate feedback`() {
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(mutableListOf(), statuses)

        coordinator.toggle()
        repeat(3) { assertTrue(coordinator.enabled) }
        assertEquals(listOf("Second screen plugin display enabled."), statuses)

        coordinator.toggle()
        assertFalse(coordinator.enabled)
        assertEquals(
            listOf("Second screen plugin display enabled.", "Second screen plugin display disabled."),
            statuses
        )
    }
}
