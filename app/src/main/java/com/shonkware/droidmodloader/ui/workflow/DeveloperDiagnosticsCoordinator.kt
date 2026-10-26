package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.integrity.ManagedIntegrityReport
import com.shonkware.droidmodloader.engine.model.Mod

internal interface DeveloperDiagnosticsEngine {
    fun buildResolvedDataGraphDebugSummary(): String
    fun buildDeploymentPlanDebugSummary(gameId: String): String
    fun buildDownloadedArchiveSummary(): String
    fun buildStorageDiagnosticSummary(gameId: String): String
    fun buildFullRedeployPlanDebugSummary(gameId: String): String
    fun indexModContent(mod: Mod): ModContentIndex
    fun verifyManagedContent(gameId: String, cancelled: () -> Boolean): ManagedIntegrityReport
}

internal class DeveloperDiagnosticsEngineAdapter(
    private val engine: ModEngine
) : DeveloperDiagnosticsEngine {
    override fun buildResolvedDataGraphDebugSummary(): String =
        engine.buildResolvedDataGraphDebugSummary()

    override fun buildDeploymentPlanDebugSummary(gameId: String): String =
        engine.buildDeploymentPlanDebugSummary(gameId)

    override fun buildDownloadedArchiveSummary(): String =
        engine.buildDownloadedArchiveSummary()

    override fun buildStorageDiagnosticSummary(gameId: String): String = engine.buildStorageDiagnosticSummary(gameId)

    override fun buildFullRedeployPlanDebugSummary(gameId: String): String =
        engine.buildFullRedeployPlanDebugSummary(gameId)

    override fun indexModContent(mod: Mod): ModContentIndex = engine.indexModContent(mod)

    override fun verifyManagedContent(gameId: String, cancelled: () -> Boolean) =
        engine.verifyManagedContent(gameId, cancelled)
}

