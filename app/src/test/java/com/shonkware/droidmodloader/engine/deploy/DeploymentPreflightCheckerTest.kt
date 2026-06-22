package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlan
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanOperation
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanOperationType
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanScope
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightChecker
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightSeverity
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeploymentPreflightCheckerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writableDirectDataPathPassesTargetPreflight() {
        val dataDir = temporaryFolder.newFolder("Data")

        val result = DeploymentPreflightChecker().check(
            config = config(targetDataPath = dataDir.canonicalPath),
            plan = emptyPlan()
        )

        assertTrue(result.canDeploy)
        assertTrue(result.issues.any { it.title == "Data target is available." })
    }

    @Test
    fun legacyDataSelectionRequiresReselectionBeforeDeploy() {
        val result = DeploymentPreflightChecker().check(
            config = config(
                targetDataPath = "",
                dataPathReselectionRequired = true
            ),
            plan = emptyPlan()
        )

        assertFalse(result.canDeploy)
        assertTrue(
            result.issues.any {
                it.severity == DeploymentPreflightSeverity.ERROR &&
                    it.title == "Data target must be reselected."
            }
        )
    }

    @Test
    fun rootOperationsRequireWritableDirectRootPath() {
        val dataDir = temporaryFolder.newFolder("Data")
        val plan = ScopedDeploymentPlan(
            dataPlan = DeploymentPlan(DeploymentPlanScope.DATA, emptyList()),
            rootPlan = DeploymentPlan(
                scope = DeploymentPlanScope.GAME_ROOT,
                operations = listOf(
                    DeploymentPlanOperation(
                        type = DeploymentPlanOperationType.REMOVE,
                        normalizedPath = "nvse_loader.exe",
                        newRecord = null,
                        oldRecord = null,
                        winningModName = null,
                        sourceSizeBytes = null,
                        reason = "test"
                    )
                )
            )
        )

        val result = DeploymentPreflightChecker().check(
            config = config(
                targetDataPath = dataDir.canonicalPath,
                targetRootPath = ""
            ),
            plan = plan
        )

        assertFalse(result.canDeploy)
        assertTrue(result.issues.any { it.title == "Game Root target is not selected." })
    }

    private fun config(
        targetDataPath: String,
        targetRootPath: String = "",
        dataPathReselectionRequired: Boolean = false,
        rootPathReselectionRequired: Boolean = false
    ) = GameDeploymentConfig(
        gameId = "fallout_new_vegas",
        displayName = "Fallout: New Vegas",
        targetDataPath = targetDataPath,
        realDeployEnabled = true,
        targetRootPath = targetRootPath,
        dataPathReselectionRequired = dataPathReselectionRequired,
        rootPathReselectionRequired = rootPathReselectionRequired
    )

    private fun emptyPlan() = ScopedDeploymentPlan(
        dataPlan = DeploymentPlan(DeploymentPlanScope.DATA, emptyList()),
        rootPlan = DeploymentPlan(DeploymentPlanScope.GAME_ROOT, emptyList())
    )
}
