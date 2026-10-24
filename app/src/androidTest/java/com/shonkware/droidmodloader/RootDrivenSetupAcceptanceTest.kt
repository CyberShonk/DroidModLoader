package com.shonkware.droidmodloader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Requires approved all-files access and an unfinished setup; never resets app/profile data. */
class RootDrivenSetupAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstSetupValidatesSupportedRootsAndRetainsSelectionAcrossRotation() {
        assertTrue("Grant debug app all-files access before this acceptance test", Environment.isExternalStorageManager())
        compose.onNodeWithText("First Setup").assertIsDisplayed()
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fixture = File(downloads, "PiAndroidGui/V08-03-setup-${UUID.randomUUID()}")
        check(fixture.mkdirs())
        val originalOrientation = compose.activity.requestedOrientation
        try {
            val markers = listOf(
                Triple("skyrim_le", "TESV.exe", "Skyrim.esm"),
                Triple("oblivion", "Oblivion.exe", "Oblivion.esm"),
                Triple("fallout_3", "Fallout3.exe", "Fallout3.esm"),
                Triple("fallout_nv", "FalloutNV.exe", "FalloutNV.esm"),
                Triple("ttw", "FalloutNV.exe", "FalloutNV.esm")
            )
            for ((gameId, executable, master) in markers) {
                val root = File(fixture, gameId)
                check(File(root, "Data").mkdirs())
                File(root, executable).writeText("synthetic marker")
                File(root, "Data/$master").writeText("synthetic marker")
            }
            fun openFolder(name: String) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
                compose.onNode(hasText(name) and hasAnyAncestor(hasScrollToIndexAction())).performClick()
            }
            fun rotate(orientation: Int, expected: Int) {
                compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
                compose.waitForIdle()
            }
            for ((gameId) in markers) {
                val root = File(fixture, gameId).canonicalFile
                compose.onNodeWithText(gameId).performScrollTo().performClick()
                compose.onNodeWithText("Choose Game Folder").performScrollTo().performClick()
                openFolder(Environment.getExternalStorageDirectory().canonicalPath)
                if (gameId == "skyrim_le") {
                    compose.onNodeWithText("Use This Folder").performClick()
                    compose.onNode(hasText("too broad", substring = true)).assertIsDisplayed()
                }
                openFolder(downloads.name)
                openFolder("PiAndroidGui")
                openFolder(fixture.name)
                if (gameId == "fallout_nv") {
                    openFolder("skyrim_le")
                    compose.onNodeWithText("Use This Folder").performClick()
                    compose.onNode(hasText("another game", substring = true)).assertIsDisplayed()
                    compose.onNodeWithText("Up").performClick()
                }
                openFolder(gameId)
                if (gameId == "skyrim_le") {
                    openFolder("Data")
                    compose.onNodeWithText("Use This Folder").performClick()
                    compose.onNode(hasText("looks like Data", substring = true)).assertIsDisplayed()
                    compose.onNodeWithText("Up").performClick()
                }
                if (gameId == "fallout_nv") {
                    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                    compose.onNodeWithText(root.path).assertIsDisplayed()
                    rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                    compose.onNodeWithText(root.path).assertIsDisplayed()
                }
                compose.onNodeWithText("Use This Folder").performClick()
                compose.onNodeWithText("Game folder: ${root.path}").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Data folder: ${File(root, "Data").path}").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Create Profile").performScrollTo().assertIsEnabled()
                if (gameId == "fallout_nv") {
                    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                    compose.onNodeWithText("Data folder: ${File(root, "Data").path}").performScrollTo().assertIsDisplayed()
                    rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                    compose.onNodeWithText("Create Profile").performScrollTo().assertIsEnabled()
                }
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
            // Only this test's newly created, uniquely named tree; no profile or real target cleanup.
            check(fixture.deleteRecursively())
        }
    }
}
