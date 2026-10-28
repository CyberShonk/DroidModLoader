package com.shonkware.droidmodloader.ui.attention

import com.shonkware.droidmodloader.attention.AppAttention
import com.shonkware.droidmodloader.attention.AppAttentionAction
import com.shonkware.droidmodloader.attention.AppAttentionKind

internal data class AppAttentionPresentation(
    val title: String,
    val message: String,
    val primaryAction: AppAttentionAction?,
    val primaryActionLabel: String?,
    val secondaryAction: AppAttentionAction?,
    val secondaryActionLabel: String?,
    val dismissibleForSession: Boolean,
    val automaticPrompt: Boolean
)

internal object AppAttentionPresentationMapper {
    fun map(attention: AppAttention): AppAttentionPresentation {
        return when (attention.kind) {
            AppAttentionKind.GAME_INSTALLATION_RESELECTION -> {
                AppAttentionPresentation(
                    title = "Choose the game folder again",
                    message = "DML can no longer use the saved game folder. " +
                        "Choose the installed game's main folder again so DML can check it " +
                        "and find its Data folder.",
                    primaryAction = attention.actions.firstOrNull {
                        it == AppAttentionAction.SELECT_GAME_FOLDER
                    },
                    primaryActionLabel = "Choose Game Folder",
                    secondaryAction = null,
                    secondaryActionLabel = null,
                    dismissibleForSession = false,
                    automaticPrompt = true
                )
            }

            AppAttentionKind.DEPLOY_RECOVERY -> {
                AppAttentionPresentation(
                    title = "Unfinished deployment",
                    message = "A deployment still needs review, or DML cannot read its saved record. " +
                        "View the details before continuing. Marking it reviewed or hiding this " +
                        "notification does not repair files or allow another deployment.",
                    primaryAction = attention.actions.firstOrNull {
                        it == AppAttentionAction.VIEW_DEPLOY_RECOVERY
                    },
                    primaryActionLabel = "View Details",
                    secondaryAction = attention.actions.firstOrNull {
                        it == AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED
                    },
                    secondaryActionLabel = "Mark Reviewed",
                    dismissibleForSession = true,
                    automaticPrompt = false
                )
            }

            AppAttentionKind.INSTALL_RECOVERY -> {
                AppAttentionPresentation(
                    title = "Unfinished installation",
                    message = "DML could not finish recovering an interrupted mod installation. " +
                        "The affected mod may be incomplete. Hiding this notification " +
                        "does not fix the installation.",
                    primaryAction = attention.actions.firstOrNull { it == AppAttentionAction.VIEW_DEPLOY_RECOVERY },
                    primaryActionLabel = "View Details".takeIf { AppAttentionAction.VIEW_DEPLOY_RECOVERY in attention.actions },
                    secondaryAction = null,
                    secondaryActionLabel = null,
                    dismissibleForSession = true,
                    automaticPrompt = false
                )
            }
        }
    }
}
