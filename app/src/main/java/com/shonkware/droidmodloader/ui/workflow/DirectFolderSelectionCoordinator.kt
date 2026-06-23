package com.shonkware.droidmodloader.ui.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator

internal class DirectFolderSelectionCoordinator(
    private val accessGrantedProvider: () -> Boolean,
    private val browser: DirectFolderBrowser,
    private val pathValidator: DirectPathValidator,
    private val currentPathProvider: (FolderPickMode) -> String,
    private val requestAllFilesAccess: () -> Unit,
    private val handlePickedFolder: (FolderPickMode, String) -> Unit
) {
    var allFilesAccessGranted by mutableStateOf(true)
        private set
    var showBrowser by mutableStateOf(false)
        private set
    var browserTitle by mutableStateOf("Choose Folder")
        private set
    var browserRequiresWritable by mutableStateOf(true)
        private set
    var browserState by mutableStateOf(DirectFolderBrowserState())
        private set

    private var folderPickMode = FolderPickMode.ActiveDataFolder

    fun refreshAccessState() {
        allFilesAccessGranted = accessGrantedProvider()
    }

    fun open(mode: FolderPickMode) {
        if (!accessGrantedProvider()) {
            refreshAccessState()
            requestAllFilesAccess()
            return
        }

        folderPickMode = mode
        browserRequiresWritable = mode != FolderPickMode.ArchiveLibraryFolder
        browserTitle = titleFor(mode)
        val currentPath = currentPathProvider(mode)
        browserState = if (currentPath.isBlank()) {
            browser.openRoots()
        } else {
            browser.open(currentPath)
        }
        showBrowser = true
    }

    fun openPath(path: String) {
        browserState = browser.open(path)
    }

    fun navigateUp() {
        browserState = browser.navigateUp(browserState)
    }

    fun selectCurrent() {
        val currentPath = browserState.currentPath ?: return
        val validation = pathValidator.validateDirectory(
            path = currentPath,
            requireWritable = browserRequiresWritable
        )
        if (!validation.isValid || validation.canonicalPath == null) {
            browserState = browserState.copy(errorMessage = validation.message)
            return
        }

        showBrowser = false
        handlePickedFolder(folderPickMode, validation.canonicalPath)
    }

    fun cancel() {
        showBrowser = false
    }

    private fun titleFor(mode: FolderPickMode): String {
        return when (mode) {
            FolderPickMode.FirstSetupDataFolder,
            FolderPickMode.ActiveDataFolder,
            FolderPickMode.NewProfileDataFolder -> "Choose Data Folder"
            FolderPickMode.ActiveGameRootFolder -> "Choose Game Root Folder"
            FolderPickMode.ArchiveLibraryFolder -> "Choose Archive Library Folder"
        }
    }
}
