package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.attention.AppAttentionId
import com.shonkware.droidmodloader.attention.AppAttentionKind
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryAction
import com.shonkware.droidmodloader.engine.install.InstallReplacementRecoveryResult
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.ui.workflow.InstallReplacementRecoveryEngine
import com.shonkware.droidmodloader.ui.workflow.InstallReplacementStartupWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUiStateInstallationProjectionTest {

    @Test
    fun `fresh installation state stores raw absence and projects clear status text`() {
        val state = MutableMainActivityUiState()

        assertEquals("", state.newProfileRootPathText)
        assertEquals("", state.newProfileDataPathText)
        assertEquals("", state.selectedRootPathText)
        assertEquals("", state.selectedDataPathText)

        val dashboard = project(state)

        assertEquals("No folder selected", dashboard.selectedRootPathText)
        assertEquals("Not detected yet", dashboard.selectedDataPathText)
        assertFalse(dashboard.setupInstallationResolved)
        assertFalse(dashboard.newProfileInstallationResolved)
        assertFalse(dashboard.rootTargetReady)
        assertFalse(dashboard.dataTargetReady)
        assertTrue(dashboard.appAttention.isEmpty())
    }

    @Test
    fun `resolved setup readiness is derived from the root and data pair`() {
        val state = MutableMainActivityUiState().apply {
            setupRootTargetPathText = "/games/FNV"
            setupTargetPathText = "/games/FNV/Data"
            newProfileRootPathText = "/games/FNV"
            newProfileDataPathText = "/games/FNV/Data"
        }

        val dashboard = project(state)

        assertTrue(dashboard.setupInstallationResolved)
        assertTrue(dashboard.newProfileInstallationResolved)
    }

    @Test
    fun `active target readiness respects reselection flags`() {
        val state = MutableMainActivityUiState().apply {
            rootTargetPathText = "/games/FNV"
            targetPathText = "/games/FNV/Data"
            rootPathReselectionRequired = true
            dataPathReselectionRequired = true
        }

        val dashboard = project(state)

        assertFalse(dashboard.rootTargetReady)
        assertFalse(dashboard.dataTargetReady)
    }

    @Test
    fun `active profile reselection projects one game installation attention condition`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
            rootPathReselectionRequired = true
            dataPathReselectionRequired = true
        }

        val dashboard = project(state)

        assertEquals(1, dashboard.appAttention.size)
        assertEquals(
            AppAttentionKind.GAME_INSTALLATION_RESELECTION,
            dashboard.appAttention.single().kind
        )
    }

    @Test
    fun `deployment recovery attention is scoped to active profile and game`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
            deployRecoveryAttentionRequired = true
            deployRecoveryAttentionProfileId = "profile-a"
            deployRecoveryAttentionGameId = "fallout_nv"
        }

        assertEquals(
            AppAttentionKind.DEPLOY_RECOVERY,
            project(state).appAttention.single().kind
        )

        state.activeProfileId = "profile-b"

        assertTrue(project(state).appAttention.isEmpty())
        assertFalse(project(state).deployRecoveryAttentionRequired)
    }

    @Test
    fun `install recovery attention remains scoped to the evaluated profile and game`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
        }
        state.updateInstallRecoveryAttention("profile-a", "fallout_nv", true)
        val attention = project(state).appAttention.single()

        assertEquals(AppAttentionKind.INSTALL_RECOVERY, attention.kind)
        assertEquals("install_recovery:profile-a:fallout_nv", attention.id.value)

        state.dismissedAttentionIds = setOf(attention.id)
        assertEquals(listOf(attention), project(state).appAttention)

        state.activeProfileId = "profile-b"
        assertTrue(project(state).appAttention.isEmpty())

        state.activeProfileId = "profile-a"
        state.selectedGameId = "skyrim_le"
        assertTrue(project(state).appAttention.isEmpty())

        state.selectedGameId = "fallout_nv"
        assertEquals(listOf(attention), project(state).appAttention)
    }

    @Test
    fun `clean recovery evaluation clears the stored signal scope and session dismissal`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
        }
        val engine = object : InstallReplacementRecoveryEngine {
            var action: InstallReplacementRecoveryAction? = null

            override fun recoverInterruptedInstallReplacements() = listOf(
                InstallReplacementRecoveryResult("transaction.properties", action, null)
            )
        }
        val workflow = InstallReplacementStartupWorkflow(
            appendLog = {},
            appendError = {},
            onInstallRecoveryAttention = state::updateInstallRecoveryAttention
        )
        workflow.checkStartup(engine, "profile-a", "fallout_nv")
        assertTrue(state.installRecoveryAttentionRequired)
        assertEquals("profile-a", state.installRecoveryAttentionProfileId)
        assertEquals("fallout_nv", state.installRecoveryAttentionGameId)
        val attentionId = project(state).appAttention.single().id
        state.dismissedAttentionIds = setOf(attentionId)
        state.suppressedAttentionPromptIds = setOf(attentionId)

        // A clean result still clears A's stored condition after switching away from A.
        state.activeProfileId = "profile-b"
        engine.action = InstallReplacementRecoveryAction.RESTORED_PREVIOUS_INSTALL
        workflow.checkStartup(engine, "profile-a", "fallout_nv")

        assertFalse(state.installRecoveryAttentionRequired)
        assertNull(state.installRecoveryAttentionProfileId)
        assertNull(state.installRecoveryAttentionGameId)
        assertTrue(state.dismissedAttentionIds.isEmpty())
        assertTrue(state.suppressedAttentionPromptIds.isEmpty())
        state.activeProfileId = "profile-a"
        assertTrue(project(state).appAttention.isEmpty())
    }

    @Test
    fun `queued result for old scope cannot overwrite current scope recovery attention`() {
        val newScopes = listOf("profile-b" to "fallout_nv", "profile-a" to "skyrim_le")
        for ((profileId, gameId) in newScopes) {
            for (lateRequiresAttention in listOf(false, true)) {
                val state = MutableMainActivityUiState().apply {
                    setupComplete = true
                    activeProfileId = "profile-a"
                    selectedGameId = "fallout_nv"
                }
                state.updateInstallRecoveryAttention("profile-a", "fallout_nv", true)
                val oldId = project(state).appAttention.single().id
                val queuedPublication = {
                    state.updateInstallRecoveryAttention("profile-a", "fallout_nv", lateRequiresAttention)
                }

                state.activeProfileId = profileId
                state.selectedGameId = gameId
                state.updateInstallRecoveryAttention(profileId, gameId, true)
                val currentAttention = project(state).appAttention
                val currentId = currentAttention.single().id
                state.dismissedAttentionIds = setOf(oldId, currentId)
                state.suppressedAttentionPromptIds = setOf(oldId, currentId)

                queuedPublication()

                assertTrue(state.installRecoveryAttentionRequired)
                assertEquals(profileId, state.installRecoveryAttentionProfileId)
                assertEquals(gameId, state.installRecoveryAttentionGameId)
                assertEquals(currentAttention, project(state).appAttention)
                val expectedDismissals = if (lateRequiresAttention) {
                    setOf(oldId, currentId)
                } else {
                    setOf(currentId)
                }
                assertEquals(expectedDismissals, state.dismissedAttentionIds)
                assertEquals(expectedDismissals, state.suppressedAttentionPromptIds)
            }
        }
    }

    @Test
    fun `install recovery attention requires both stored scope identities`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
            installRecoveryAttentionRequired = true
        }

        assertTrue(project(state).appAttention.isEmpty())

        state.installRecoveryAttentionProfileId = "profile-a"
        assertTrue(project(state).appAttention.isEmpty())

        state.installRecoveryAttentionProfileId = null
        state.installRecoveryAttentionGameId = "fallout_nv"
        assertTrue(project(state).appAttention.isEmpty())
    }

    @Test
    fun `another profile install recovery does not change existing attention kinds`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-b"
            selectedGameId = "fallout_nv"
            rootPathReselectionRequired = true
            deployRecoveryAttentionRequired = true
            deployRecoveryAttentionProfileId = "profile-b"
            deployRecoveryAttentionGameId = "fallout_nv"
        }
        val originalAttention = project(state).appAttention

        state.installRecoveryAttentionRequired = true
        state.installRecoveryAttentionProfileId = "profile-a"
        state.installRecoveryAttentionGameId = "fallout_nv"

        assertEquals(2, originalAttention.size)
        assertEquals(originalAttention, project(state).appAttention)
    }

    @Test
    fun `session suppression does not remove authoritative projected attention`() {
        val state = MutableMainActivityUiState().apply {
            setupComplete = true
            activeProfileId = "profile-a"
            selectedGameId = "fallout_nv"
            rootPathReselectionRequired = true
        }
        val first = project(state)
        val attentionId = first.appAttention.single().id

        state.suppressedAttentionPromptIds = setOf(attentionId)
        state.dismissedAttentionIds = setOf(AppAttentionId("other"))

        val second = project(state)

        assertEquals(first.appAttention, second.appAttention)
        assertEquals(setOf(attentionId), second.suppressedAttentionPromptIds)
    }

    private fun project(state: MutableMainActivityUiState): DashboardUiState {
        return state.toDashboardUiState(
            secondScreenEnabled = false,
            allFilesAccessRequired = false,
            directFolderState = directFolderState()
        )
    }

    private fun directFolderState(): DirectFolderSelectionUiState {
        return DirectFolderSelectionUiState(
            allFilesAccessGranted = true,
            showBrowser = false,
            browserTitle = "Choose Folder",
            browserRequiresWritable = false,
            browserState = DirectFolderBrowserState()
        )
    }
}
