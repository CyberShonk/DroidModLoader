package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessState
import com.shonkware.droidmodloader.ui.ReportCard
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import com.shonkware.droidmodloader.ui.workflow.AppDiagnosticInfo
import com.shonkware.droidmodloader.ui.workflow.EnvironmentDiagnostics
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic production-component fixtures, not MainActivity or real installation proof. */
class EnvironmentDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionPublishesHistoricalUnavailableEvidenceInScrollableLog() {
        var inspections = 0
        var log by mutableStateOf("")
        val summary = EnvironmentDiagnostics.inspect(
            OperationContext("synthetic", "fallout_nv", false, "", "", unavailable = true),
            AppDiagnosticInfo("synthetic", 1, "fixture", "16", "fixture", 36),
            AllFilesAccessState.DENIED, observedAtMillis = 0
        ).toSummary()
        compose.setContent {
            DmlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReportCard(log, {}, onInspectEnvironment = { inspections++; log = summary })
                }
            }
        }
        compose.onNodeWithText("Check Game Setup").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, inspections) }
        compose.onNodeWithText(summary).performScrollTo().assertIsDisplayed()
    }

    @Test fun actionIsDisabledWhileAnOperationOwnsSession() {
        var inspections = 0
        compose.setContent {
            DmlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReportCard("Historical log", {}, operationInProgress = true,
                        onInspectEnvironment = { inspections++ })
                }
            }
        }
        compose.onNodeWithText("Check Game Setup").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, inspections) }
    }
}
