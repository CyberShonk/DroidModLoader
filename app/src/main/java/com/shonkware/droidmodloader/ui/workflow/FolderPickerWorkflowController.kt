package com.shonkware.droidmodloader.ui.workflow

internal class FolderPickerWorkflowController(
    private val runInBackground: (() -> Unit) -> Unit,
    private val saveFirstSetupDataPath: (String) -> Unit,
    private val savePickedDataFolderToSelectedGameConfig: (String) -> Unit,
    private val savePickedRootFolderToSelectedGameConfig: (String) -> Unit,
    private val setNewProfileDataPathText: (String) -> Unit,
    private val saveArchiveLibraryPath: (String) -> Unit,
    private val appendLog: (String) -> Unit
) {

    fun handlePickedFolder(
        mode: FolderPickMode,
        path: String
    ) {
        runInBackground {
            when (mode) {
                FolderPickMode.FirstSetupDataFolder -> {
                    saveFirstSetupDataPath(path)
                    appendLog("Selected Data folder for first setup.")
                }

                FolderPickMode.ActiveDataFolder -> {
                    savePickedDataFolderToSelectedGameConfig(path)
                }

                FolderPickMode.ActiveGameRootFolder -> {
                    savePickedRootFolderToSelectedGameConfig(path)
                }

                FolderPickMode.NewProfileDataFolder -> {
                    setNewProfileDataPathText(path)
                    appendLog("Selected Data folder for new profile.")
                }

                FolderPickMode.ArchiveLibraryFolder -> {
                    saveArchiveLibraryPath(path)
                    appendLog("Selected Archive Library folder.")
                }
            }
        }
    }
}
