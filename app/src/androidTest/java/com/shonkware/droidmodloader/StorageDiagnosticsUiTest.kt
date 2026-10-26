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
import com.shonkware.droidmodloader.engine.storage.ManagedArchiveInventory
import com.shonkware.droidmodloader.engine.storage.StorageDiagnostics
import com.shonkware.droidmodloader.ui.ReportCard
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic production-component fixtures, not MainActivity or actual free-space evidence. */
class StorageDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun storageActionPublishesHistoricalUnknownFactsInExistingScrollableLog() {
        var inspections = 0
        var log by mutableStateOf("")
        val summary = "Storage scope: profile=synthetic; game=fallout_nv\n" +
            StorageDiagnostics(0L, emptyList(), ManagedArchiveInventory(details = listOf("Available capacity unknown")), emptyList()).toSummary()
        compose.setContent {
            DmlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReportCard(log, {}, onInspectStorage = { inspections++; log = summary })
                }
            }
        }
        compose.onNodeWithText("Inspect Storage").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, inspections) }
        compose.onNodeWithText(summary).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun storageActionIsDisabledWhileAnOperationOwnsTheSession() {
        var inspections = 0
        compose.setContent {
            DmlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReportCard("Historical log", {}, operationInProgress = true, onInspectStorage = { inspections++ })
                }
            }
        }
        compose.onNodeWithText("Inspect Storage").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, inspections) }
    }
}
