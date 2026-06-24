package com.shonkware.droidmodloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState

/**
 * Activity-scoped Compose state. This keeps mutable UI state and its immutable
 * [DashboardUiState] projection out of the Android lifecycle class.
 */
interface MainActivityUiState {
    var setupComplete: Boolean
    var activeProfileId: String?
    var profileNameText: String
    var profileOptions: List<GameProfile>
    var activeProfileName: String
    var showProfileDialog: Boolean
    var setupGameId: String
    var setupGameDisplayName: String
    var setupTargetPathText: String
    var setupRealDeployEnabled: Boolean
    var operationInProgress: Boolean
    var activeOperationText: String
    var newProfileNameText: String
    var newProfileGameId: String
    var newProfileGameDisplayName: String
    var newProfileDataPathText: String
    var newProfileRealDeployEnabled: Boolean
    var developerTapCount: Int
    var developerModeEnabled: Boolean
    var lastOperationStatus: String
    var logText: String
    var summaryText: String
    var visibleMods: List<Mod>
    var visiblePlugins: List<PluginEntry>
    var gameOptions: List<String>
    var selectedGameId: String
    var targetPathText: String
    var selectedDataPathText: String
    var selectedRootPathText: String
    var rootTargetPathText: String
    var dataPathReselectionRequired: Boolean
    var rootPathReselectionRequired: Boolean
    var realDeployEnabledState: Boolean
    var pendingArchiveInstall: PreparedArchiveInstall?
    var pendingInstallerArchiveRecordId: String?
    var pendingInstallerSelectedOptionIds: Set<String>
    var showInstallerDialog: Boolean
    var installerDialogFullscreen: Boolean
    var visibleModContentIndexes: Map<String, ModContentIndex>
    var selectedModFilePreview: ModFilePreview?
    var showModFilePreviewDialog: Boolean
    var modFilePreviewFullscreen: Boolean
    var fullscreenPanel: FullscreenPanel
    var showArchiveFolderSetupDialog: Boolean
    var archiveBrowserState: ArchiveBrowserUiState
    var overwriteEntries: List<OverwriteEntry>
    var showOverwriteDialog: Boolean
    var overwriteBaselineExists: Boolean
    var overwriteMessage: String
    var deployRecoveryWarningText: String
    var showDeployRecoveryDialog: Boolean
    var showForceFullRedeployConfirmDialog: Boolean

    fun toDashboardUiState(
        secondScreenEnabled: Boolean,
        allFilesAccessRequired: Boolean,
        directFolderState: DirectFolderSelectionUiState
    ): DashboardUiState
}

data class DirectFolderSelectionUiState(
    val allFilesAccessGranted: Boolean,
    val showBrowser: Boolean,
    val browserTitle: String,
    val browserRequiresWritable: Boolean,
    val browserState: DirectFolderBrowserState
)

internal class MutableMainActivityUiState : MainActivityUiState {
    override var setupComplete by mutableStateOf(false)
    override var activeProfileId by mutableStateOf<String?>(null)
    override var profileNameText by mutableStateOf("Default")
    override var profileOptions by mutableStateOf<List<GameProfile>>(emptyList())
    override var activeProfileName by mutableStateOf("No profile")
    override var showProfileDialog by mutableStateOf(false)
    override var setupGameId by mutableStateOf("skyrim_le")
    override var setupGameDisplayName by mutableStateOf("Skyrim Legendary Edition")
    override var setupTargetPathText by mutableStateOf("")
    override var setupRealDeployEnabled by mutableStateOf(false)
    override var operationInProgress by mutableStateOf(false)
    override var activeOperationText by mutableStateOf("")
    override var newProfileNameText by mutableStateOf("")
    override var newProfileGameId by mutableStateOf("skyrim_le")
    override var newProfileGameDisplayName by mutableStateOf("Skyrim Legendary Edition")
    override var newProfileDataPathText by mutableStateOf("No folder selected")
    override var newProfileRealDeployEnabled by mutableStateOf(false)
    override var developerTapCount = 0
    override var developerModeEnabled by mutableStateOf(false)
    override var lastOperationStatus by mutableStateOf("Ready.")
    override var logText by mutableStateOf("")
    override var summaryText by mutableStateOf("Loading...")
    override var visibleMods by mutableStateOf<List<Mod>>(emptyList())
    override var visiblePlugins by mutableStateOf<List<PluginEntry>>(emptyList())
    override var gameOptions by mutableStateOf(listOf("skyrim_le", "oblivion", "fallout_3", "fallout_nv", "ttw"))
    override var selectedGameId by mutableStateOf("skyrim_le")
    override var targetPathText by mutableStateOf("")
    override var selectedDataPathText by mutableStateOf("No folder selected")
    override var selectedRootPathText by mutableStateOf("No root folder selected")
    override var rootTargetPathText by mutableStateOf("")
    override var dataPathReselectionRequired by mutableStateOf(false)
    override var rootPathReselectionRequired by mutableStateOf(false)
    override var realDeployEnabledState by mutableStateOf(false)
    override var pendingArchiveInstall by mutableStateOf<PreparedArchiveInstall?>(null)
    override var pendingInstallerArchiveRecordId by mutableStateOf<String?>(null)
    override var pendingInstallerSelectedOptionIds by mutableStateOf<Set<String>>(emptySet())
    override var showInstallerDialog by mutableStateOf(false)
    override var installerDialogFullscreen by mutableStateOf(false)
    override var visibleModContentIndexes by mutableStateOf<Map<String, ModContentIndex>>(emptyMap())
    override var selectedModFilePreview by mutableStateOf<ModFilePreview?>(null)
    override var showModFilePreviewDialog by mutableStateOf(false)
    override var modFilePreviewFullscreen by mutableStateOf(false)
    override var fullscreenPanel by mutableStateOf(FullscreenPanel.NONE)
    override var showArchiveFolderSetupDialog by mutableStateOf(false)
    override var archiveBrowserState by mutableStateOf(ArchiveBrowserUiState())
    override var overwriteEntries by mutableStateOf<List<OverwriteEntry>>(emptyList())
    override var showOverwriteDialog by mutableStateOf(false)
    override var overwriteBaselineExists by mutableStateOf(false)
    override var overwriteMessage by mutableStateOf("")
    override var deployRecoveryWarningText by mutableStateOf("")
    override var showDeployRecoveryDialog by mutableStateOf(false)
    override var showForceFullRedeployConfirmDialog by mutableStateOf(false)

