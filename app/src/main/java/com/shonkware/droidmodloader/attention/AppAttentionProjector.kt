package com.shonkware.droidmodloader.attention

internal data class AppAttentionProjectionInput(
    val profileSessionReady: Boolean,
    val activeProfileId: String?,
    val selectedGameId: String,
    val gameInstallationReselectionRequired: Boolean,
    val deployRecoveryRequired: Boolean,
    val installRecoveryRequiresAttention: Boolean
)

internal object AppAttentionProjector {
    fun project(input: AppAttentionProjectionInput): List<AppAttention> {
        val profileId = input.activeProfileId?.trim().orEmpty()
        val gameId = input.selectedGameId.trim()
        if (!input.profileSessionReady || profileId.isBlank() || gameId.isBlank()) {
            return emptyList()
        }

        val attention = mutableListOf<AppAttention>()
        if (input.installRecoveryRequiresAttention) {
            attention.add(
                AppAttention(
                    id = AppAttentionIds.scoped(
                        AppAttentionKind.INSTALL_RECOVERY,
                        profileId,
                        gameId
                    ),
                    kind = AppAttentionKind.INSTALL_RECOVERY,
                    severity = AppAttentionSeverity.WARNING,
                    actions = emptyList()
                )
            )
        }

        if (input.deployRecoveryRequired) {
            attention.add(
                AppAttention(
                    id = AppAttentionIds.scoped(
                        AppAttentionKind.DEPLOY_RECOVERY,
                        profileId,
                        gameId
                    ),
                    kind = AppAttentionKind.DEPLOY_RECOVERY,
                    severity = AppAttentionSeverity.WARNING,
                    actions = listOf(
                        AppAttentionAction.VIEW_DEPLOY_RECOVERY,
                        AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED
                    )
                )
            )
        }

        if (input.gameInstallationReselectionRequired) {
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
