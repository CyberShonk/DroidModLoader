package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig

internal interface DeploymentExecutionEngine {
    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig?
    fun getDeploymentTargetDebugSummary(gameId: String): String
    fun getCurrentRootWinningRecordCount(): Int
    fun validateTargetDataPath(path: String): Boolean
    fun deployForGame(gameId: String): ScopedDeploymentResult
    fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult
    fun buildFullRedeployPlanDebugSummary(gameId: String): String
    fun getDeploymentJournalDebugSummary(gameId: String): String
    fun syncPlugins()
}

internal class DeploymentExecutionEngineAdapter(
    private val engine: ModEngine,
    private val syncPluginsAction: () -> Unit
) : DeploymentExecutionEngine {
    override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? {
        return engine.getGameDeploymentConfig(gameId)
    }

    override fun getDeploymentTargetDebugSummary(gameId: String): String {
        return engine.getDeploymentTargetDebugSummary(gameId)
    }

    override fun getCurrentRootWinningRecordCount(): Int {
        return engine.getCurrentRootWinningRecords().size
    }

    override fun validateTargetDataPath(path: String): Boolean {
        return engine.validateTargetDataPath(path)
    }

    override fun deployForGame(gameId: String): ScopedDeploymentResult {
        return engine.deployForGame(gameId)
    }

    override fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult {
        return engine.forceFullRedeployForGame(gameId)
    }

    override fun buildFullRedeployPlanDebugSummary(gameId: String): String {
        return engine.buildFullRedeployPlanDebugSummary(gameId)
    }

    override fun getDeploymentJournalDebugSummary(gameId: String): String {
        return engine.getDeploymentJournalDebugSummary(gameId)
    }

    override fun syncPlugins() {
        syncPluginsAction.invoke()
    }
}