    override fun toDashboardUiState(
        secondScreenEnabled: Boolean,
        allFilesAccessRequired: Boolean,
        directFolderState: DirectFolderSelectionUiState
    ): DashboardUiState {
        return DashboardUiState(
            appName = "Droid Mod Loader",
            versionLabel = "Version 0.6.0 Beta",
            developerModeEnabled = developerModeEnabled,
            lastOperationStatus = lastOperationStatus,
            summaryText = summaryText,
            mods = visibleMods,
            plugins = visiblePlugins,
            gameOptions = gameOptions,
            selectedGameId = selectedGameId,
            selectedDataPathText = selectedDataPathText,
            selectedRootPathText = selectedRootPathText,
            realDeployEnabled = realDeployEnabledState,
            logText = logText,
            setupComplete = setupComplete,
            profileNameText = profileNameText,
            setupGameId = setupGameId,
            setupTargetPathText = setupTargetPathText,
            setupRealDeployEnabled = setupRealDeployEnabled,
            activeProfileName = activeProfileName,
            profileOptions = profileOptions,
            activeProfileId = activeProfileId,
            newProfileNameText = newProfileNameText,
            newProfileGameId = newProfileGameId,
            newProfileRealDeployEnabled = newProfileRealDeployEnabled,
            showProfileDialog = showProfileDialog,
            newProfileDataPathText = newProfileDataPathText,
            operationInProgress = operationInProgress,
            activeOperationText = activeOperationText,
            modContentIndexes = visibleModContentIndexes,
            pendingArchiveInstall = pendingArchiveInstall,
            selectedInstallerOptionIds = pendingInstallerSelectedOptionIds,
            showInstallerDialog = showInstallerDialog,
            installerDialogFullscreen = installerDialogFullscreen,
            selectedModFilePreview = selectedModFilePreview,
            showModFilePreviewDialog = showModFilePreviewDialog,
            modFilePreviewFullscreen = modFilePreviewFullscreen,
            secondScreenEnabled = secondScreenEnabled,
            fullscreenPanel = fullscreenPanel,
            overwriteEntries = overwriteEntries,
            showOverwriteDialog = showOverwriteDialog,
            overwriteBaselineExists = overwriteBaselineExists,
            overwriteMessage = overwriteMessage,
            deployRecoveryWarningText = deployRecoveryWarningText,
            showDeployRecoveryDialog = showDeployRecoveryDialog,
            showForceFullRedeployConfirmDialog = showForceFullRedeployConfirmDialog,
            showArchiveFolderSetupDialog = showArchiveFolderSetupDialog,
            archiveBrowserState = archiveBrowserState,
            allFilesAccessRequired = allFilesAccessRequired,
            allFilesAccessGranted = directFolderState.allFilesAccessGranted,
            showDirectFolderBrowser = directFolderState.showBrowser,
            directFolderBrowserTitle = directFolderState.browserTitle,
            directFolderBrowserRequiresWritable = directFolderState.browserRequiresWritable,
            directFolderBrowserState = directFolderState.browserState
        )
    }
}
