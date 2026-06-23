package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUiStateTest {
    @Test
    fun `dashboard projection includes activity and direct folder state`() {
        val state = MutableMainActivityUiState().apply {
            activeProfileName = "Mojave"
            selectedGameId = "fallout_nv"
            selectedDataPathText = "/storage/emulated/0/FNV/Data"
            developerModeEnabled = true
            showArchiveFolderSetupDialog = true
        }

        val result = state.toDashboardUiState(
            secondScreenEnabled = true,
            allFilesAccessRequired = true,
            directFolderState = DirectFolderSelectionUiState(
                allFilesAccessGranted = false,
                showBrowser = true,
                browserTitle = "Choose Data Folder",
                browserRequiresWritable = true,
                browserState = DirectFolderBrowserState(currentPath = "/storage/emulated/0")
            )
        )

        assertEquals("Mojave", result.activeProfileName)
        assertEquals("fallout_nv", result.selectedGameId)
        assertEquals("/storage/emulated/0/FNV/Data", result.selectedDataPathText)
        assertTrue(result.developerModeEnabled)
        assertTrue(result.secondScreenEnabled)
        assertTrue(result.allFilesAccessRequired)
        assertFalse(result.allFilesAccessGranted)
        assertTrue(result.showDirectFolderBrowser)
        assertEquals("Choose Data Folder", result.directFolderBrowserTitle)
        assertTrue(result.showArchiveFolderSetupDialog)
    }
}
