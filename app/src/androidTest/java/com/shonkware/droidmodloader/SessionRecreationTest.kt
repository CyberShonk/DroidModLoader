package com.shonkware.droidmodloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.shonkware.droidmodloader.engine.install.InstallerGroup
import com.shonkware.droidmodloader.engine.install.InstallerGroupType
import com.shonkware.droidmodloader.engine.install.InstallerOption
import com.shonkware.droidmodloader.engine.install.InstallerPlan
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.ui.DroidModLoaderScreen
import com.shonkware.droidmodloader.ui.FullscreenPanel
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A test host avoids production startup, storage permissions, and user profiles. */
class SessionRecreationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var session: SessionViewModel
    private var pendingEngine: PausedInstallerEngine? = null

    private fun render(activity: ComponentActivity) {
        val engine = pendingEngine
        val provider = if (engine == null) ViewModelProvider(activity) else {
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(SessionViewModel(activity.application) { engine })!!
            })
        }
        val model = provider[SessionViewModel::class.java]
        if (::session.isInitialized) assertSame(session, model) else session = model
        val actions = model.buildActions(
            shareLogs = { error("Unexpected sharing") },
            requestAllFilesAccess = { error("Unexpected permission request") }
        )
        activity.setContent {
            DmlTheme { DroidModLoaderScreen(model.dashboardState(), actions) }
        }
    }

    private fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::render)
        compose.waitForIdle()
    }

    @Test
    fun archivePanelAndTerminalStatusSurviveRecreation() {
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.setupComplete = true
            session.activeProfileId = "recreation-test"
            session.fullscreenPanel = FullscreenPanel.ARCHIVES
            session.archiveBrowserState = ArchiveBrowserUiState(folderPath = "/test/archives")
            session.lastOperationStatus = "Import archive failed: test diagnostic."
        }
        compose.onNodeWithText("Archive Library").assertIsDisplayed()
        compose.onNodeWithText("Import archive failed: test diagnostic.").assertIsDisplayed()

        recreate()

        compose.onNodeWithText("Archive Library").assertIsDisplayed()
        compose.onNodeWithText("Import archive failed: test diagnostic.").assertIsDisplayed()
        assertEquals(FullscreenPanel.ARCHIVES, session.fullscreenPanel)
    }

    @Test
    fun pendingInstallerAndSelectedOptionsSurviveRecreation() {
        val prepared = preparedInstall()
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.setupComplete = true
            session.pendingArchiveInstall = prepared
            session.pendingInstallerArchiveRecordId = "record-1"
            session.showInstallerDialog = true
            session.lastOperationStatus = "Choose installer options. (1 ms)"
        }
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()

        recreate()

        compose.onNodeWithText("Install Options").assertIsDisplayed()
        compose.onNodeWithText("Choose installer options. (1 ms)").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
        assertSame(prepared, session.pendingArchiveInstall)
        assertEquals("record-1", session.pendingInstallerArchiveRecordId)
        assertEquals(setOf("optional"), session.pendingInstallerSelectedOptionIds)
    }

    @Test
    fun supportReportIsSingleUseAndDiscardedWhenItsInputsAreSuperseded() {
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            // Separate app-owned fixture scope; no production startup or game target.
            session.activeProfileId = "s2-support-report-fixture"
            session.requestSupportReport()
        }
        compose.waitUntil(5_000) { session.pendingSupportReportFile != null && !session.isOperationAdmitted }
        var exported: File? = null
        compose.runOnIdle {
            exported = session.consumeSupportReport()
            assertNull(session.consumeSupportReport())
            session.requestSupportReport()
        }
        assertTrue(checkNotNull(exported).readText().contains("Droid Mod Loader Diagnostic Summary"))
        compose.waitUntil(5_000) { session.pendingSupportReportFile != null && !session.isOperationAdmitted }
        compose.runOnIdle {
            session.selectedGameId = "fallout_nv"
            assertNull(session.consumeSupportReport())
            session.requestSupportReport()
        }
        compose.waitUntil(5_000) { session.pendingSupportReportFile != null && !session.isOperationAdmitted }
        compose.runOnIdle {
            // Even another operation in the same context invalidates an unconsumed result.
            session.buildActions(shareLogs = {}, requestAllFilesAccess = {}).onViewModFiles("missing-fixture-mod")
            assertNull(session.pendingSupportReportFile)
            assertNull(session.consumeSupportReport())
        }
        compose.waitUntil(5_000) {
            session.logText.contains("Mod not found: missing-fixture-mod") && !session.isOperationAdmitted
        }
    }

    @Test
    fun deleteConfirmationForSupersededProfileGameOrTargetIsRefused() {
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            val actions = session.buildActions(shareLogs = {}, requestAllFilesAccess = {})
            val mod = Mod(id = "never-delete", name = "Test", installPath = "/test/mod",
                enabled = true, priority = 1, modType = ModType.LOOSE)
            for (change in listOf<() -> Unit>(
                { session.activeProfileId = "profile-b" },
                { session.selectedGameId = "fallout_nv" },
                { session.targetPathText = "/other-target" }
            )) {
                session.activeProfileId = "profile-a"
                session.selectedGameId = "skyrim_le"
                session.targetPathText = "/original-target"
                actions.onDeleteMod(mod)
                change()
                session.confirmDelete(mod)
                assertNull(session.pendingDeleteMod)
                assertTrue(session.logText.contains("Ignoring delete confirmation: profile, game or target changed."))
            }
        }
    }

    @Test
    fun savedAttentionBundleDoesNotSerializeOrRecreateLiveInstallerState() {
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.pendingArchiveInstall = preparedInstall()
            session.showInstallerDialog = true
            session.operationInProgress = true
            session.lastOperationStatus = "Installing selected options..."
            val saved = Bundle()
            session.saveAttentionState(saved)
            assertEquals(setOf("dml.dismissed_attention_ids", "dml.suppressed_attention_prompt_ids"), saved.keySet())

            // A new store models a fresh owner, not configuration retention.
            // Do not initialize production startup or replay a pending operation.
            val store = ViewModelStore()
            try {
                val fresh = ViewModelProvider(store,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(activity.application)
                )[SessionViewModel::class.java]
                assertFalse(fresh.operationInProgress)
                assertFalse(fresh.archiveImportInProgress)
                assertNull(fresh.pendingArchiveInstall)
                assertFalse(fresh.showInstallerDialog)
                assertEquals("Ready.", fresh.lastOperationStatus)
            } finally {
                store.clear()
            }
        }
    }

    @Test
    fun pausedInstallationCompletesOnceAfterRecreation() {
        exercisePausedInstallation(cancel = false)
    }

    @Test
    fun recreatedUiCancelsTheOriginalPausedWorker() {
        exercisePausedInstallation(cancel = true)
    }

    private fun exercisePausedInstallation(cancel: Boolean) {
        val engine = PausedInstallerEngine()
        pendingEngine = engine
        val prepared = preparedInstall()
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.setupComplete = true
            // No real profile: dashboard refresh must not touch user game data.
            session.pendingArchiveInstall = prepared
            session.pendingInstallerArchiveRecordId = "record-1"
            session.pendingInstallerSelectedOptionIds = setOf("optional")
            session.showInstallerDialog = true
        }
        try {
            compose.onNodeWithText("Install Selected").performClick()
            assertTrue("Worker did not reach the controlled boundary", engine.entered.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            val attemptId = checkNotNull(Regex("""OPERATION STARTED \[([^]]+)]""").find(session.logText)).groupValues[1]

            recreate()

            compose.runOnIdle {
                assertTrue(session.operationInProgress)
                assertTrue(session.isOperationAdmitted)
                assertSame(prepared, session.pendingArchiveInstall)
            }
            compose.onNode(
                hasText("Installing selected options...") and hasAnyAncestor(isDialog())
            ).assertIsDisplayed()
            compose.runOnIdle {
                val gameId = session.selectedGameId
                val profileId = session.activeProfileId
                val status = session.lastOperationStatus
                val actions = session.buildActions(shareLogs = {}, requestAllFilesAccess = {})
                // Model a delayed busy projection: session admission must still refuse these.
                session.operationInProgress = false
                actions.onConfirmInstaller()
                actions.onDeployMods()
                actions.onSelectProfile("must-not-be-loaded")
                actions.onSelectGame("fallout_nv")
                actions.onSaveSettings()
                session.requestSupportReport()
                assertNull(session.pendingSupportReportFile)
                val mod = Mod(id = "must-not-be-deleted", name = "Test", installPath = "/test/mod",
                    enabled = true, priority = 1, modType = ModType.LOOSE)
                actions.onDeleteMod(mod)
                session.confirmDelete(mod)
                assertEquals(mod, session.pendingDeleteMod)
                session.cancelDelete()
                assertEquals(gameId, session.selectedGameId)
                assertEquals(profileId, session.activeProfileId)
                assertEquals(status, session.lastOperationStatus)
                assertEquals(1, engine.finalizeCount.get())
                assertEquals(0, engine.saveCount.get())
                session.operationInProgress = true
            }
            if (cancel) {
                compose.onNodeWithText("Cancel").performClick()
                compose.waitUntil(timeoutMillis = 5_000) { session.lastOperationStatus == "Cancelling installer..." }
            } else {
                // Duplicate confirmation must not start another worker after reattachment.
                compose.onNodeWithText("Install Selected").performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    session.logText.contains("Ignoring request: session operation was not admitted.")
                }
            }
            engine.release.countDown()
            val expected = if (cancel) "Installer cancelled." else "Archive imported successfully."
            compose.waitUntil(timeoutMillis = 5_000) {
                session.pendingArchiveInstall == null && !session.operationInProgress && !session.isOperationAdmitted &&
                    session.lastOperationStatus.startsWith(expected)
            }
            compose.runOnIdle {
                assertEquals(1, engine.finalizeCount.get())
                assertEquals(if (cancel) 0 else 1, engine.saveCount.get())
                assertEquals(if (cancel) 0 else 1, engine.markCount.get())
                assertEquals(if (cancel) 1 else 0, engine.cleanupCount.get())
                assertEquals(setOf("optional"), engine.selectedIds)
                val outcome = if (cancel) "CANCELLED" else "SUCCEEDED"
                assertEquals(1, session.logText.lineSequence().count { it.contains("OPERATION STARTED [$attemptId]") })
                assertEquals(1, session.logText.lineSequence().count { it.contains("OPERATION $outcome [$attemptId]") })
                assertFalse(session.logText.contains("OPERATION FAILED [$attemptId]"))
            }
        } finally {
            engine.release.countDown()
        }
    }

    private class PausedInstallerEngine : PendingInstallerEngine {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finalizeCount = AtomicInteger()
        val saveCount = AtomicInteger()
        val markCount = AtomicInteger()
        val cleanupCount = AtomicInteger()
        @Volatile var selectedIds: Set<String> = emptySet()

        override fun getCurrentMods(): List<Mod> = emptyList()
        override fun finalizePreparedArchiveInstall(
            prepared: PreparedArchiveInstall,
            selectedOptionIds: Set<String>,
            priority: Int,
            sourceType: String,
            cancellationSignal: InstallCancellationSignal
        ): Mod {
            finalizeCount.incrementAndGet()
            selectedIds = selectedOptionIds
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Test did not release the worker" }
            cancellationSignal.throwIfCancellationRequested()
            return Mod(id = "test-mod", name = "Test", installPath = "/test/installed", enabled = true,
                priority = priority, modType = ModType.LOOSE)
        }
        override fun saveCurrentMods(mods: List<Mod>) { saveCount.incrementAndGet() }
        override fun markDownloadedArchiveInstalled(archiveId: String, installedModId: String) { markCount.incrementAndGet() }
        override fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) { cleanupCount.incrementAndGet() }
        override fun syncPlugins() = Unit
        override fun appendInstalledModRoutingSummary(mod: Mod) = Unit
    }

    private fun preparedInstall() = PreparedArchiveInstall(
        archivePath = "/test/example.zip",
        archiveName = "example.zip",
        modName = "Example",
        sessionRootPath = "/test/session",
        extractedRootPath = "/test/session/raw",
        installRootPath = "/test/session/raw",
        plan = InstallerPlan(
            installerType = InstallerType.BAIN,
            displayName = "Example",
            rootPath = "/test/session/raw",
            groups = listOf(InstallerGroup(
                id = "options",
                name = "Options",
                type = InstallerGroupType.SELECT_ANY,
                options = listOf(InstallerOption(id = "optional", name = "Optional", sourcePath = "10 Optional"))
            ))
        )
    )

    @Test
    fun secondScreenIntentSurvivesRecreationWithoutRetoggling() {
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.setupComplete = true
            session.secondScreenPluginCoordinator.toggle()
        }
        val logBefore = session.logText

        recreate()

        assertTrue(session.secondScreenPluginCoordinator.enabled)
        assertEquals("Second screen plugin display enabled.", session.lastOperationStatus)
        assertEquals(logBefore, session.logText)
        compose.onNodeWithText("Status: Second screen plugin display enabled.").assertIsDisplayed()
    }
}
