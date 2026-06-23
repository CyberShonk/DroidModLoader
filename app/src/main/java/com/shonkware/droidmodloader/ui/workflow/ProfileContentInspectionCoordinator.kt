package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.overwrite.OverwriteScanResult

internal interface ProfileContentInspectionEngine {
    fun getCurrentMods(): List<Mod>
    fun buildModFilePreview(mod: Mod): ModFilePreview
    fun scanOverwriteFiles(gameId: String): OverwriteScanResult
    fun getDeploymentTargetDebugSummary(gameId: String): String
    fun hasDataBaseline(gameId: String): Boolean
    fun rebuildDataBaseline(gameId: String): DataBaselineSnapshot
}

internal class ProfileContentInspectionEngineAdapter(
    private val engine: ModEngine
) : ProfileContentInspectionEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()
    override fun buildModFilePreview(mod: Mod): ModFilePreview = engine.buildModFilePreview(mod)
    override fun scanOverwriteFiles(gameId: String): OverwriteScanResult =
        engine.scanOverwriteFiles(gameId)

    override fun getDeploymentTargetDebugSummary(gameId: String): String =
        engine.getDeploymentTargetDebugSummary(gameId)

    override fun hasDataBaseline(gameId: String): Boolean = engine.hasDataBaseline(gameId)
    override fun rebuildDataBaseline(gameId: String): DataBaselineSnapshot =
        engine.rebuildDataBaseline(gameId)
}

internal class ProfileContentInspectionCoordinator(
    private val engineProvider: () -> ProfileContentInspectionEngine?,
    private val selectedGameIdProvider: () -> String,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val showModPreview: (ModFilePreview) -> Unit,
    private val showOverwriteResult: (OverwriteScanResult) -> Unit,
    private val updateBaselineState: (exists: Boolean, message: String) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit
) {
    fun openModFilePreview(modId: String) {
        val engine = engineProvider() ?: return
        val mod = engine.getCurrentMods().firstOrNull { it.id == modId }
        if (mod == null) {
            appendError("Could not open file preview. Mod not found: $modId", null)
            return
        }

        try {
            val preview = engine.buildModFilePreview(mod)
            runOnUiThread { showModPreview(preview) }
            appendLog("Opened file preview for mod: ${mod.name}")
        } catch (e: Exception) {
            appendError("Failed to build file preview for $modId: ${e.message}", e)
        }
    }

    fun openOverwriteFolderPanel() {
        val engine = engineProvider() ?: return
        try {
            ensureDataBaselineIfMissing(engine, "opening overwrite folder")
            val result = engine.scanOverwriteFiles(selectedGameIdProvider())
            runOnUiThread { showOverwriteResult(result) }
            appendLog("Opened overwrite folder panel. ${result.message}")
        } catch (e: Exception) {
            appendError("Failed to scan overwrite files: ${e.message}", e)
        }
    }

    fun ensureDataBaselineIfMissing(reason: String) {
        val engine = engineProvider() ?: return
        ensureDataBaselineIfMissing(engine, reason)
    }

    private fun ensureDataBaselineIfMissing(
        engine: ProfileContentInspectionEngine,
        reason: String
    ) {
        val gameId = selectedGameIdProvider()
        try {
            appendLog(engine.getDeploymentTargetDebugSummary(gameId))
            if (engine.hasDataBaseline(gameId)) {
                appendLog("Data baseline already exists for $gameId.")
                return
            }

            val snapshot = engine.rebuildDataBaseline(gameId)
            runOnUiThread {
                updateBaselineState(
                    true,
                    "Indexed ${snapshot.files.size} existing Data folder files."
                )
            }
            appendLog("Created Data baseline automatically for $gameId.")
            appendLog("Baseline reason: $reason")
            appendLog("Baseline file count: ${snapshot.files.size}")
            updateLastOperationStatus("Indexed existing Data folder automatically.")
        } catch (e: Exception) {
            appendError("Automatic Data baseline failed: ${e.message}", e)
        }
    }
}
