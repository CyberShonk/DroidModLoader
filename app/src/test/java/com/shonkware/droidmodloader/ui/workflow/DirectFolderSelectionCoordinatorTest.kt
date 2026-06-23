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
