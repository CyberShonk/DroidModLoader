package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileWorkflowControllerTest {

    @Test
    fun `profile draft is captured before background scheduling`() {
        var draft = "profile-a"
        val scheduled = mutableListOf<() -> Unit>()
        val created = mutableListOf<String>()
        val capture: () -> (() -> Unit) = {
            val snapshot = draft
            val action: () -> Unit = { created += snapshot }
            action
        }
        val controller = ProfileWorkflowController(
            runInBackground = scheduled::add,
            prepareFirstSetup = capture,
            prepareAdditionalProfile = capture,
            switchActiveProfile = {},
            deleteProfileAction = {},
            saveDashboardSettings = {}
        )

        controller.completeSetup()
        controller.createProfile()
        draft = "profile-b"
        scheduled.forEach { it() }

        assertEquals(listOf("profile-a", "profile-a"), created)
    }

    @Test
    fun `delete profile routes profile id to delete action exactly once`() {
        var deleteCallCount = 0
        var deletedProfileId: String? = null

        val controller = ProfileWorkflowController(
            runInBackground = { action -> action() },
            prepareFirstSetup = { {} },
            prepareAdditionalProfile = { {} },
            switchActiveProfile = {},
            deleteProfileAction = { profileId ->
                deleteCallCount += 1
                deletedProfileId = profileId
            },
            saveDashboardSettings = {}
        )

        controller.deleteProfile("delete-test-profile")

        assertEquals(1, deleteCallCount)
        assertEquals("delete-test-profile", deletedProfileId)
    }
}
