package com.shonkware.droidmodloader.attention

import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryFact
import com.shonkware.droidmodloader.engine.deploy.health.TargetReadiness

internal data class AppAttentionProjectionInput(
    val profileSessionReady: Boolean,
    val activeProfileId: String?,
    val selectedGameId: String,
    val deploymentHealth: DeploymentHealth
)

internal object AppAttentionProjector {
    fun project(input: AppAttentionProjectionInput): List<AppAttention> {
        val profileId = input.activeProfileId?.trim().orEmpty()
        val gameId = input.selectedGameId.trim()
        if (!input.profileSessionReady || profileId.isBlank() || gameId.isBlank()) {
            return emptyList()
        }

        val health = input.deploymentHealth.takeIf { it.isFor(profileId, gameId) } ?: return emptyList()
        val installRecovery = health.installRecoveryRequired
        val deployRecovery = health.recovery in setOf(DeploymentRecoveryFact.UNFINISHED,
            DeploymentRecoveryFact.FAILED, DeploymentRecoveryFact.REVIEWED) ||
            (health.recovery == DeploymentRecoveryFact.UNKNOWN && health.collectedAtEpochMillis > 0)
        val reselection = health.targets.any {
            it.reselectionRequired || it.readiness == TargetReadiness.UNAVAILABLE
        }
        val attention = mutableListOf<AppAttention>()
        if (installRecovery) {
            attention.add(
                AppAttention(
                    id = AppAttentionIds.scoped(
                        AppAttentionKind.INSTALL_RECOVERY,
                        profileId,
                        gameId
                    ),
                    kind = AppAttentionKind.INSTALL_RECOVERY,
                    severity = AppAttentionSeverity.WARNING,
                    actions = listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY)
                )
            )
        }

        if (deployRecovery) {
            attention.add(
                AppAttention(
                    id = AppAttentionIds.scoped(
                        AppAttentionKind.DEPLOY_RECOVERY,
                        profileId,
                        gameId
                    ),
                    kind = AppAttentionKind.DEPLOY_RECOVERY,
                    severity = AppAttentionSeverity.WARNING,
                    actions = if (!health.recoveryCapabilities.reviewAllowed) {
                        listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY)
                    } else listOf(
                        AppAttentionAction.VIEW_DEPLOY_RECOVERY,
                        AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED
                    )
                )
            )
        }

        if (reselection) {
            attention.add(
                AppAttention(
                    id = AppAttentionIds.scoped(
                        AppAttentionKind.GAME_INSTALLATION_RESELECTION,
                        profileId,
                        gameId
                    ),
                    kind = AppAttentionKind.GAME_INSTALLATION_RESELECTION,
                    severity = AppAttentionSeverity.WARNING,
                    actions = listOf(AppAttentionAction.SELECT_GAME_FOLDER)
                )
            )
        }

        return attention.sortedWith(
            compareByDescending<AppAttention> { it.severity.priority }
                .thenBy { kindPriority(it.kind) }
                .thenBy { it.id.value }
        )
    }

    private fun kindPriority(kind: AppAttentionKind): Int {
        return when (kind) {
            AppAttentionKind.DEPLOY_RECOVERY -> 0
            AppAttentionKind.GAME_INSTALLATION_RESELECTION -> 1
            AppAttentionKind.INSTALL_RECOVERY -> 2
        }
    }
}
