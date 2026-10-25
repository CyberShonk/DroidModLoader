package com.shonkware.droidmodloader

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.ui.OperationHistoryPresentationMapper
import com.shonkware.droidmodloader.ui.RecentOperationsCard
import com.shonkware.droidmodloader.ui.theme.DmlMatteBackground
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import com.shonkware.droidmodloader.ui.workflow.OperationAttempt
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import com.shonkware.droidmodloader.ui.workflow.OperationHistoryAvailability
import com.shonkware.droidmodloader.ui.workflow.OperationHistorySnapshot
import org.junit.Rule
import org.junit.Test

/** Synthetic presentation only; no startup, profile selection, files, repair or target writes. */
class OperationHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val record = OperationAttempt.start(
        OperationContext("fixture-profile", "skyrim_le", true, "/fixture/Data", "/fixture"), "Preparing fixture"
    ) { _, _, _ -> }.apply { waitForChoices("Choose fixture options") }.snapshot()
    private var history by mutableStateOf(OperationHistorySnapshot(listOf(record)))

    private fun render(activity: ComponentActivity) {
        activity.setContent {
            DmlTheme {
                DmlMatteBackground {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RecentOperationsCard(OperationHistoryPresentationMapper.present(history))
                    }
                }
            }
        }
    }

    @Test fun waitingDetailsAndExpandedIntentSurviveActivityRecreation() {
        compose.activityRule.scenario.onActivity(::render)
        compose.onNodeWithText("Archive install: Waiting for installer choices").assertIsDisplayed()
        compose.onNodeWithContentDescription("Details for attempt ${record.id}").performScrollTo().performClick()
        compose.onNodeWithText("Configured Data: /fixture/Data", substring = true).performScrollTo().assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::render)
        compose.onNodeWithText("Configured Data: /fixture/Data", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Archive install: Succeeded").assertDoesNotExist()
        compose.onNodeWithText("History is not current health or recovery authority.").performScrollTo().assertIsDisplayed()
    }

    @Test fun unsavedAndUnavailableKeepInterruptedOutcomeUnknown() {
        history = OperationHistorySnapshot(listOf(record.copy(interrupted = true)),
            OperationHistoryAvailability.NOT_SAVED, "Fixture write failed")
        compose.activityRule.scenario.onActivity(::render)
        compose.onNodeWithText("History changes are not saved.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Archive install: Interrupted: outcome unknown").assertIsDisplayed()
        compose.runOnIdle { history = history.copy(availability = OperationHistoryAvailability.UNAVAILABLE, reason = "Fixture corrupt") }
        compose.onNodeWithText("Saved history is unavailable.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Archive install: Interrupted: outcome unknown").assertIsDisplayed()
        compose.onNodeWithText("Archive install: Cancelled").assertDoesNotExist()
    }
}
