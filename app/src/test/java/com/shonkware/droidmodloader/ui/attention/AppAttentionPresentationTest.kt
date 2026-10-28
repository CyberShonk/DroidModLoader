package com.shonkware.droidmodloader.ui.attention

import com.shonkware.droidmodloader.attention.AppAttention
import com.shonkware.droidmodloader.attention.AppAttentionAction
import com.shonkware.droidmodloader.attention.AppAttentionId
import com.shonkware.droidmodloader.attention.AppAttentionKind
import com.shonkware.droidmodloader.attention.AppAttentionSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAttentionPresentationTest {
    @Test
    fun `game installation attention uses automatic prompt without card dismissal`() {
        val presentation = AppAttentionPresentationMapper.map(
            attention(
                kind = AppAttentionKind.GAME_INSTALLATION_RESELECTION,
                AppAttentionAction.SELECT_GAME_FOLDER
            )
        )

        assertEquals("Choose Game Folder", presentation.primaryActionLabel)
        assertEquals("Choose the game folder again", presentation.title)
        assertEquals(AppAttentionAction.SELECT_GAME_FOLDER, presentation.primaryAction)
        assertNull(presentation.secondaryAction)
        assertNull(presentation.secondaryActionLabel)
        assertTrue(presentation.automaticPrompt)
        assertFalse(presentation.dismissibleForSession)
    }

    @Test
    fun `deploy recovery attention exposes review action and remains session dismissible`() {
        val presentation = AppAttentionPresentationMapper.map(
            attention(
                kind = AppAttentionKind.DEPLOY_RECOVERY,
                AppAttentionAction.VIEW_DEPLOY_RECOVERY,
                AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED
            )
        )

        assertEquals("View Details", presentation.primaryActionLabel)
        assertEquals("Unfinished deployment", presentation.title)
        assertTrue(presentation.message.contains("does not repair files or allow another deployment"))
        assertEquals(AppAttentionAction.VIEW_DEPLOY_RECOVERY, presentation.primaryAction)
        assertEquals("Mark Reviewed", presentation.secondaryActionLabel)
        assertEquals(
            AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED,
            presentation.secondaryAction
        )
        assertFalse(presentation.automaticPrompt)
        assertTrue(presentation.dismissibleForSession)
    }

    @Test
    fun `install recovery attention has no actions and is session dismissible`() {
        val presentation = AppAttentionPresentationMapper.map(
            attention(kind = AppAttentionKind.INSTALL_RECOVERY)
        )

        assertEquals("Unfinished installation", presentation.title)
        assertTrue(presentation.message.contains("does not fix the installation"))
        assertNull(presentation.primaryActionLabel)
        assertNull(presentation.primaryAction)
        assertNull(presentation.secondaryAction)
        assertNull(presentation.secondaryActionLabel)
        assertFalse(presentation.automaticPrompt)
        assertTrue(presentation.dismissibleForSession)
    }

    private fun attention(
        kind: AppAttentionKind,
        vararg actions: AppAttentionAction
    ): AppAttention {
        return AppAttention(
            id = AppAttentionId("test"),
            kind = kind,
            severity = AppAttentionSeverity.WARNING,
            actions = actions.toList()
        )
    }
}