internal class DeveloperDiagnosticsCoordinator(
    private val operationInProgressProvider: () -> Boolean,
    private val engineProvider: () -> DeveloperDiagnosticsEngine?,
    private val selectedGameIdProvider: () -> String,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val beginOperation: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val refreshDashboard: () -> Unit
) {
    fun verifyManagedContent(
        engine: DeveloperDiagnosticsEngine,
        gameId: String,
        profileId: String,
        cancelled: () -> Boolean,
        contextStillCurrent: () -> Boolean
    ): ManagedIntegrityReport? {
        beginOperation("Checking recorded managed content...")
        return try {
            val result = engine.verifyManagedContent(gameId, cancelled)
            val report = result.copy(stale = result.stale || !contextStillCurrent())
            appendLog("----- Managed Content Integrity: profile=$profileId; game=$gameId -----")
            report.toSummary().lineSequence().filter { it.isNotEmpty() }.forEach(appendLog)
            finishOperation(when {
                report.stale -> "Integrity evidence is stale; rerun required."
                report.cancelled -> "Integrity check cancelled; partial evidence only."
                report.groups.any { !it.complete } -> "Integrity check partial; some evidence unavailable."
                else -> "Integrity check completed; review recorded counts."
            })
            report
        } catch (failure: Exception) {
            failOperation("Integrity check failed: ${failure.message}", failure)
            null
        }
        // The caller may reproject recorded metadata, but must not rebuild expected indexes.
    }

    fun inspectStorage(
        engine: DeveloperDiagnosticsEngine,
        context: OperationContext,
        contextStillCurrent: () -> Boolean
    ) {
        beginOperation("Inspecting managed storage...")
        try {
            check(!context.unavailable) { "Storage context is unavailable." }
            val summary = engine.buildStorageDiagnosticSummary(context.gameId)
            if (!contextStillCurrent()) {
                finishOperation("Storage facts discarded: profile, game or targets changed.")
                return
            }
            appendLog("Storage scope: profile=${context.profileId ?: "unassigned"}; game=${context.gameId}")
            appendLog("Captured target intent: real=${context.realDeployment}; Data=${context.dataTarget}; Game Root=${context.rootTarget}")
            appendLog("Intent is not validated target evidence; measured locations use saved configuration.")
            summary.lineSequence().filter { it.isNotEmpty() }.forEach(appendLog)
            finishOperation("Storage facts collected; requirements may be unknown.")
        } catch (failure: Exception) {
            failOperation("Storage inspection failed: ${failure.message}", failure)
        }
    }

    fun buildResolvedDataGraphSummary() {
        runSummaryOperation(
            busyMessage = "Ignoring resolved graph request: operation already in progress.",
            startMessage = "Building resolved data graph...",
            heading = "Resolved Data Graph Summary",
            successMessage = "Resolved data graph built.",
            failurePrefix = "Resolved data graph failed",
            includeNoFilesChanged = false
        ) { engine ->
            engine.buildResolvedDataGraphDebugSummary()
        }
    }

    fun buildDeploymentPlanSummary() {
        val gameId = selectedGameIdProvider()
        runSummaryOperation(
            busyMessage = "Ignoring deploy plan request: operation already in progress.",
            startMessage = "Building deploy plan...",
            heading = "Deploy Plan Summary",
            successMessage = "Deploy plan built.",
            failurePrefix = "Deploy plan failed"
        ) { engine ->
            engine.buildDeploymentPlanDebugSummary(gameId)
        }
    }

    fun buildArchiveLibrarySummary() {
        runSummaryOperation(
            busyMessage = "Ignoring archive library summary request: operation already in progress.",
            startMessage = "Building archive library summary...",
            heading = "Archive Library Summary",
            successMessage = "Archive library summary built.",
            failurePrefix = "Archive library summary failed"
        ) { engine ->
            engine.buildDownloadedArchiveSummary()
        }
    }

    fun buildFullRedeployPlanSummary() {
        val gameId = selectedGameIdProvider()
        runSummaryOperation(
            busyMessage = "Ignoring full redeploy plan request: operation already in progress.",
            startMessage = "Building full redeploy plan...",
            heading = "Full Redeploy Plan Summary",
            successMessage = "Full redeploy plan built.",
            failurePrefix = "Full redeploy plan failed"
        ) { engine ->
            engine.buildFullRedeployPlanDebugSummary(gameId)
        }
    }

    fun appendInstalledModRoutingSummary(
        engine: DeveloperDiagnosticsEngine,
        mod: Mod
    ) {
        try {
            val index = engine.indexModContent(mod)
            appendLog("Installed mod routing summary for ${mod.name}:")
            appendLog("  Data files: ${index.dataFiles.size}")
            appendLog("  Game root files: ${index.gameRootFiles.size}")
            appendLog("  Manager-only files: ${index.managerOnlyFiles.size}")
            appendLog("  Unknown files: ${index.unknownFiles.size}")
            index.gameRootFiles.take(10).forEach { entry ->
                appendLog("  ROOT: ${entry.normalizedPath}")
            }
            index.dataFiles.take(10).forEach { entry ->
                appendLog("  DATA: ${entry.normalizedPath}")
            }
        } catch (e: Exception) {
            appendError("Failed to build installed mod routing summary: ${e.message}", e)
        }
    }

    private fun runSummaryOperation(
        busyMessage: String,
        startMessage: String,
        heading: String,
        successMessage: String,
        failurePrefix: String,
        includeNoFilesChanged: Boolean = true,
        buildSummary: (DeveloperDiagnosticsEngine) -> String
    ) {
        if (operationInProgressProvider()) {
            appendLog(busyMessage)
            return
        }

        beginOperation(startMessage)
        try {
            val engine = engineProvider()
                ?: throw IllegalStateException("Could not create engine for active profile.")
            val summary = buildSummary(engine)

            appendLog("----- $heading -----")
            summary.lineSequence().forEach(appendLog)
            appendLog("----- $heading End -----")
            if (includeNoFilesChanged) {
                appendLog("No files were changed.")
            }
            appendLog("RESULT: PASS")
            finishOperation(successMessage)
        } catch (e: Exception) {
            appendError("$failurePrefix: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("$failurePrefix: ${e.message}", e)
        }

        refreshDashboard()
    }
}
