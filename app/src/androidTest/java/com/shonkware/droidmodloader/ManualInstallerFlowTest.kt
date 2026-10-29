package com.shonkware.droidmodloader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.install.*
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.ui.*
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import com.shonkware.droidmodloader.ui.workflow.PendingInstallerEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic cache-only installs through the real UI, pending workflow and file transaction. */
class ManualInstallerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var engine: FixtureEngine
    private lateinit var session: SessionViewModel

    @Before fun fixture() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "v08-12-${UUID.randomUUID()}").apply { check(mkdirs()) }
        engine = FixtureEngine(File(root, "mods"), File(root, "temp"))
    }

    @After fun cleanFixture() {
        compose.waitUntil(10_000) { !::session.isInitialized || !session.isOperationAdmitted }
        check(root.deleteRecursively())
    }

    private fun render(activity: ComponentActivity) {
        val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                modelClass.cast(SessionViewModel(activity.application) { engine })!!
        })[SessionViewModel::class.java]
        if (::session.isInitialized) assertSame(session, model) else session = model
        val actions = model.buildActions(shareLogs = {}, requestAllFilesAccess = {})
        activity.setContent { DmlTheme { DroidModLoaderScreen(model.dashboardState(), actions) } }
    }

    private fun open(contents: Map<String, String>): PreparedArchiveInstall {
        val source = File(root, "Original.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            contents.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val prepared = engine.prepare(source)
        check(source.delete()) // Match the temporary snapshot's lifetime after preparation.
        compose.activityRule.scenario.onActivity { activity ->
            render(activity)
            session.setupComplete = true
            session.pendingArchiveInstall = prepared
            session.pendingInstallerSelectedOptionIds = prepared.plan.defaultSelectedOptionIds
            session.installerChoices = InstallerDialogState(modName = prepared.modName)
            session.showInstallerDialog = true
        }
        return prepared
    }

    private fun name(value: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }

    private fun click(text: String) {
        compose.onNode(hasText(text, substring = false) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        compose.waitForIdle()
    }

    private fun openFolderMenu(path: String) {
        val proposal = session.installerChoices.proposal
        compose.onNodeWithContentDescription("Folder actions $path").performClick()
        compose.waitForIdle()
        assertEquals(proposal, session.installerChoices.proposal)
        assertEquals(path, session.installerChoices.selectedFolder)
        compose.onNodeWithText("Use as Data Folder").assertIsDisplayed()
    }

    private fun applyFolderRoot() {
        // DropdownMenu is a separate popup, not a child of the installer dialog.
        compose.onNodeWithText("Use as Data Folder").performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        compose.waitForIdle()
    }

    private fun installed(): File {
        compose.waitUntil(10_000) {
            !session.isOperationAdmitted && (session.pendingArchiveInstall == null || session.installerChoices.error != null)
        }
        assertNull("Install failed: ${session.installerChoices.error}\n${session.logText.takeLast(3000)}", session.pendingArchiveInstall)
        assertEquals(1, engine.finalizeCount)
        assertEquals(1, engine.saveCount)
        return File(engine.mods, engine.lastConfirmation!!.modName)
    }

    @Test fun firstDialogIsCompactWithOneGroupedActionRow() {
        open(mapOf("mod.esp" to "plugin"))
        val panel = compose.onNodeWithTag("installer-panel").getUnclippedBoundsInRoot()
        assertTrue((panel.bottom - panel.top).value < compose.activity.resources.configuration.screenHeightDp / 2f)
        val choose = compose.onNodeWithText("Choose Files").assertIsDisplayed().getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithText("Cancel").assertIsDisplayed().getUnclippedBoundsInRoot()
        val install = compose.onNodeWithText("Install").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(choose.right <= cancel.left && cancel.right <= install.left)
        val chooseY = (choose.top.value + choose.bottom.value) / 2f
        val cancelY = (cancel.top.value + cancel.bottom.value) / 2f
        val installY = (install.top.value + install.bottom.value) / 2f
        assertTrue(kotlin.math.abs(chooseY - installY) <= 4f)
        assertTrue(kotlin.math.abs(cancelY - installY) <= 4f)
        click("Cancel")
        assertEquals(0, engine.finalizeCount)
    }

    @Test fun ordinaryInstallUsesTheEditedLibraryFolderName() {
        open(mapOf("Data/mod.esp" to "plugin", "skse_loader.exe" to "loader"))
        compose.onNode(hasText("Install Mod") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Choose Files").assertIsDisplayed()
        name("A Clean Name")
        click("Install")
        val folder = installed()
        assertEquals("plugin", File(folder, "Data/mod.esp").readText())
        assertEquals("loader", File(folder, "skse_loader.exe").readText())
        assertFalse(File(folder, "A Clean Name").exists())
        assertFalse(File(engine.mods, "Original").exists())
    }

    @Test fun rootExclusionsNameAndExpandedTreeSurviveRecreationAndBack() {
        open(mapOf("Wrapper/Content/mod.esp" to "plugin", "Wrapper/Content/textures/a.dds" to "a",
            "Wrapper/Content/textures/b.dds" to "b", "readme.txt" to "readme"))
        name("My Mod")
        click("Choose Files")
        click("Reset to Archive Root")
        compose.onNodeWithContentDescription("Expand Wrapper").performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        openFolderMenu("Wrapper/Content")
        applyFolderRoot()
        compose.onNodeWithContentDescription("Expand Wrapper/Content/textures").performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        compose.onNodeWithContentDescription("Include Wrapper/Content/textures/b.dds").performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        val retained = session.installerChoices
        val inventory = checkNotNull(session.pendingArchiveInstall?.manualLayoutInventory)
        inventory.preview(checkNotNull(retained.proposal)).files.forEach { file ->
            assertEquals(File(inventory.extractedRootPath, file.sourcePath), inventory.sourceFile(file))
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::render)
        compose.waitForIdle()
        assertEquals(retained, session.installerChoices)
        compose.onNodeWithText("Content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Include Wrapper/Content/textures/b.dds").assertIsOff()
        name("Edited in Tree")
        assertEquals("Edited in Tree", session.installerChoices.modName)
        click("Back")
        name("Renamed Mod")
        click("Install")
        val folder = installed()
        assertEquals(setOf("mod.esp", "textures/a.dds"), folder.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(folder).invariantSeparatorsPath }.toSet())
        assertEquals(retained.proposal, engine.lastConfirmation!!.manualLayout)
        assertEquals("Renamed Mod", engine.lastConfirmation!!.modName)
    }

    @Test fun unknownStructureRequiresShortConfirmationAndCancelMakesNoWrites() {
        val prepared = open(mapOf("asset.custom" to "custom"))
        name("Unusual Mod")
        click("Install")
        compose.onNodeWithText("Content").assertIsDisplayed()
        compose.onNodeWithText("Recognized folder structure").assertDoesNotExist()
        compose.onNodeWithText("Preview installed paths").assertDoesNotExist()
        click("Install")
        compose.onNodeWithText("This mod's file structure is unrecognized.").assertIsDisplayed()
        assertEquals(0, engine.finalizeCount)
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.waitForIdle()
        assertTrue(File(prepared.sessionRootPath).exists())
        assertFalse(engine.mods.exists())
        click("Install")
        click("Install Anyway")
        assertEquals("custom", File(installed(), "asset.custom").readText())
    }

    @Test fun addUpdateKeepsOldOnlyFilesAndReplacesMatches() = collision(ExistingModAction.ADD_UPDATE)
    @Test fun replaceRemovesOldOnlyFiles() = collision(ExistingModAction.REPLACE)

    private fun collision(action: ExistingModAction) {
        val previous = File(engine.mods, "Original").apply { mkdirs() }
        File(previous, "old.txt").writeText("keep only for merge")
        File(previous, "mod.esp").writeText("old plugin")
        open(mapOf("mod.esp" to "new plugin"))
        click("Install")
        compose.onNodeWithText("A mod named “Original” already exists.").assertIsDisplayed()
        assertEquals(0, engine.finalizeCount)
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.waitForIdle()
        assertEquals("old plugin", File(previous, "mod.esp").readText())
        assertTrue(File(previous, "old.txt").exists())
        click("Install")
        click(if (action == ExistingModAction.ADD_UPDATE) "Add / Update Files" else "Replace")
        val folder = installed()
        assertEquals("new plugin", File(folder, "mod.esp").readText())
        assertEquals(action == ExistingModAction.ADD_UPDATE, File(folder, "old.txt").exists())
    }

    @Test fun emptySelectionBlocksInstallationAndCancelCleansThePreparedSession() {
        val prepared = open(mapOf("mod.esp" to "plugin"))
        click("Choose Files")
        compose.onNodeWithContentDescription("Include mod.esp").performClick()
        compose.waitUntil(10_000) { !session.isOperationAdmitted }
        compose.onNodeWithText("Choose at least one file to install.").assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsNotEnabled()
        click("Cancel")
        assertEquals(0, engine.finalizeCount)
        assertFalse(File(prepared.sessionRootPath).exists())
        assertNull(session.pendingArchiveInstall)
    }

    @Test fun landscapeTreeScrollsWhileInstallAndCancelStayReachable() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val contents = (0..99).associate { "Content/mod${it.toString().padStart(3, '0')}.esp" to "plugin" } +
            mapOf("Content/textures/example.dds" to "texture", "readme.txt" to "readme")
        val prepared = open(contents)
        click("Choose Files")
        val list = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        list.performScrollToNode(hasContentDescription("Include Content/mod099.esp"))
        compose.onNodeWithContentDescription("Include Content/mod099.esp").assertIsDisplayed()
        list.performScrollToNode(hasText("textures/"))
        openFolderMenu("Content/textures")
        list.assertHeightIsAtLeast(48.dp)
        applyFolderRoot()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals("Content/textures", session.installerChoices.proposal!!.rootPath)
        list.performScrollToNode(hasText("example.dds"))
        compose.onNodeWithText("example.dds").assertIsDisplayed()
        click("Cancel")
        assertFalse(File(prepared.sessionRootPath).exists())
        assertEquals(0, engine.finalizeCount)
    }

    @Test fun changedPreparedFileFailsVisiblyWithoutCreatingAnInstallation() {
        val prepared = open(mapOf("mod.esp" to "plugin"))
        click("Choose Files")
        File(prepared.extractedRootPath, "mod.esp").writeText("changed contents")
        click("Install")
        compose.onNode(hasText("Prepared archive files changed.", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertNotNull(session.pendingArchiveInstall)
        assertFalse(File(engine.mods, "Original").exists())
        click("Cancel")
    }

    private class FixtureEngine(val mods: File, temp: File) : PendingInstallerEngine {
        private val installer = PreparedArchiveInstaller(temp, mods)
        private var state = emptyList<Mod>()
        var finalizeCount = 0
        var saveCount = 0
        var lastConfirmation: ArchiveInstallConfirmation? = null
        fun prepare(source: File): PreparedArchiveInstall = installer.prepare(source)
        override fun getCurrentMods(): List<Mod> = state
        override fun findExistingModName(name: String): String? = installer.findExistingModName(name)
        override fun finalizePreparedArchiveInstall(prepared: PreparedArchiveInstall, selectedOptionIds: Set<String>,
            priority: Int, sourceType: String, cancellationSignal: InstallCancellationSignal): Mod {
            finalizeCount++
            lastConfirmation = prepared.confirmation
            val folder = installer.finalizeInstall(prepared, InstallerSelection(selectedOptionIds), cancellationSignal)
            return Mod(folder.name, folder.name, folder.absolutePath, true, priority, ModType.LOOSE)
        }
        override fun saveCurrentMods(mods: List<Mod>) { state = mods; saveCount++ }
        override fun markDownloadedArchiveInstalled(archiveId: String, installedModId: String) = Unit
        override fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) = installer.cancel(prepared)
        override fun syncPlugins() = Unit
        override fun appendInstalledModRoutingSummary(mod: Mod) = Unit
    }
}
