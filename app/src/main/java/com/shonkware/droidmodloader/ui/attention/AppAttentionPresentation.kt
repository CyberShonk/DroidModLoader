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
                    title = "Previous deploy may need review",
                    message = "Droid Mod Loader found a deploy journal that was not marked " +
                        "completed. Review the previous deployment state before relying on " +
                        "another deploy.",
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
                    primaryAction = null,
                    primaryActionLabel = null,
                    secondaryAction = null,
                    secondaryActionLabel = null,
                    dismissibleForSession = true,
                    automaticPrompt = false
                )
            }
        }
    }
}
