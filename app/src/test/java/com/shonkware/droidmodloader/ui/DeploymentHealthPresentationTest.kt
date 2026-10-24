package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.attention.*
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.health.*
import org.junit.Assert.*
import org.junit.Test

class DeploymentHealthPresentationTest {
    private fun health() = DeploymentHealthProjector.project(DeploymentHealthFacts(
        "profile-a", "fallout_nv", GameTargetType.entries.map {
            DeploymentTargetHealth(it, DeploymentTargetIdentity("fallout_nv", "real_path", "/fixture/${it.name}"),
                TargetReadiness.READY, 0)
        }, DeploymentRecoveryFact.NONE,
        DeploymentIntegrityEvidence(DeploymentIntegrityFact.MATCHED_AT_CHECK, 1, "digest", checked = 2), true, 2
    ))

    private fun state() = MutableMainActivityUiState().apply {
        activeProfileId = "profile-a"
        selectedGameId = "fallout_nv"
        setupComplete = true
        deploymentHealth = health()
        deploymentHealthContext = deploymentHealthContextKey()
    }

    @Test fun `current projection preserves evidence and rejects changed profile game or target`() {
        val state = state()
        assertEquals(DeploymentHealthPhase.MATCHED_AT_CHECK, state.currentDeploymentHealth().phase)
        state.activeProfileId = "profile-b"
        val other = state.currentDeploymentHealth()
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, other.phase)
        assertTrue(other.targets.all { it.identity == null })
        assertEquals(DeploymentIntegrityFact.UNVERIFIED, other.integrity.fact)
        state.activeProfileId = "profile-a"
        state.selectedGameId = "oblivion"
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, state.currentDeploymentHealth().phase)
        state.selectedGameId = "fallout_nv"
        state.targetPathText = "/new/Data"
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, state.currentDeploymentHealth().phase)
    }

    @Test fun `busy projection and explicit invalidation cannot present old match as current`() {
        val state = state()
        state.operationInProgress = true
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, state.currentDeploymentHealth().phase)
        state.operationInProgress = false
        state.deploymentHealth = DeploymentHealthProjector.invalidate(state.deploymentHealth!!)
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, state.currentDeploymentHealth().phase)
        assertEquals(DeploymentIntegrityFact.STALE, state.currentDeploymentHealth().integrity.fact)
    }

    @Test fun `attention uses the same projection with only supported journal actions`() {
        val health = health().let { health ->
            DeploymentHealthProjector.project(DeploymentHealthFacts(health.profileId, health.gameId,
                health.targets.map { it.copy(readiness = TargetReadiness.UNAVAILABLE) },
                DeploymentRecoveryFact.FAILED, health.integrity, false, 2, installRecoveryRequired = true))
        }
        val result = AppAttentionProjector.project(AppAttentionProjectionInput(true, "profile-a", "fallout_nv", health))
        assertEquals(setOf(AppAttentionKind.DEPLOY_RECOVERY, AppAttentionKind.INSTALL_RECOVERY,
            AppAttentionKind.GAME_INSTALLATION_RESELECTION), result.map { it.kind }.toSet())
        assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY), result.first { it.kind == AppAttentionKind.DEPLOY_RECOVERY }.actions)
        val cleared = AppAttentionProjector.project(AppAttentionProjectionInput(true, "profile-a", "fallout_nv", health()))
        assertTrue(cleared.isEmpty())
    }

    @Test fun `stale cache cannot suppress a newly observed unfinished journal warning`() {
        val state = state()
        state.deploymentHealth = DeploymentHealthProjector.invalidate(state.deploymentHealth!!)
        state.deployRecoveryAttentionRequired = true
        state.deployRecoveryAttentionProfileId = "profile-a"
        state.deployRecoveryAttentionGameId = "fallout_nv"
        state.deployRecoveryWarningText = "Newly observed journal details"
        val health = state.currentDeploymentHealth()
        assertEquals("Newly observed journal details", health.recoveryDetails)
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, health.phase)
        assertEquals(DeploymentRecoveryFact.UNFINISHED, health.recovery)
        val attention = AppAttentionProjector.project(AppAttentionProjectionInput(true, "profile-a", "fallout_nv", health))
        assertTrue(attention.any { it.kind == AppAttentionKind.DEPLOY_RECOVERY })
    }

    @Test fun `failed journal recovery dialog receives the same nonempty projection as dashboard and attention`() {
        val state = state()
        val previous = state.deploymentHealth!!
        state.deploymentHealth = DeploymentHealthProjector.project(DeploymentHealthFacts(
            previous.profileId, previous.gameId, previous.targets, DeploymentRecoveryFact.FAILED,
            previous.integrity, true, 2, recoveryDetails = "Failed operation fixture-operation"
        ))
        assertEquals("", state.deployRecoveryWarningText)
        val dashboard = state.toDashboardUiState(false, false, DirectFolderSelectionUiState(
            true, false, "Fixture", false, com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState()
        ))
        assertEquals(dashboard.deploymentHealth!!.toDebugSummary(), dashboard.deployRecoveryWarningText)
        assertTrue(dashboard.deployRecoveryWarningText.contains("Failed operation fixture-operation"))
        assertFalse(dashboard.deployRecoveryAttentionRequired)
        assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY),
            dashboard.appAttention.single { it.kind == AppAttentionKind.DEPLOY_RECOVERY }.actions)
    }

    @Test fun `install recovery remains scoped and cannot disappear behind matched deployment evidence`() {
        val state = state()
        state.installRecoveryAttentionRequired = true
        state.installRecoveryAttentionProfileId = "profile-a"
        state.installRecoveryAttentionGameId = "fallout_nv"
        val result = state.currentDeploymentHealth()
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, result.phase)
        assertTrue(DeploymentHealthReason.INSTALL_RECOVERY_REQUIRED in result.reasons)
        state.installRecoveryAttentionProfileId = "other"
        assertEquals(DeploymentHealthPhase.MATCHED_AT_CHECK, state.currentDeploymentHealth().phase)
    }
}
