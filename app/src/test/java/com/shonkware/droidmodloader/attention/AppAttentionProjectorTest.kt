package com.shonkware.droidmodloader.attention

import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.health.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAttentionProjectorTest {
    @Test
    fun `inactive profile session has no attention`() {
        val result = project(
            profileSessionReady = false,
            activeProfileId = null,
            gameInstallationReselectionRequired = true,
            deployRecoveryRequired = true,
            installRecoveryRequiresAttention = true
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `game installation reselection produces one scoped attention item`() {
        val result = project(gameInstallationReselectionRequired = true)

        assertEquals(1, result.size)
        assertEquals(AppAttentionKind.GAME_INSTALLATION_RESELECTION, result.single().kind)
        assertEquals(
            "game_installation_reselection:profile-a:fallout_nv",
            result.single().id.value
        )
        assertEquals(
            listOf(AppAttentionAction.SELECT_GAME_FOLDER),
            result.single().actions
        )
    }

    @Test
    fun `deployment recovery and game reselection coexist in deterministic order`() {
        val first = project(
            gameInstallationReselectionRequired = true,
            deployRecoveryRequired = true
        )
        val second = project(
            gameInstallationReselectionRequired = true,
            deployRecoveryRequired = true
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                AppAttentionKind.DEPLOY_RECOVERY,
                AppAttentionKind.GAME_INSTALLATION_RESELECTION
            ),
            first.map { it.kind }
        )
        assertEquals(
            listOf(
                AppAttentionAction.VIEW_DEPLOY_RECOVERY,
                AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED
            ),
            first.first().actions
        )
    }

    @Test
    fun `install recovery offers read only scoped details`() {
        val result = project(installRecoveryRequiresAttention = true)

        assertEquals(1, result.size)
        assertEquals(AppAttentionKind.INSTALL_RECOVERY, result.single().kind)
        assertEquals(
            "install_recovery:profile-a:fallout_nv",
            result.single().id.value
        )
        assertEquals(AppAttentionSeverity.WARNING, result.single().severity)
        assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY), result.single().actions)
    }

    @Test
    fun `all three attention kinds coexist in deterministic order`() {
        val result = project(
            gameInstallationReselectionRequired = true,
            deployRecoveryRequired = true,
            installRecoveryRequiresAttention = true
        )

        assertEquals(3, result.size)
        assertEquals(
            listOf(
                AppAttentionKind.DEPLOY_RECOVERY,
                AppAttentionKind.GAME_INSTALLATION_RESELECTION,
                AppAttentionKind.INSTALL_RECOVERY
            ),
            result.map { it.kind }
        )
        assertEquals(
            project(
                gameInstallationReselectionRequired = true,
                deployRecoveryRequired = true
            ),
            result.filter { it.kind != AppAttentionKind.INSTALL_RECOVERY }
        )
    }

    @Test
    fun `clean install recovery produces no attention`() {
        assertTrue(project(installRecoveryRequiresAttention = false).isEmpty())
    }

    @Test
    fun `attention identity is scoped to profile and game`() {
        val first = project(
            activeProfileId = "profile-a",
            selectedGameId = "fallout_nv",
            gameInstallationReselectionRequired = true
        ).single()
        val second = project(
            activeProfileId = "profile-b",
            selectedGameId = "fallout_nv",
            gameInstallationReselectionRequired = true
        ).single()
        val third = project(
            activeProfileId = "profile-a",
            selectedGameId = "skyrim_le",
            gameInstallationReselectionRequired = true
        ).single()

        assertTrue(first.id != second.id)
        assertTrue(first.id != third.id)
    }

    @Test
    fun `observed unknown and reviewed journals retain details without repair or review actions`() {
        for (fact in listOf(DeploymentRecoveryFact.UNKNOWN, DeploymentRecoveryFact.REVIEWED)) {
            val health = DeploymentHealthProjector.project(DeploymentHealthFacts("profile-a", "fallout_nv",
                emptyList(), fact, DeploymentIntegrityEvidence(), null, 1))
            val input = AppAttentionProjectionInput(true, "profile-a", "fallout_nv", health)
            val attention = AppAttentionProjector.project(input).single()
            assertEquals(AppAttentionKind.DEPLOY_RECOVERY, attention.kind)
            assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY), attention.actions)
            assertTrue(AppAttentionProjector.project(input.copy(activeProfileId = "profile-b")).isEmpty())
        }
    }

    private fun project(
        profileSessionReady: Boolean = true,
        activeProfileId: String? = "profile-a",
        selectedGameId: String = "fallout_nv",
        gameInstallationReselectionRequired: Boolean = false,
        deployRecoveryRequired: Boolean = false,
        installRecoveryRequiresAttention: Boolean = false
    ): List<AppAttention> {
        return AppAttentionProjector.project(
            AppAttentionProjectionInput(
                profileSessionReady = profileSessionReady,
                activeProfileId = activeProfileId,
                selectedGameId = selectedGameId,
                deploymentHealth = DeploymentHealthProjector.project(DeploymentHealthFacts(
                    activeProfileId.orEmpty(), selectedGameId, GameTargetType.entries.map {
                        DeploymentTargetHealth(it, null, TargetReadiness.UNKNOWN, null, gameInstallationReselectionRequired)
                    }, if (deployRecoveryRequired) DeploymentRecoveryFact.UNFINISHED else DeploymentRecoveryFact.NONE,
                    DeploymentIntegrityEvidence(), null, 0, installRecoveryRequired = installRecoveryRequiresAttention,
                    recoveryCapabilities = DeploymentRecoveryCapabilities(reviewAllowed = deployRecoveryRequired, recordedTargetsMatch = true)
                ))
            )
        )
    }
}
