package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.UninstallResult
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModManagementWorkflowTest {
    @Test
    fun toggleModEnabledSortsAndNormalizesBeforeSaving() {
        val engine = FakeModManagementEngine(
            listOf(
                mod("second", priority = 20, enabled = true),
                mod("first", priority = 10, enabled = true)
            )
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.toggleModEnabled("second")

        assertEquals(listOf("first", "second"), engine.savedMods.map { it.id })
        assertEquals(listOf(1, 2), engine.savedMods.map { it.priority })
        assertFalse(engine.savedMods.last().enabled)
        assertEquals(1, engine.syncCount)
        assertEquals(1, harness.refreshCount)
        assertTrue(harness.logs.contains("Toggled enabled state for second"))
    }

    @Test
    fun moveModUpSwapsAdjacentModsAndNormalizesPriorities() {
        val engine = FakeModManagementEngine(
            listOf(
                mod("first", priority = 1),
                mod("second", priority = 2),
                mod("third", priority = 3)
            )
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.moveModUp("third")

        assertEquals(listOf("first", "third", "second"), engine.savedMods.map { it.id })
        assertEquals(listOf(1, 2, 3), engine.savedMods.map { it.priority })
        assertEquals(1, engine.syncCount)
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun invalidMoveDoesNotSaveSyncOrRefresh() {
        val engine = FakeModManagementEngine(
            listOf(
                mod("first", priority = 1),
                mod("second", priority = 2)
            )
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.moveModUp("first")
        harness.workflow.moveModDown("second")

        assertTrue(engine.savedMods.isEmpty())
        assertEquals(0, engine.syncCount)
        assertEquals(0, harness.refreshCount)
        assertEquals(
            listOf("Cannot move up: first", "Cannot move down: second"),
            harness.logs
        )
    }

    @Test
    fun successfulDeletePreservesLogsStatusSyncAndRefresh() {
        val engine = FakeModManagementEngine(emptyList()).apply {
            uninstallResult = UninstallResult(
                removed = true,
                removedModId = "example",
                deletedFileCount = 7
            )
        }
        val harness = WorkflowHarness(engine)

        harness.workflow.deleteInstalledMod("example")

        assertEquals(listOf("example"), engine.uninstallRequests)
        assertEquals(1, engine.syncCount)
        assertEquals(1, harness.refreshCount)
        assertEquals("Delete mod succeeded: example", harness.statuses.single())
        assertTrue(harness.logs.contains("Deleted installed mod files: 7"))
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertEquals(
            "----- Delete Installed Mod Workflow End -----",
            harness.logs.last()
        )
    }

    @Test
    fun unsuccessfulDeletePreservesFailureStatusAndSkipsSync() {
        val engine = FakeModManagementEngine(emptyList()).apply {
            uninstallResult = UninstallResult(
                removed = false,
                removedModId = "missing",
                deletedFileCount = 0
            )
        }
        val harness = WorkflowHarness(engine)

        harness.workflow.deleteInstalledMod("missing")

        assertEquals(0, engine.syncCount)
        assertEquals(0, harness.refreshCount)
        assertEquals(
            "Delete mod failed: could not remove missing.",
            harness.statuses.single()
        )
        assertEquals("Could not remove mod: missing", harness.errors.single().first)
        assertTrue(harness.logs.contains("RESULT: FAIL"))
    }

    @Test
    fun applyModOrderDelegatesThenSyncsAndRefreshes() {
        val engine = FakeModManagementEngine(emptyList())
        val harness = WorkflowHarness(engine)
        val order = listOf("third", "first", "second")

        harness.workflow.applyModOrder(order)

        assertEquals(order, engine.appliedOrder)
        assertEquals(1, engine.syncCount)
        assertEquals(1, harness.refreshCount)
        assertEquals(listOf("Applied dragged mod order."), harness.logs)
    }

    private class WorkflowHarness(engine: FakeModManagementEngine) {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()
        val statuses = mutableListOf<String>()
        var refreshCount = 0

        val workflow = ModManagementWorkflow(
            withEngine = { action -> action(engine) },
            appendLog = { message -> logs.add(message) },
            appendError = { message, throwable -> errors.add(message to throwable) },
            updateLastOperationStatus = { status -> statuses.add(status) },
            refreshDashboard = { refreshCount++ }
        )
    }

    private class FakeModManagementEngine(
        initialMods: List<Mod>
    ) : ModManagementEngine {
        private var currentMods = initialMods
        var savedMods: List<Mod> = emptyList()
        var uninstallResult = UninstallResult(false, "", 0)
        val uninstallRequests = mutableListOf<String>()
        var appliedOrder: List<String> = emptyList()
        var syncCount = 0

        override fun getCurrentMods(): List<Mod> = currentMods

        override fun saveCurrentMods(mods: List<Mod>) {
            savedMods = mods
            currentMods = mods
        }

        override fun uninstallModAndApplyDiff(modId: String): UninstallResult {
            uninstallRequests.add(modId)
            return uninstallResult
        }

        override fun applyModPriorityOrder(orderedModIds: List<String>) {
            appliedOrder = orderedModIds
        }

        override fun syncPlugins() {
            syncCount++
        }
    }

    private fun mod(
        id: String,
        priority: Int,
        enabled: Boolean = true
    ): Mod {
        return Mod(
            id = id,
            name = id,
            installPath = "/mods/$id",
            enabled = enabled,
            priority = priority,
            modType = ModType.LOOSE
        )
    }
}
