package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.deploy.DeploymentResult
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightIssue
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightSeverity
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentExecutionWorkflowTest {
    @Test
    fun deploySavesConfigurationLogsResultsAndFinishes() {
        val engine = FakeDeploymentExecutionEngine(
            config = config(),
            rootRecordCount = 2,
            validPaths = setOf("/data", "/root"),
            deployResult = scopedResult()
        )
        val harness = Harness(engine = engine)

        harness.workflow.deploy()

        assertEquals(1, harness.saveProfileCount)
        assertEquals(1, harness.saveConfigCount)
        assertEquals(listOf("Deploying mods..."), harness.begunOperations)
        assertEquals(listOf("Deploy succeeded (Direct Path)."), harness.finishedOperations)
        assertTrue(harness.failedOperations.isEmpty())
        assertEquals(1, harness.refreshCount)
        assertEquals("skyrim_le", engine.deployedGameId)
        assertEquals(0, engine.syncPluginsCount)
        assertTrue(harness.logs.contains("Selected game: skyrim_le"))
        assertTrue(harness.logs.contains("Root-scope deployable file count: 2"))
        assertTrue(harness.logs.contains("Deploy mode: Direct Path"))
        assertTrue(harness.logs.contains("Data deploy target: /data"))
        assertTrue(harness.logs.contains("Game root deploy target: /root"))
        assertTrue(harness.logs.contains("Data deploy result:"))
        assertTrue(harness.logs.contains("Game root deploy result:"))
        assertTrue(harness.logs.contains("Combined deploy result:"))
        assertTrue(harness.logs.contains("----- Last Deploy Journal -----"))
        assertTrue(harness.logs.contains("journal line one"))
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertEquals("----- Deploy Workflow End -----", harness.logs.last())
    }

    @Test
    fun deployWarnsWhenRootFilesExistWithoutRootTarget() {
        val engine = FakeDeploymentExecutionEngine(
            config = config(
                targetDataPath = "/invalid/data",
                targetRootPath = ""
            ),
            rootRecordCount = 3,
            validPaths = emptySet(),
            deployResult = scopedResult()
        )
        val harness = Harness(engine = engine)

        harness.workflow.deploy()

        assertTrue(
            harness.logs.contains(
                "WARNING: Root-scope files were detected, but no game root folder is selected. " +
                    "Pick the game root folder to deploy files like SKSE/NVSE loaders, DLLs, ENB files, or other root-level files."
            )
        )
        assertTrue(harness.logs.contains("Deploy mode: Simulated"))
        assertTrue(harness.logs.contains("Data deploy target: /simulated/profile/skyrim_le/Data"))
        assertTrue(harness.logs.contains("Game root deploy target: Simulated game root"))
    }

    @Test
    fun deployReportsPreflightFailureAndRefreshes() {
        val preflightException = preflightException()
        val engine = FakeDeploymentExecutionEngine(
            config = config(),
            deployThrowable = preflightException
        )
        val harness = Harness(engine = engine)

        harness.workflow.deploy()

        assertTrue(harness.finishedOperations.isEmpty())
        assertEquals("Deploy blocked by readiness check.", harness.failedOperations.single().first)
        assertEquals(preflightException, harness.failedOperations.single().second)
        assertEquals("Deploy blocked by preflight check.", harness.errors.single().first)
        assertTrue(harness.logs.contains("----- Deploy Readiness Check Failed -----"))
        assertTrue(harness.logs.contains("No files were changed."))
        assertTrue(harness.logs.contains("RESULT: FAIL"))
        assertEquals(1, harness.refreshCount)
        assertEquals("----- Deploy Workflow End -----", harness.logs.last())
    }

    @Test
    fun deployPreservesExistingEarlyReturnWhenEngineCannotBeCreated() {
        val harness = Harness(engine = null)

        harness.workflow.deploy()

        assertEquals(1, harness.saveProfileCount)
        assertEquals(1, harness.saveConfigCount)
        assertEquals(listOf("Deploying mods..."), harness.begunOperations)
        assertTrue(harness.finishedOperations.isEmpty())
        assertTrue(harness.failedOperations.isEmpty())
        assertEquals(0, harness.refreshCount)
        assertFalse(harness.logs.contains("----- Deploy Workflow End -----"))
    }

    @Test
    fun forceFullRedeployLogsPlanSyncsPluginsAndFinishes() {
        val engine = FakeDeploymentExecutionEngine(
            config = config(),
            fullRedeployResult = scopedResult(),
            fullRedeployPlanSummary = "plan line one\nplan line two"
        )
        val harness = Harness(engine = engine)

        harness.workflow.forceFullRedeploy()

        assertEquals(1, harness.saveProfileCount)
        assertEquals(1, harness.saveConfigCount)
        assertEquals(listOf("Force full redeploy..."), harness.begunOperations)
        assertEquals(listOf("Full redeploy succeeded."), harness.finishedOperations)
        assertTrue(harness.failedOperations.isEmpty())
        assertEquals("skyrim_le", engine.fullRedeployedGameId)
        assertEquals(1, engine.syncPluginsCount)
        assertEquals(1, harness.refreshCount)
        assertTrue(harness.logs.contains("----- Force Full Redeploy Start -----"))
        assertTrue(harness.logs.contains("----- Full Redeploy Plan Before Execution -----"))
        assertTrue(harness.logs.contains("plan line one"))
        assertTrue(harness.logs.contains("plan line two"))
        assertTrue(harness.logs.contains("Data full redeploy result:"))
        assertTrue(harness.logs.contains("Game Root full redeploy result:"))
        assertTrue(harness.logs.contains("Combined full redeploy result:"))
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertEquals("----- Force Full Redeploy End -----", harness.logs.last())
    }

    @Test
    fun forceFullRedeployReportsUnavailableEngineAsFailure() {
        val harness = Harness(engine = null)

        harness.workflow.forceFullRedeploy()

        assertTrue(harness.finishedOperations.isEmpty())
        assertEquals(1, harness.failedOperations.size)
        assertEquals(
            "Full redeploy failed: Could not create engine for active profile.",
            harness.failedOperations.single().first
        )
        assertEquals(1, harness.errors.size)
        assertEquals(1, harness.refreshCount)
        assertTrue(harness.logs.contains("RESULT: FAIL"))
        assertEquals("----- Force Full Redeploy End -----", harness.logs.last())
    }

    @Test
    fun forceFullRedeployReportsPreflightFailureWithoutSyncingPlugins() {
        val preflightException = preflightException()
        val engine = FakeDeploymentExecutionEngine(
            config = config(),
            fullRedeployThrowable = preflightException
        )
        val harness = Harness(engine = engine)

        harness.workflow.forceFullRedeploy()

        assertEquals(0, engine.syncPluginsCount)
        assertEquals(
            "Full redeploy blocked by readiness check.",
            harness.failedOperations.single().first
        )
        assertEquals("Full redeploy blocked by preflight check.", harness.errors.single().first)
        assertEquals(1, harness.refreshCount)
        assertTrue(harness.logs.contains("No files were changed."))
        assertEquals("----- Force Full Redeploy End -----", harness.logs.last())
    }

    @Test
    fun requestsAreIgnoredWhileAnotherOperationIsRunning() {
        val engine = FakeDeploymentExecutionEngine(config = config())
        val harness = Harness(
            engine = engine,
            operationInProgress = true
        )

        harness.workflow.deploy()
        harness.workflow.forceFullRedeploy()

        assertEquals(0, harness.saveProfileCount)
        assertEquals(0, harness.saveConfigCount)
        assertTrue(harness.begunOperations.isEmpty())
        assertEquals(0, harness.refreshCount)
        assertEquals(null, engine.deployedGameId)
        assertEquals(null, engine.fullRedeployedGameId)
        assertEquals(
            listOf(
                "Ignoring deploy request: operation already in progress.",
                "Ignoring full redeploy request: operation already in progress."
            ),
            harness.logs
        )
    }

    private class Harness(
        private val engine: DeploymentExecutionEngine?,
        private val operationInProgress: Boolean = false
    ) {
        var saveProfileCount = 0
        var saveConfigCount = 0
        var refreshCount = 0
        val begunOperations = mutableListOf<String>()
        val finishedOperations = mutableListOf<String>()
        val failedOperations = mutableListOf<Pair<String, Throwable?>>()
        val logs = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()

        val workflow = DeploymentExecutionWorkflow(
            isOperationInProgress = { operationInProgress },
            selectedGameIdProvider = { "skyrim_le" },
            simulatedDataTargetPathProvider = { "/simulated/profile/skyrim_le/Data" },
            saveActiveProfile = { saveProfileCount++ },
            saveSelectedGameConfig = { saveConfigCount++ },
            createEngine = { engine },
            beginOperation = { begunOperations += it },
            finishOperation = { finishedOperations += it },
            failOperation = { message, throwable ->
                failedOperations += message to throwable
            },
            appendLog = { logs += it },
            appendError = { message, throwable ->
                errors += message to throwable
            },
            refreshDashboard = { refreshCount++ }
        )
    }

    private class FakeDeploymentExecutionEngine(
        private val config: GameDeploymentConfig?,
        private val rootRecordCount: Int = 0,
        private val validPaths: Set<String> = emptySet(),
        private val deployResult: ScopedDeploymentResult = scopedResult(),
        private val fullRedeployResult: ScopedDeploymentResult = scopedResult(),
        private val deployThrowable: Throwable? = null,
        private val fullRedeployThrowable: Throwable? = null,
        private val fullRedeployPlanSummary: String = "plan summary",
        private val journalSummary: String = "journal line one\njournal line two"
    ) : DeploymentExecutionEngine {
        var deployedGameId: String? = null
        var fullRedeployedGameId: String? = null
        var syncPluginsCount = 0

        override fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? {
            return config
        }

        override fun getDeploymentTargetDebugSummary(gameId: String): String {
            return "target summary for $gameId"
        }

        override fun getCurrentRootWinningRecordCount(): Int {
            return rootRecordCount
        }

        override fun validateTargetDataPath(path: String): Boolean {
            return path in validPaths
        }

        override fun deployForGame(gameId: String): ScopedDeploymentResult {
            deployThrowable?.let { throw it }
            deployedGameId = gameId
            return deployResult
        }

        override fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult {
            fullRedeployThrowable?.let { throw it }
            fullRedeployedGameId = gameId
            return fullRedeployResult
        }

        override fun buildFullRedeployPlanDebugSummary(gameId: String): String {
            return fullRedeployPlanSummary
        }

        override fun getDeploymentJournalDebugSummary(gameId: String): String {
            return journalSummary
        }

        override fun syncPlugins() {
            syncPluginsCount++
        }
    }

    companion object {
        private fun config(
            targetDataPath: String = "/data",
            targetRootPath: String = "/root"
        ): GameDeploymentConfig {
            return GameDeploymentConfig(
                gameId = "skyrim_le",
                displayName = "Skyrim Legendary Edition",
                targetDataPath = targetDataPath,
                realDeployEnabled = true,
                targetRootPath = targetRootPath
            )
        }

        private fun scopedResult(): ScopedDeploymentResult {
            return ScopedDeploymentResult(
                dataResult = DeploymentResult(
                    addCount = 2,
                    removeCount = 1,
                    updateCount = 3,
                    finalRecordCount = 9,
                    backupCount = 1,
                    restoreCount = 2,
                    protectedConflictCount = 3
                ),
                rootResult = DeploymentResult(
                    addCount = 4,
                    removeCount = 2,
                    updateCount = 1,
                    finalRecordCount = 7,
                    backupCount = 2,
                    restoreCount = 1,
                    protectedConflictCount = 1
                )
            )
        }

        private fun preflightException(): DeploymentPreflightException {
            return DeploymentPreflightException(
                DeploymentPreflightResult(
                    issues = listOf(
                        DeploymentPreflightIssue(
                            severity = DeploymentPreflightSeverity.ERROR,
                            title = "Data target is not selected.",
                            details = "Pick the game's Data folder before real deploy."
                        )
                    )
                )
            )
        }
    }
}
