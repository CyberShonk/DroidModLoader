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
        assertEquals(listOf("File-check results are out of date. Run the check again."), finished)
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
        assertTrue(log.contains("File check cancelled. Review the partial results in the log."))
    }

    @Test
    fun `storage inspection uses captured game and logs scoped historical facts without refreshing`() {
        val log = mutableListOf<String>()
        var game = ""
        val engine = FakeEngine { game = it; "Storage facts (historical observation)\nAvailable: unknown" }
        val coordinator = storageCoordinator(log)
        val context = OperationContext("profile-a", "fallout_nv", false, "/intent/Data", "/intent")
        coordinator.inspectStorage(engine, context) { true }
        assertEquals("fallout_nv", game)
        assertTrue(log.any { it.contains("profile=profile-a; game=fallout_nv") })
        assertTrue(log.any { it.contains("Data=/intent/Data") })
        assertTrue(log.contains("Available: unknown"))
        assertTrue(log.contains("Storage info is in the log. Space needed for your next action may still be unknown."))
        assertTrue(log.none { it == "RESULT: PASS" })
    }

    @Test
    fun `storage facts for superseded targets are discarded before publication`() {
        val log = mutableListOf<String>()
        var current = true
        val engine = FakeEngine { current = false; "OLD TARGET FACTS MUST NOT APPEAR" }
        storageCoordinator(log).inspectStorage(engine, OperationContext("profile-a", "fallout_nv", true, "/A/Data", "/A")) { current }
        assertTrue(log.any { it.contains("Storage results were not kept") })
        assertTrue(log.none { it.contains("OLD TARGET FACTS") || it.contains("Storage scope:") })
    }

    @Test
    fun `unavailable storage context never starts a filesystem inspection`() {
        val log = mutableListOf<String>()
        val engine = FakeEngine { throw AssertionError("Unavailable context must not inspect storage") }
        storageCoordinator(log).inspectStorage(engine, OperationContext(null, "game", false, "", "", true)) { true }
        assertTrue(log.any { it.contains("Could not read storage info") })
    }

    private fun storageCoordinator(log: MutableList<String>) = DeveloperDiagnosticsCoordinator(
        { false }, { null }, { "different mutable game" }, log::add, { message, _ -> log.add(message) },
        log::add, log::add, { message, _ -> log.add(message) },
        { throw AssertionError("Storage inspection must not refresh or rebuild indexes") }
    )

    private class FakeEngine(private val storageRead: (String) -> String = { "storage $it" }) : DeveloperDiagnosticsEngine {
        override fun buildStorageDiagnosticSummary(gameId: String): String = storageRead(gameId)
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
