package com.shonkware.droidmodloader

import android.os.Looper
import android.os.Build
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.install.ArchiveExtractionFacts
import com.shonkware.droidmodloader.engine.install.ArchiveFormat
import com.shonkware.droidmodloader.engine.install.ArchiveInspection
import com.shonkware.droidmodloader.engine.install.ArchiveReadException
import com.shonkware.droidmodloader.engine.install.ArchiveReadFailureCode
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstaller
import com.shonkware.droidmodloader.ui.ArchiveLibraryPanelDialog
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiItem
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

/** Owned cache fixtures and production components, not real target or full MainActivity proof. */
class ArchiveInspectionRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun startInPortrait() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
    }

    @Test fun ordinaryPreparationKeepsFactsAndCleansTemporaryData() = withFixture { root ->
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val installer = PreparedArchiveInstaller(File(root, "temp"), File(root, "mods"))
        val source = zip(root, "normal.zip", "Data/mod.esp")
        val prepared = installer.prepare(source)
        try {
            val facts = checkNotNull(prepared.inspection)
            assertEquals(InstallerType.SIMPLE, facts.installerType)
            assertEquals(1, facts.extraction.completedFiles)
            assertEquals(7L, facts.extraction.expandedBytes)
            assertEquals("Data", prepared.plan.groups.single().options.single().sourcePath)
            assertEquals("payload", File(prepared.installRootPath, "Data/mod.esp").readText())
        } finally { installer.cancel(prepared) }
        val unusual = installer.prepare(zip(root, "unusual.zip", "unusual.dat"))
        assertEquals(InstallerType.MANUAL_DATA_FOLDER, unusual.plan.installerType)
        assertTrue(unusual.plan.requiresUserChoice)
        assertNotNull(unusual.manualLayoutInventory)
        assertThrows(java.io.IOException::class.java) {
            installer.finalizeInstall(unusual, com.shonkware.droidmodloader.engine.install.InstallerSelection(emptySet()))
        }
        installer.cancel(unusual)
        assertTrue(File(root, "temp/installer_sessions").list().orEmpty().isEmpty())
        assertFalse(File(root, "mods").exists())
        assertTrue(source.isFile)
    }

    @Test fun unsafeArchiveRefusalPreservesExistingFixtureInstallation() = withFixture { root ->
        val marker = File(root, "mods/Existing/keep.esp").apply { parentFile!!.mkdirs(); writeText("unchanged") }
        val installer = PreparedArchiveInstaller(File(root, "temp"), File(root, "mods"))
        for ((index, path) in listOf("../outside", "C:/outside").withIndex()) {
            val failure = assertThrows(java.io.IOException::class.java) {
                installer.prepare(zip(root, "unsafe-$index.zip", path))
            }
            assertTrue(failure.cause is ArchiveReadException)
            val readFailure = failure.cause as ArchiveReadException
            // Android 14+ rejects traversal before DML's own guard; Windows paths reach DML.
            val platformRefusal = index == 0 && Build.VERSION.SDK_INT >= 34
            assertEquals(if (platformRefusal) ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED
                else ArchiveReadFailureCode.UNSAFE_ENTRY_PATH, readFailure.code)
            if (platformRefusal) {
                val zipFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ZipException>().single()
                assertTrue(zipFailure.message.orEmpty().contains(path))
            }
            assertEquals("unchanged", marker.readText())
            assertFalse(root.walkTopDown().any { it.name == "outside" })
            assertTrue(File(root, "temp/installer_sessions").list().orEmpty().isEmpty())
        }
    }

    @Test fun libraryShowsUsefulRowsAndOnlyInstallStartsWork() {
        var installs = 0
        compose.setContent {
            DmlTheme {
                ArchiveLibraryPanelDialog(browserState(), false, false, "", rememberLazyListState(), {}, {}, {},
                    { installs++ }, {}, {})
            }
        }
        compose.onNodeWithText("Inspect…").assertDoesNotExist()
        compose.onNodeWithText("fixture.zip").assertIsDisplayed()
        compose.onNodeWithText("ZIP • 20 B").assertIsDisplayed()
        compose.onNodeWithText("Version: 1.2").assertIsDisplayed()
        compose.onNodeWithText("Nexus game: falloutnewvegas").assertIsDisplayed()
        compose.onNodeWithText("Install").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun installAttemptDetailsDoNotStartWorkAndBusyControlsRemainCancellable() {
        var cancellations = 0
        var installs = 0
        val report = ArchiveInspectionReport("fixture.zip", 0,
            ArchiveInspection(ArchiveExtractionFacts("synthetic-snapshot", 0, ArchiveFormat.ZIP,
                false, 20, 1, 1, 7), InstallerType.SIMPLE))
        compose.setContent {
            DmlTheme {
                ArchiveLibraryPanelDialog(browserState().copy(inspection = report), true, true, "", rememberLazyListState(),
                    {}, {}, {}, { installs++ }, { cancellations++ }, {})
            }
        }
        compose.onNode(hasScrollToIndexAction()).assertHeightIsAtLeast(1.dp)
        repeat(2) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Archive Details"))
            compose.onNodeWithText("Archive Details").assertIsDisplayed().performClick()
            compose.onNodeWithText("Encrypted: unknown").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Install"))
        compose.onNodeWithText("Inspect…").assertDoesNotExist()
        compose.onNodeWithText("Install").assertIsNotEnabled()
        compose.onNodeWithText("Cancel Import").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, installs); assertEquals(1, cancellations) }
    }

    @Test fun landscapeRowsScrollWhileCancellationStaysVisible() {
        var cancellations = 0
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.setContent {
            DmlTheme {
                ArchiveLibraryPanelDialog(browserState(), true, true, "", rememberLazyListState(),
                    {}, {}, {}, {}, { cancellations++ }, {})
            }
        }
        compose.onNode(hasScrollToIndexAction()).assertHeightIsAtLeast(1.dp)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Install"))
        compose.onNodeWithText("Install").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cancel Import").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    private fun browserState() = ArchiveBrowserUiState(folderPath = "/synthetic/archives", folderName = "Fixtures",
        items = listOf(ArchiveBrowserUiItem("fixture", "/synthetic/archives/fixture.zip", "Fixture", "fixture.zip",
            "zip", 20, 0, ArchiveBrowserItemStatus.NEVER_INSTALLED, version = "1.2", nexusGameDomain = "falloutnewvegas")))

    private fun zip(root: File, name: String, path: String): File = File(root, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write("payload".toByteArray())
            zip.closeEntry()
        }
    }

    private fun withFixture(action: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "archive-library-runtime-${UUID.randomUUID()}")
        check(root.mkdir())
        try { action(root) } finally { assertTrue("Owned fixture cleanup failed", root.deleteRecursively()) }
    }
}
