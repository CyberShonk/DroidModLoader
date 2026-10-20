package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
