package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.Mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperDiagnosticsCoordinatorTest {

    @Test
    fun `deployment plan summary reports pass and refreshes`() {
        val log = mutableListOf<String>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        var refreshCount = 0
        val coordinator = DeveloperDiagnosticsCoordinator(
            operationInProgressProvider = { false },
            engineProvider = { FakeEngine() },
            selectedGameIdProvider = { "fallout_nv" },
            appendLog = log::add,
            appendError = { message, _ -> log += "ERROR:$message" },
            beginOperation = started::add,
            finishOperation = finished::add,
            failOperation = { message, _ -> log += "FAILED:$message" },
            refreshDashboard = { refreshCount++ }
        )

        coordinator.buildDeploymentPlanSummary()

        assertEquals(listOf("Building deploy plan..."), started)
        assertEquals(listOf("Deploy plan built."), finished)
        assertTrue(log.contains("plan for fallout_nv"))
        assertTrue(log.contains("No files were changed."))
        assertTrue(log.contains("RESULT: PASS"))
        assertEquals(1, refreshCount)
    }

    @Test
    fun `busy operation is ignored without refresh`() {
        val log = mutableListOf<String>()
        var refreshCount = 0
        val coordinator = DeveloperDiagnosticsCoordinator(
            operationInProgressProvider = { true },
            engineProvider = { FakeEngine() },
            selectedGameIdProvider = { "fallout_nv" },
            appendLog = log::add,
            appendError = { _, _ -> },
            beginOperation = {},
            finishOperation = {},
            failOperation = { _, _ -> },
            refreshDashboard = { refreshCount++ }
        )

        coordinator.buildResolvedDataGraphSummary()

        assertEquals(
            listOf("Ignoring resolved graph request: operation already in progress."),
            log
        )
        assertEquals(0, refreshCount)
    }

    @Test
    fun `integrity evidence for superseded profile is stale and never refreshes indexes`() {
        val log = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val coordinator = DeveloperDiagnosticsCoordinator(
            operationInProgressProvider = { false }, engineProvider = { FakeEngine() },
            selectedGameIdProvider = { "fallout_nv" }, appendLog = log::add,
            appendError = { _, _ -> }, beginOperation = {}, finishOperation = finished::add,
            failOperation = { _, _ -> org.junit.Assert.fail("Unexpected failure") },
            refreshDashboard = { org.junit.Assert.fail("Read-only checks must not rebuild indexes") }
        )
        coordinator.verifyManagedContent(FakeEngine(), "fallout_nv", "profile-a", { false }, { false })
        assertTrue(log.any { it.contains("profile=profile-a") })
        assertTrue(log.any { it.contains("STALE") })
        assertEquals(listOf("Integrity evidence is stale; rerun required."), finished)
        assertTrue(log.none { it == "RESULT: PASS" })
    }

    @Test
    fun `integrity cancellation is partial and not a successful content verdict`() {
        val log = mutableListOf<String>()
        val coordinator = DeveloperDiagnosticsCoordinator(
            operationInProgressProvider = { false }, engineProvider = { FakeEngine() },
            selectedGameIdProvider = { "fallout_nv" }, appendLog = log::add,
            appendError = { _, _ -> }, beginOperation = {}, finishOperation = log::add,
            failOperation = { _, _ -> org.junit.Assert.fail("Unexpected failure") },
            refreshDashboard = { org.junit.Assert.fail("Unexpected refresh") }
        )
        coordinator.verifyManagedContent(FakeEngine(), "fallout_nv", "profile-a", { true }, { true })
        assertTrue(log.any { it.contains("CANCELLED / PARTIAL") })
        assertTrue(log.contains("Integrity check cancelled; partial evidence only."))
    }

    private class FakeEngine : DeveloperDiagnosticsEngine {
        override fun verifyManagedContent(gameId: String, cancelled: () -> Boolean) =
            com.shonkware.droidmodloader.engine.integrity.ManagedIntegrityReport(
                1, "digest", emptyList(), cancelled(), false
            )
        override fun buildResolvedDataGraphDebugSummary(): String = "resolved"
        override fun buildDeploymentPlanDebugSummary(gameId: String): String = "plan for $gameId"
        override fun buildDownloadedArchiveSummary(): String = "archives"
        override fun buildFullRedeployPlanDebugSummary(gameId: String): String = "full $gameId"
        override fun indexModContent(mod: Mod): ModContentIndex = ModContentIndex(
            modId = mod.id,
            modName = mod.name,
            entries = emptyList()
        )
    }
}
