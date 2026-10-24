package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.deploy.GameInstallationResolver
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirectFolderSelectionCoordinatorTest {

    @Test
    fun `open and select writable data folder`() {
        val root = Files.createTempDirectory("dml-folder-selection").toFile()
        val selected = mutableListOf<Pair<FolderPickMode, String>>()
        val validator = DirectPathValidator()
        val coordinator = DirectFolderSelectionCoordinator(
            accessGrantedProvider = { true },
            browser = DirectFolderBrowser(listOf(root), validator),
            pathValidator = validator,
            currentPathProvider = { root.absolutePath },
            requestAllFilesAccess = {},
            handlePickedFolder = { mode, path -> selected += mode to path }
        )

        coordinator.open(FolderPickMode.ActiveDataFolder)
        assertTrue(coordinator.showBrowser)
        assertEquals("Choose Data Folder", coordinator.browserTitle)
        assertTrue(coordinator.browserRequiresWritable)

        coordinator.selectCurrent()

        assertFalse(coordinator.showBrowser)
        assertEquals(listOf(FolderPickMode.ActiveDataFolder to root.canonicalPath), selected)
    }

    @Test
    fun `folder chosen for superseded profile or game is refused`() {
        val root = Files.createTempDirectory("dml-stale-folder").toFile()
        try {
            var scope = FolderSelectionScope("profile-a", "skyrim_le")
            var selections = 0
            val validator = DirectPathValidator()
            val coordinator = DirectFolderSelectionCoordinator(
                accessGrantedProvider = { true },
                browser = DirectFolderBrowser(listOf(root), validator),
                pathValidator = validator,
                currentPathProvider = { root.absolutePath },
                requestAllFilesAccess = {},
                handlePickedFolder = { _, _ -> selections++ },
                selectionScopeProvider = { scope }
            )
            for (replacement in listOf(
                FolderSelectionScope("profile-b", "skyrim_le"),
                FolderSelectionScope("profile-a", "fallout_nv")
            )) {
                scope = FolderSelectionScope("profile-a", "skyrim_le")
                coordinator.open(FolderPickMode.ActiveDataFolder)
                scope = replacement
                coordinator.selectCurrent()
                assertEquals(0, selections)
                assertTrue(coordinator.showBrowser)
                assertEquals("Profile or game changed. Reopen the folder chooser.", coordinator.browserState.errorMessage)
            }
            coordinator.open(FolderPickMode.ActiveDataFolder)
            coordinator.selectCurrent()
            assertEquals(1, selections)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `access revoked after opening refuses every selection mode until restored`() {
        val root = Files.createTempDirectory("dml-folder-revoked-access").toFile()
        try {
            File(root, "FalloutNV.exe").writeText("")
            val data = File(root, "Data").apply { mkdirs() }
            File(data, "FalloutNV.esm").writeText("")
            val validator = DirectPathValidator()
            for (mode in FolderPickMode.entries) {
                var granted = true
                var selections = 0
                var requests = 0
                val coordinator = DirectFolderSelectionCoordinator(
                    accessGrantedProvider = { granted },
                    browser = DirectFolderBrowser(listOf(root), validator),
                    pathValidator = validator,
                    currentPathProvider = { root.absolutePath },
                    requestAllFilesAccess = { requests++ },
                    handlePickedFolder = { _, _ -> selections++ },
                    gameInstallationResolver = GameInstallationResolver(),
                    selectedGameIdProvider = { "fallout_nv" },
                    handlePickedGameInstallation = { _, _ -> selections++ }
                )
                coordinator.open(mode)
                granted = false

                coordinator.selectCurrent()

                assertEquals("$mode must not publish a selection without access", 0, selections)
                assertTrue("$mode must preserve the open chooser", coordinator.showBrowser)
                assertFalse("$mode must refresh permission state", coordinator.allFilesAccessGranted)
                assertEquals("$mode must offer permission recovery", 1, requests)

                granted = true
                coordinator.refreshAccessState()
                coordinator.selectCurrent()

                assertEquals("$mode must allow retry after access returns", 1, selections)
                assertFalse(coordinator.showBrowser)
                assertEquals(1, requests)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing access requests settings without opening browser`() {
        var requests = 0
        val root = Files.createTempDirectory("dml-folder-access").toFile()
        val validator = DirectPathValidator()
        val coordinator = DirectFolderSelectionCoordinator(
            accessGrantedProvider = { false },
            browser = DirectFolderBrowser(listOf(root), validator),
            pathValidator = validator,
            currentPathProvider = { "" },
            requestAllFilesAccess = { requests++ },
            handlePickedFolder = { _, _ -> }
        )

        coordinator.open(FolderPickMode.ArchiveLibraryFolder)

        assertFalse(coordinator.showBrowser)
        assertFalse(coordinator.allFilesAccessGranted)
        assertEquals(1, requests)
    }
}
