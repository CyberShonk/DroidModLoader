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
                    title = "Game folder needs attention",
                    message = "DML can no longer use the saved game installation location. " +
                        "Select the installed game's main folder again so DML can validate " +
                        "Game Root and detect Data.",
                    primaryAction = attention.actions.firstOrNull {
                        it == AppAttentionAction.SELECT_GAME_FOLDER
                    },
                    primaryActionLabel = "Select Game Folder",
                    secondaryAction = null,
                    secondaryActionLabel = null,
                    dismissibleForSession = false,
                    automaticPrompt = true
                )
            }

            AppAttentionKind.DEPLOY_RECOVERY -> {
                AppAttentionPresentation(
                    title = "Deployment recovery needs attention",
                    message = "Deployment recovery is unresolved or its evidence is unavailable. " +
                        "Review and dismissal do not repair files or unblock deployment. " +
                        "View details for the recorded scope and supported actions.",
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
                    title = "Install recovery needs review",
                    message = "Droid Mod Loader could not automatically recover an " +
                        "interrupted mod installation. Review the state before relying on " +
                        "the affected installation.",
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
