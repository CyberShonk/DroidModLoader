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

    @Test
    fun `recreation while startup is queued does not reload or recover twice`() {
        val events = mutableListOf<String>()
        var queued: (() -> Unit)? = null
        val coordinator = AppStartupCoordinator(
            runInBackground = { action ->
                check(queued == null)
                queued = action
            },
            loadSetupState = { events += "load" },
            migrateLegacyProfileStorage = {},
            refreshGameOptions = {},
            loadSelectedGameConfig = {},
            migratePrioritySpacing = {},
            ensureDataBaseline = {},
            createRuntime = { "engine" },
            checkRecovery = { events += "recover" },
            synchronizePluginsAndRefresh = { events += "refresh" },
            appendLog = { events += it }
        )

        coordinator.initialize { events += "restore" }
        coordinator.initialize { error("must not restore old saved state over the live session") }
        assertEquals(listOf("restore", "UI ready."), events)
        checkNotNull(queued).invoke()
        coordinator.initialize()
        assertEquals(listOf("restore", "UI ready.", "load", "recover", "refresh"), events)
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
