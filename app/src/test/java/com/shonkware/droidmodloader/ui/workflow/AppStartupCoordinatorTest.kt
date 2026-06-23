package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStartupCoordinatorTest {
    @Test
    fun `startup work runs in required order and shares created runtime`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, runtime = "engine")

        coordinator.initialize()

        assertEquals(
            listOf(
                "background",
                "load-setup",
                "migrate-storage",
                "refresh-games",
                "load-config",
                "migrate-priority",
                "baseline",
                "create-runtime",
                "recovery:engine",
                "sync:engine",
                "log:UI ready."
            ),
            events
        )
    }

    @Test
    fun `missing runtime skips recovery but still synchronizes refresh state`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, runtime = null)

        coordinator.initialize()

        assertEquals(false, events.any { it.startsWith("recovery:") })
        assertEquals(true, events.contains("sync:null"))
    }

    private fun coordinator(
        events: MutableList<String>,
        runtime: String?
    ): AppStartupCoordinator<String> {
        return AppStartupCoordinator(
            runInBackground = { action ->
                events += "background"
                action()
            },
            loadSetupState = { events += "load-setup" },
            migrateLegacyProfileStorage = { events += "migrate-storage" },
            refreshGameOptions = { events += "refresh-games" },
            loadSelectedGameConfig = { events += "load-config" },
            migratePrioritySpacing = { events += "migrate-priority" },
            ensureDataBaseline = { events += "baseline" },
            createRuntime = {
                events += "create-runtime"
                runtime
            },
            checkRecovery = { value -> events += "recovery:$value" },
            synchronizePluginsAndRefresh = { value -> events += "sync:$value" },
            appendLog = { message -> events += "log:$message" }
        )
    }
}
