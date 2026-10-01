package com.shonkware.droidmodloader.attention

data class AppAttentionId(
    val value: String
)

internal object AppAttentionIds {
    fun scoped(
        kind: AppAttentionKind,
        profileId: String,
        gameId: String
    ): AppAttentionId {
        return AppAttentionId(
            "${kind.name.lowercase()}:${profileId.trim()}:${gameId.trim()}"
        )
    }
}

enum class AppAttentionKind {
    GAME_INSTALLATION_RESELECTION,
    DEPLOY_RECOVERY,
    INSTALL_RECOVERY
}

enum class AppAttentionSeverity(
    internal val priority: Int
) {
    INFO(0),
    WARNING(1),
    ERROR(2)
}

enum class AppAttentionAction {
    SELECT_GAME_FOLDER,
    VIEW_DEPLOY_RECOVERY,
    MARK_DEPLOY_RECOVERY_REVIEWED
}

data class AppAttention(
    val id: AppAttentionId,
    val kind: AppAttentionKind,
    val severity: AppAttentionSeverity,
    val actions: List<AppAttentionAction>
)
