package com.shonkware.droidmodloader.ui.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.deploy.GameInstallationResolver
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidationSeverity
import com.shonkware.droidmodloader.engine.deploy.ResolvedGameInstallation
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowser
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator

internal data class FolderSelectionScope(val profileId: String?, val gameId: String)

internal class DirectFolderSelectionCoordinator(
    private val accessGrantedProvider: () -> Boolean,
    private val browser: DirectFolderBrowser,
    private val pathValidator: DirectPathValidator,
    private val currentPathProvider: (FolderPickMode) -> String,
    private val requestAllFilesAccess: () -> Unit,
    private val handlePickedFolder: (FolderPickMode, String) -> Unit,
    private val gameInstallationResolver: GameInstallationResolver? = null,
    private val selectedGameIdProvider: (FolderPickMode) -> String? = { null },
    private val handlePickedGameInstallation: (FolderPickMode, ResolvedGameInstallation) -> Unit = { _, _ -> },
    private val selectionScopeProvider: (FolderPickMode) -> FolderSelectionScope = {
        FolderSelectionScope(null, "")
    }
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

    private var folderPickMode = FolderPickMode.ActiveGameFolder
    private var openedScope: FolderSelectionScope? = null

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
        openedScope = selectionScopeProvider(mode)
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
        if (openedScope != selectionScopeProvider(folderPickMode)) {
            browserState = browserState.copy(
                errorMessage = "Profile or game changed. Reopen the folder chooser."
            )
            return
        }
        val currentPath = browserState.currentPath ?: return

        if (folderPickMode.isGameInstallationMode()) {
            selectCurrentGameInstallation(currentPath)
            return
        }

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

    private fun selectCurrentGameInstallation(path: String) {
        val resolver = gameInstallationResolver
        if (resolver == null) {
            browserState = browserState.copy(
                errorMessage = "Game installation validation is unavailable."
            )
            return
        }

        val gameId = selectedGameIdProvider(folderPickMode)?.trim().orEmpty()
        if (gameId.isBlank()) {
            browserState = browserState.copy(
                errorMessage = "Select a supported game before choosing its folder."
            )
            return
        }

        val resolution = resolver.resolve(
            gameId = gameId,
            selectedGameRootPath = path
        )
        val installation = resolution.installation
        if (!resolution.isResolved || installation == null) {
            val finding = resolution.findings.firstOrNull {
                it.severity == GameTargetValidationSeverity.ERROR
            }
            browserState = browserState.copy(
                errorMessage = finding?.let {
                    if (it.details.isBlank()) it.title else "${it.title}\n${it.details}"
                } ?: "The selected game folder could not be validated."
            )
            return
        }

        showBrowser = false
        handlePickedGameInstallation(folderPickMode, installation)
    }

    private fun FolderPickMode.isGameInstallationMode(): Boolean {
        return when (this) {
            FolderPickMode.FirstSetupGameFolder,
            FolderPickMode.ActiveGameFolder,
            FolderPickMode.NewProfileGameFolder -> true
            else -> false
        }
    }

    private fun titleFor(mode: FolderPickMode): String {
        return when (mode) {
            FolderPickMode.FirstSetupGameFolder,
            FolderPickMode.ActiveGameFolder,
            FolderPickMode.NewProfileGameFolder -> "Choose Game Folder"
            FolderPickMode.FirstSetupDataFolder,
            FolderPickMode.ActiveDataFolder,
            FolderPickMode.NewProfileDataFolder -> "Choose Data Folder"
            FolderPickMode.ActiveGameRootFolder -> "Choose Game Root Folder"
            FolderPickMode.ArchiveLibraryFolder -> "Choose Archive Library Folder"
        }
    }
}
