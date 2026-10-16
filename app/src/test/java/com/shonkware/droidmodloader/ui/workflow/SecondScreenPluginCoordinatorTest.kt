package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.ui.SecondScreenController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenPluginCoordinatorTest {

    private fun coordinator(
        logs: MutableList<String>,
        statuses: MutableList<String>,
        controllerProvider: () -> SecondScreenController?
    ): SecondScreenPluginCoordinator {
        return SecondScreenPluginCoordinator(
            controllerProvider = controllerProvider,
            pluginsProvider = { emptyList() },
            activeProfileNameProvider = { "profile" },
            appendLog = { logs += it },
            updateLastOperationStatus = { statuses += it }
        )
    }

    @Test
    fun `enable path sets enabled status and logs it`() {
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(logs, statuses, { null })

        coordinator.toggle()

        assertTrue(coordinator.enabled)
        assertEquals(
            "Second screen plugin display enabled.",
            statuses.single()
        )
        assertEquals(
            listOf("Second screen plugin display enabled."),
            logs
        )
    }

    @Test
    fun `disable path sets disabled status and logs it`() {
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(logs, statuses, { null })

        coordinator.toggle()
        coordinator.toggle()

        assertFalse(coordinator.enabled)
        assertEquals(
            "Second screen plugin display disabled.",
            statuses.last()
        )
        assertEquals(
            listOf(
                "Second screen plugin display enabled.",
                "Second screen plugin display disabled."
            ),
            logs
        )
    }

    @Test
    fun `null controller provider still flips status without throwing`() {
        val statuses = mutableListOf<String>()
        val coordinator = coordinator(mutableListOf(), statuses, { null })

        coordinator.toggle()
        coordinator.onResume()
        coordinator.refresh()
        coordinator.onPause()
        assertTrue(coordinator.enabled)
        assertEquals(listOf("Second screen plugin display enabled."), statuses)

        coordinator.toggle()
        assertFalse(coordinator.enabled)

        assertEquals(
            listOf(
                "Second screen plugin display enabled.",
                "Second screen plugin display disabled."
            ),
            statuses
        )
    }
}
