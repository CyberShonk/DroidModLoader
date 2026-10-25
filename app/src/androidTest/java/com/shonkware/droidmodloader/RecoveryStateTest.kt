package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.attention.*
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.health.*
import com.shonkware.droidmodloader.engine.deploy.journal.*
import com.shonkware.droidmodloader.engine.install.*
import com.shonkware.droidmodloader.ui.AppAttentionCard
import com.shonkware.droidmodloader.ui.RecoveryToolsCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Disposable app-owned fixtures and explicitly synthetic component state, never real game targets. */
class RecoveryStateTest {
    @get:Rule val compose = createComposeRule()

    private fun withEngine(test: (File, ModEngine) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "recovery-fixture-").toFile()
        val state = File(root, "profile").apply { mkdirs() }
        val engine = ModEngine(context, File(root, "temp"), File(root, "mods"),
            File(state, "mods.json"), File(state, "manifest.json"), File(root, "deploy/Data"),
            File(state, "config.json"), File(state, "plugins.json"), File(state, "plugins.txt"),
            File(state, "loadorder.txt"), File(root, "archives"), File(state, "archives.json"))
        try { test(root, engine) } finally { check(root.deleteRecursively()) }
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes().toList() }

    @Test fun missingInstallBackupBlocksBothDeployActionsWithoutChangingEvidence() = withEngine { root, engine ->
        val mods = File(root, "mods").apply { mkdirs() }
        val transaction = InstallReplacementTransaction("fixture", InstallReplacementState.BACKUP_CREATED,
            "ExampleMod", "_installing_ExampleMod_fixture", "_dml_backup_ExampleMod_fixture", true)
        File(mods, transaction.stagedDirectoryName).mkdirs()
        InstallReplacementTransactionStore().create(mods, transaction)
        val before = snapshot(root)
        val health = engine.buildDeploymentHealth("profile", "fallout_nv", null)
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, health.phase)
        assertEquals(false, health.installRecoveryFacts.single().backupPresent)
        assertTrue(health.toDebugSummary().contains("Manual install retry / rollback unavailable"))
        assertThrows(IllegalStateException::class.java) { engine.deployForGame("fallout_nv") }
        assertThrows(IllegalStateException::class.java) { engine.forceFullRedeployForGame("fallout_nv") }
        assertFalse(engine.recoverInterruptedInstallReplacements().single().recovered)
        assertEquals(before, snapshot(root))
        // Restoring evidence lets the existing automatic rules recover, not a new manual action.
        File(mods, transaction.backupDirectoryName).mkdirs()
        assertTrue(engine.recoverInterruptedInstallReplacements().single().recovered)
        assertFalse(engine.buildDeploymentHealth("profile", "fallout_nv", null).installRecoveryRequired)
        assertTrue(engine.recoverInterruptedInstallReplacements().isEmpty())
    }

    @Test fun reviewIsScopedAnnotationAndCannotAuthorizeAnotherDeployment() = withEngine { root, engine ->
        engine.deployForGame("fallout_nv") // simulated, empty disposable target
        val file = File(root, "profile/deployment_journal_fallout_nv.json")
        val repo = DeploymentJournalRepository(file)
        val interrupted = repo.load()!!.copy(operationId = "interrupted-fixture",
            status = DeploymentJournalStatus.STARTED, completedAtEpochMillis = null, resultSummary = null)
        repo.saveStarted(interrupted)
        assertTrue(engine.buildDeploymentHealth("profile", "fallout_nv", null).recoveryCapabilities.reviewAllowed)
        assertTrue(engine.markDeploymentJournalReviewed("fallout_nv"))
        val before = snapshot(root)
        repeat(2) {
            val health = engine.buildDeploymentHealth("profile", "fallout_nv", null)
            assertEquals(DeploymentRecoveryFact.REVIEWED, health.recovery)
            assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, health.phase)
            assertFalse(health.recoveryCapabilities.reviewAllowed)
            assertNotNull(engine.getDeploymentJournalStartupWarning("fallout_nv"))
            assertFalse(engine.markDeploymentJournalReviewed("fallout_nv"))
            assertThrows(IllegalStateException::class.java) { engine.deployForGame("fallout_nv") }
            assertThrows(IllegalStateException::class.java) { engine.forceFullRedeployForGame("fallout_nv") }
        }
        assertEquals(before, snapshot(root))
        file.writeText("corrupt fixture journal")
        val corrupt = snapshot(root)
        assertEquals(DeploymentRecoveryFact.UNKNOWN, engine.buildDeploymentHealth("profile", "fallout_nv", null).recovery)
        assertThrows(java.io.IOException::class.java) { engine.markDeploymentJournalReviewed("fallout_nv") }
        assertThrows(java.io.IOException::class.java) { engine.deployForGame("fallout_nv") }
        assertEquals(corrupt, snapshot(root))
    }

    @Test fun syntheticRecoveryComponentsKeepReviewAndDismissalSeparateFromRepair() {
        val facts = DeploymentHealthFacts("synthetic-profile", "fallout_nv", GameTargetType.entries.map {
            DeploymentTargetHealth(it, null, TargetReadiness.UNKNOWN, null)
        }, DeploymentRecoveryFact.UNFINISHED, DeploymentIntegrityEvidence(), null, 1,
            recoveryCapabilities = DeploymentRecoveryCapabilities(true, true))
        var health by mutableStateOf(DeploymentHealthProjector.project(facts))
        var dismissed by mutableStateOf(false)
        var reviews = 0
        var mutations = 0
        fun attention() = AppAttentionProjector.project(AppAttentionProjectionInput(true,
            "synthetic-profile", "fallout_nv", health)).single()
        fun review() {
            reviews++
            health = DeploymentHealthProjector.project(facts.copy(recovery = DeploymentRecoveryFact.REVIEWED))
        }
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!dismissed) AppAttentionCard(attention(), onAction = {
                    if (it == AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED) review()
                }, onDismissForSession = { dismissed = true })
                RecoveryToolsCard(false, health.recoveryCapabilities.reviewAllowed,
                    health.recovery != DeploymentRecoveryFact.NONE, {}, ::review, { mutations++ }, {})
            }
        }
        compose.onNodeWithText("Mark Reviewed").performScrollTo().performClick()
        compose.onNodeWithText("Mark Reviewed").assertDoesNotExist()
        compose.onNodeWithText("Mark Warning Reviewed").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Force Full Redeploy").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, reviews)
            assertEquals(0, mutations)
            assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, health.phase)
            assertEquals(AppAttentionKind.DEPLOY_RECOVERY, attention().kind)
        }
        compose.onNodeWithText("Review recorded state without changing game files. Marking reviewed is an annotation, not repair, and does not unblock deployment.")
            .performScrollTo().assertIsDisplayed()
    }
}