internal class DeploymentExecutionWorkflow(
    private val isOperationInProgress: () -> Boolean,
    private val selectedGameIdProvider: () -> String,
    private val simulatedDataTargetPathProvider: () -> String,
    private val saveActiveProfile: () -> Unit,
    private val saveSelectedGameConfig: () -> Unit,
    private val createEngine: () -> DeploymentExecutionEngine?,
    private val beginOperation: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val refreshDashboard: () -> Unit
) {
    fun deploy() {
        if (isOperationInProgress()) {
            appendLog("Ignoring deploy request: operation already in progress.")
            return
        }

        beginOperation("Deploying mods...")

        try {
            saveActiveProfile()
            saveSelectedGameConfig()

            val engine = createEngine() ?: return
            val gameId = selectedGameIdProvider()
            val config = engine.getGameDeploymentConfig(gameId)

            appendLog("Selected game: $gameId")
            appendLog("Active config: $config")
            appendLog(engine.getDeploymentTargetDebugSummary(gameId))

            val rootRecordCount = engine.getCurrentRootWinningRecordCount()
            val rootTargetSelected =
                config != null &&
                    config.realDeployEnabled &&
                    !config.rootPathReselectionRequired &&
                    engine.validateTargetDataPath(config.targetRootPath)

            if (rootRecordCount > 0) {
                appendLog("Root-scope deployable file count: $rootRecordCount")
            }

            if (rootRecordCount > 0 && config?.realDeployEnabled == true && !rootTargetSelected) {
                appendLog(
                    "WARNING: Root-scope files were detected, but no game root folder is selected. " +
                        "Pick the game root folder to deploy files like SKSE/NVSE loaders, DLLs, ENB files, or other root-level files."
                )
            }

            val result = engine.deployForGame(gameId)

            val usingDirectPath =
                config != null &&
                    config.realDeployEnabled &&
                    !config.dataPathReselectionRequired &&
                    engine.validateTargetDataPath(config.targetDataPath)

            val effectiveMode = if (usingDirectPath) "Direct Path" else "Simulated"
            val effectiveTarget = config
                ?.targetDataPath
                ?.takeIf { usingDirectPath }
                ?: simulatedDataTargetPathProvider()

            appendLog("Deploy mode: $effectiveMode")
            appendLog("Data deploy target: $effectiveTarget")

            val rootTarget = if (
                config != null &&
                config.realDeployEnabled &&
                !config.rootPathReselectionRequired &&
                engine.validateTargetDataPath(config.targetRootPath)
            ) {
                config.targetRootPath
            } else {
                "Simulated game root"
            }

            appendLog("Game root deploy target: $rootTarget")

            appendDeploymentResultBlock(
                title = "Data deploy result",
                result = result,
                useRootResult = false
            )
            appendDeploymentResultBlock(
                title = "Game root deploy result",
                result = result,
                useRootResult = true
            )

            appendCombinedResult(
                title = "Combined deploy result:",
                result = result
            )

            appendJournal(engine, gameId)
            appendLog("RESULT: PASS")
            finishOperation("Deploy succeeded ($effectiveMode).")
        } catch (e: DeploymentPreflightException) {
            appendPreflightFailure(
                heading = "Deploy",
                failureMessage = "Deploy blocked by readiness check.",
                exception = e
            )
        } catch (e: Exception) {
            appendError("Deploy workflow failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Deploy failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Deploy Workflow End -----")
    }

    fun forceFullRedeploy() {
        if (isOperationInProgress()) {
            appendLog("Ignoring full redeploy request: operation already in progress.")
            return
        }

        beginOperation("Force full redeploy...")

        try {
            saveActiveProfile()
            saveSelectedGameConfig()

            val engine = createEngine()
                ?: throw IllegalStateException("Could not create engine for active profile.")
            val gameId = selectedGameIdProvider()
            val config = engine.getGameDeploymentConfig(gameId)

            appendLog("----- Force Full Redeploy Start -----")
            appendLog("Selected game: $gameId")
            appendLog("Active config: $config")
            appendLog(engine.getDeploymentTargetDebugSummary(gameId))

            appendLog("----- Full Redeploy Plan Before Execution -----")
            engine.buildFullRedeployPlanDebugSummary(gameId)
                .lineSequence()
                .forEach { line -> appendLog(line) }
            appendLog("----- Full Redeploy Plan Before Execution End -----")

            val result = engine.forceFullRedeployForGame(gameId)

            appendDeploymentResultBlock(
                title = "Data full redeploy result",
                result = result,
                useRootResult = false
            )
            appendDeploymentResultBlock(
                title = "Game Root full redeploy result",
                result = result,
                useRootResult = true
            )

            appendCombinedResult(
                title = "Combined full redeploy result:",
                result = result
            )

            appendJournal(engine, gameId)
            engine.syncPlugins()

            appendLog("RESULT: PASS")
            finishOperation("Full redeploy succeeded.")
        } catch (e: DeploymentPreflightException) {
            appendPreflightFailure(
                heading = "Full redeploy",
                failureMessage = "Full redeploy blocked by readiness check.",
                exception = e
            )
        } catch (e: Exception) {
            appendError("Full redeploy failed: ${e.message}", e)
            appendLog("RESULT: FAIL")
            failOperation("Full redeploy failed: ${e.message}", e)
        }

        refreshDashboard()
        appendLog("----- Force Full Redeploy End -----")
    }

    private fun appendDeploymentResultBlock(
        title: String,
        result: ScopedDeploymentResult,
        useRootResult: Boolean = false
    ) {
        val scopedResult = if (useRootResult) result.rootResult else result.dataResult
        OperationLogFormatter.deploymentResultBlockLines(
            title = title,
            result = scopedResult
        ).forEach { line -> appendLog(line) }
    }

    private fun appendCombinedResult(
        title: String,
        result: ScopedDeploymentResult
    ) {
        appendLog(title)
        appendLog("  Adds: ${result.addCount}")
        appendLog("  Removes: ${result.removeCount}")
        appendLog("  Updates: ${result.updateCount}")
        appendLog("  Backups created: ${result.dataResult.backupCount + result.rootResult.backupCount}")
        appendLog("  Backups restored: ${result.dataResult.restoreCount + result.rootResult.restoreCount}")
        appendLog("  Protected conflicts: ${result.dataResult.protectedConflictCount + result.rootResult.protectedConflictCount}")
        appendLog("  Final file count: ${result.finalRecordCount}")
    }

    private fun appendJournal(
        engine: DeploymentExecutionEngine,
        gameId: String
    ) {
        appendLog("----- Last Deploy Journal -----")
        engine.getDeploymentJournalDebugSummary(gameId)
            .lineSequence()
            .forEach { line -> appendLog(line) }
        appendLog("----- Last Deploy Journal End -----")
    }

    private fun appendPreflightFailure(
        heading: String,
        failureMessage: String,
        exception: DeploymentPreflightException
    ) {
        appendError("$heading blocked by preflight check.", exception)
        appendLog("----- Deploy Readiness Check Failed -----")
        exception.result.toDebugSummary()
            .lineSequence()
            .forEach { line -> appendLog(line) }
        appendLog("----- Deploy Readiness Check Failed End -----")
        appendLog("No files were changed.")
        appendLog("RESULT: FAIL")
        failOperation(failureMessage, exception)
    }
}
