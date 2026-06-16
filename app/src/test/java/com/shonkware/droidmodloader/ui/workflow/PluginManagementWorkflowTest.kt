package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagementWorkflowTest {
    @Test
    fun togglePluginSortsNormalizesAndPreservesStatus() {
        val engine = FakePluginManagementEngine(
            listOf(
                plugin("second.esp", priority = 20, enabled = true),
                plugin("first.esm", priority = 10, enabled = true)
            )
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.togglePluginEnabled("second.esp")

        assertEquals(listOf("first.esm", "second.esp"), engine.savedPlugins.map { it.normalizedPath })
        assertEquals(listOf(1, 2), engine.savedPlugins.map { it.priority })
        assertFalse(engine.savedPlugins.last().enabled)
        assertEquals(listOf("Plugin updated: second.esp"), harness.statuses)
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun missingPluginDoesNotSaveOrRefresh() {
        val engine = FakePluginManagementEngine(listOf(plugin("first.esm", 1)))
        val harness = WorkflowHarness(engine)

        harness.workflow.togglePluginEnabled("missing.esp")

        assertTrue(engine.savedPlugins.isEmpty())
        assertEquals(0, harness.refreshCount)
        assertEquals("Could not find plugin: missing.esp", harness.errors.single().first)
    }

    @Test
    fun movePluginUpSwapsAdjacentEntries() {
        val engine = FakePluginManagementEngine(
            listOf(plugin("first.esm", 1), plugin("second.esp", 2), plugin("third.esp", 3))
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.movePluginUp("third.esp")

        assertEquals(
            listOf("first.esm", "third.esp", "second.esp"),
            engine.savedPlugins.map { it.normalizedPath }
        )
        assertEquals(listOf("Plugin moved up."), harness.statuses)
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun invalidMovesDoNotSaveOrRefresh() {
        val engine = FakePluginManagementEngine(
            listOf(plugin("first.esm", 1), plugin("second.esp", 2))
        )
        val harness = WorkflowHarness(engine)

        harness.workflow.movePluginUp("first.esm")
        harness.workflow.movePluginDown("second.esp")

        assertTrue(engine.savedPlugins.isEmpty())
        assertEquals(0, harness.refreshCount)
        assertEquals(
            listOf(
                "Cannot move plugin up: first.esm",
                "Cannot move plugin down: second.esp"
            ),
            harness.logs
        )
    }

    @Test
    fun applyPluginOrderDelegatesAndRefreshes() {
        val engine = FakePluginManagementEngine(emptyList())
        val harness = WorkflowHarness(engine)
        val order = listOf("third.esp", "first.esm", "second.esp")

        harness.workflow.applyPluginOrder(order)

        assertEquals(order, engine.appliedOrder)
        assertEquals(listOf("Applied dragged plugin order."), harness.logs)
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun writeLoadOrderFilesRefreshesEmptyListOnceThenWrites() {
        val engine = FakePluginManagementEngine(emptyList()).apply {
            pluginsAfterSync = listOf(plugin("first.esm", 1), plugin("second.esp", 2, enabled = false))
            exportedOutputs = "*first.esm\n" to "first.esm\nsecond.esp\n"
            outputPaths = "/target/plugins.txt" to "/target/loadorder.txt"
        }
        val harness = WorkflowHarness(engine)

        harness.workflow.writeLoadOrderFiles()

        assertEquals(1, engine.syncCount)
        assertEquals(listOf("Writing plugin files..."), harness.startedOperations)
        assertEquals(listOf("Plugin files written successfully."), harness.finishedOperations)
        assertTrue(harness.logs.contains("Plugin count: 2"))
        assertTrue(harness.logs.contains("Enabled plugin count: 1"))
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun writeLoadOrderFilesPreservesNoPluginsFailure() {
        val engine = FakePluginManagementEngine(emptyList())
        val harness = WorkflowHarness(engine)

        harness.workflow.writeLoadOrderFiles()

        assertEquals(1, engine.syncCount)
        assertEquals(
            listOf("Writing plugin files failed: no plugins available."),
            harness.failedOperations.map { it.first }
        )
        assertTrue(harness.logs.contains("RESULT: FAIL"))
        assertEquals(0, harness.refreshCount)
    }

    @Test
    fun writeRequestIsIgnoredWhileOperationIsRunning() {
        val engine = FakePluginManagementEngine(listOf(plugin("first.esm", 1)))
        val harness = WorkflowHarness(engine, operationInProgress = true)

        harness.workflow.writeLoadOrderFiles()

        assertTrue(harness.startedOperations.isEmpty())
        assertTrue(engine.exportRequests == 0)
        assertEquals(
            listOf("Ignoring plugin file write request: operation already in progress."),
            harness.logs
        )
    }

    private class WorkflowHarness(
        engine: FakePluginManagementEngine?,
        operationInProgress: Boolean = false
    ) {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()
        val statuses = mutableListOf<String>()
        val startedOperations = mutableListOf<String>()
        val finishedOperations = mutableListOf<String>()
        val failedOperations = mutableListOf<Pair<String, Throwable?>>()
        var refreshCount = 0

        val workflow = PluginManagementWorkflow(
            createEngine = { engine },
            isOperationInProgress = { operationInProgress },
            beginOperation = { text -> startedOperations.add(text) },
            finishOperation = { text -> finishedOperations.add(text) },
            failOperation = { message, throwable -> failedOperations.add(message to throwable) },
            appendLog = { message -> logs.add(message) },
            appendError = { message, throwable -> errors.add(message to throwable) },
            updateLastOperationStatus = { status -> statuses.add(status) },
            refreshDashboard = { refreshCount++ }
        )
    }

    private class FakePluginManagementEngine(
        initialPlugins: List<PluginEntry>
    ) : PluginManagementEngine {
        private var currentPlugins = initialPlugins
        var pluginsAfterSync: List<PluginEntry> = initialPlugins
        var savedPlugins: List<PluginEntry> = emptyList()
        var appliedOrder: List<String> = emptyList()
        var exportedOutputs: Pair<String, String> = "" to ""
        var outputPaths: Pair<String, String> = "plugins.txt" to "loadorder.txt"
        var syncCount = 0
        var exportRequests = 0

        override fun getCurrentPlugins(): List<PluginEntry> = currentPlugins

        override fun loadPlugins(): List<PluginEntry> = currentPlugins

        override fun normalizePluginPriorities(plugins: List<PluginEntry>): List<PluginEntry> {
            return plugins.mapIndexed { index, plugin -> plugin.copy(priority = index + 1) }
        }

        override fun saveCurrentPlugins(plugins: List<PluginEntry>) {
            savedPlugins = plugins
            currentPlugins = plugins
        }

        override fun applyPluginPriorityOrder(orderedPluginPaths: List<String>) {
            appliedOrder = orderedPluginPaths
        }

        override fun exportSavedPluginOutputs(): Pair<String, String> {
            exportRequests++
            return exportedOutputs
        }

        override fun getPluginOutputFilePaths(): Pair<String, String> = outputPaths

        override fun syncPlugins() {
            syncCount++
            currentPlugins = pluginsAfterSync
        }
    }

    private fun plugin(
        normalizedPath: String,
        priority: Int,
        enabled: Boolean = true
    ): PluginEntry {
        return PluginEntry(
            normalizedPath = normalizedPath,
            pluginName = normalizedPath,
            sourceModId = "mod",
            sourceModName = "Mod",
            enabled = enabled,
            priority = priority,
            pluginType = if (normalizedPath.endsWith(".esm")) "esm" else "esp"
        )
    }
}
