package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import com.shonkware.droidmodloader.ui.InfoPopover
import com.shonkware.droidmodloader.ui.InfoPopoverHost
import com.shonkware.droidmodloader.ui.ReportCard
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real pointer/window behavior on synthetic production components; no game files or permissions. */
class InfoPopoverTest {
    @get:Rule val compose = createComposeRule()
    private val explanation = "Short explanatory text. Full folder paths are shared."

    @Test fun repeatedIconTapClosesAndOutsideTapAlsoRunsItsAction() {
        var calls = 0
        compose.setContent {
            DmlTheme {
                InfoPopoverHost(Modifier.fillMaxSize()) {
                    Column {
                        InfoPopover(explanation, "More information")
                        Spacer(Modifier.height(200.dp))
                        Button(onClick = { calls++ }) { Text("Continue work") }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("More information").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.onNodeWithContentDescription("More information").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onNodeWithContentDescription("More information").performTouchInput { click() }
        compose.onNodeWithText("Continue work").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun actualScrollDismissesWithoutConsumingTheGesture() {
        var scrollValue = { 0 }
        compose.setContent {
            val scroll = rememberScrollState()
            scrollValue = { scroll.value }
            DmlTheme {
                InfoPopoverHost(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(scroll).testTag("scroll")) {
                        InfoPopover(explanation, "More information")
                        Spacer(Modifier.height(1800.dp))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("More information").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.runOnIdle { assertTrue("The same gesture must scroll the screen", scrollValue() > 0) }
    }

    @Test fun scrollStartingOnExplanationAlsoDismissesAndContinues() {
        var scrollValue = { 0 }
        var touchSlop = 0f
        compose.setContent {
            val scroll = rememberScrollState()
            touchSlop = LocalViewConfiguration.current.touchSlop
            scrollValue = { scroll.value }
            DmlTheme {
                InfoPopoverHost(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).testTag("explanationScroll")) {
                        Spacer(Modifier.height(240.dp))
                        InfoPopover(explanation, "More information")
                        Spacer(Modifier.height(1800.dp))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("More information").performTouchInput { click() }
        val explanationNode = compose.onNodeWithText(explanation).assertIsDisplayed()
        val node = explanationNode.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val start = compose.runOnIdle {
            val coordinates = node.layoutInfo.coordinates
            coordinates.localToScreen(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        }
        val distance = maxOf(120f, touchSlop * 4)
        val end = start - Offset(0f, distance)
        val scrollNode = compose.onNodeWithTag("explanationScroll").fetchSemanticsNode()
        val viewport = compose.runOnIdle {
            Rect(scrollNode.layoutInfo.coordinates.localToScreen(Offset.Zero), scrollNode.boundsInRoot.size)
        }
        assertTrue("Native swipe must begin and end in the scroll viewport: $start -> $end; viewport=$viewport",
            viewport.contains(start) && viewport.contains(end))
        nativeSwipe(start, end)
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("A swipe over the explanation must still scroll; bounds=$bounds; touchSlop=$touchSlop; scroll=${scrollValue()}",
                scrollValue() > 0)
        }
    }

    /** Native injection exercises Android window routing; Compose-local dispatch bypasses window flags. */
    private fun nativeSwipe(start: Offset, end: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, point: Offset) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native input injection failed", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, start)
        for (step in 1..12) inject(MotionEvent.ACTION_MOVE, start + (end - start) * (step / 12f))
        inject(MotionEvent.ACTION_UP, end)
        compose.waitForIdle()
    }

    @Test fun backDismissesWithoutLeavingTheScreen() {
        compose.setContent {
            DmlTheme { InfoPopoverHost { InfoPopover(explanation, "More information") } }
        }
        compose.onNodeWithContentDescription("More information").performClick()
        compose.onNodeWithText(explanation).assertIsDisplayed()
        pressBack()
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onNodeWithContentDescription("More information").assertIsDisplayed()
    }

    @Test fun navigationRemovalDoesNotReopenPopover() {
        var shown by mutableStateOf(true)
        compose.setContent {
            DmlTheme {
                InfoPopoverHost {
                    if (shown) InfoPopover(explanation, "More information")
                }
            }
        }
        compose.onNodeWithContentDescription("More information").performClick()
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.runOnIdle { shown = false }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.runOnIdle { shown = true }
        compose.onNodeWithText(explanation).assertDoesNotExist()
    }

    @Test fun recreationDoesNotRestoreAnOpenPopover() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DmlTheme { InfoPopoverHost { InfoPopover(explanation, "More information") } }
        }
        compose.onNodeWithContentDescription("More information").performClick()
        compose.onNodeWithText(explanation).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onNodeWithContentDescription("More information").assertIsDisplayed()
    }

    @Test fun optionalTimeoutRespectsAccessibilityRecommendation() {
        val requests = mutableListOf<Long>()
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
                containsText: Boolean, containsControls: Boolean): Long {
                assertTrue(containsText)
                assertFalse(containsControls)
                requests += originalTimeoutMillis
                return 4000
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                DmlTheme { InfoPopoverHost { InfoPopover(explanation, "More information", autoDismissMillis = 1000) } }
            }
        }
        compose.onNodeWithContentDescription("More information").performClick()
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2500)
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1000L), requests) }
    }

    @Test fun openingAnotherPopoverReplacesTheFirst() {
        compose.setContent {
            DmlTheme {
                InfoPopoverHost {
                    Column {
                        InfoPopover(explanation, "First information")
                        Spacer(Modifier.height(200.dp))
                        InfoPopover("Second explanation", "Second information")
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("First information").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertIsDisplayed()
        compose.onNodeWithContentDescription("Second information").performTouchInput { click() }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onNodeWithText("Second explanation").assertIsDisplayed()
    }

    @Test fun shareLogsRemainsOneActionAndItsWarningHasNoTimer() {
        var shares = 0
        compose.setContent {
            DmlTheme {
                InfoPopoverHost {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReportCard("Synthetic log", { shares++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("About shared logs").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Reports include diagnostic information, logs, and full folder paths, which may reveal personal names. Only share with someone you trust. Nothing is uploaded automatically.")
            .assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText("Reports include diagnostic information", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, shares) }
        compose.onNodeWithText("Share Logs").performTouchInput { click() }
        compose.onNodeWithText("Reports include diagnostic information", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, shares) }
    }
}
