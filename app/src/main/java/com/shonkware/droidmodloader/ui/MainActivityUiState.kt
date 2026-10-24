package com.shonkware.droidmodloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.attention.AppAttentionId
import com.shonkware.droidmodloader.attention.AppAttentionIds
import com.shonkware.droidmodloader.attention.AppAttentionKind
import com.shonkware.droidmodloader.attention.AppAttentionProjectionInput
import com.shonkware.droidmodloader.attention.AppAttentionProjector
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthFacts
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthPhase
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthProjector
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentIntegrityEvidence
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryFact
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentTargetHealth
import com.shonkware.droidmodloader.engine.deploy.health.TargetReadiness
import com.shonkware.droidmodloader.engine.flags.ModFlag
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.workflow.DeploymentConfigUiMapper
import com.shonkware.droidmodloader.BuildConfig

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
    var setupRootTargetPathText: String
    var operationInProgress: Boolean
    var integrityCheckInProgress: Boolean
    var activeOperationText: String
    var newProfileNameText: String
    var newProfileGameId: String
    var newProfileGameDisplayName: String
    var newProfileDataPathText: String
    var newProfileRootPathText: String
    var developerTapCount: Int
    var developerModeEnabled: Boolean
    var lastOperationStatus: String
    var logText: String
    var summaryText: String
    var deploymentHealth: DeploymentHealth?
    var deploymentHealthContext: List<String>
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
    var archiveImportInProgress: Boolean
    var pendingInstallerArchiveRecordId: String?
    var pendingInstallerSelectedOptionIds: Set<String>
    var showInstallerDialog: Boolean
    var installerDialogFullscreen: Boolean
    var visibleModContentIndexes: Map<String, ModContentIndex>
    var visibleModFlags: Map<String, Set<ModFlag>>
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
    var deployRecoveryAttentionRequired: Boolean
    var deployRecoveryAttentionProfileId: String?
    var deployRecoveryAttentionGameId: String?
    var installRecoveryAttentionRequired: Boolean
    var installRecoveryAttentionProfileId: String?
    var installRecoveryAttentionGameId: String?
    var dismissedAttentionIds: Set<AppAttentionId>
    var suppressedAttentionPromptIds: Set<AppAttentionId>
    var showDeployRecoveryDialog: Boolean
    var showForceFullRedeployConfirmDialog: Boolean

    fun deploymentHealthContextKey(): List<String> = listOf(activeProfileId.orEmpty(), selectedGameId,
        targetPathText, rootTargetPathText, realDeployEnabledState.toString(),
        dataPathReselectionRequired.toString(), rootPathReselectionRequired.toString())

    fun currentDeploymentHealth(): DeploymentHealth {
        val snapshot = deploymentHealth?.takeIf {
            it.isFor(activeProfileId, selectedGameId) && deploymentHealthContext == deploymentHealthContextKey()
        }
        val installRecovery = installRecoveryAttentionRequired &&
            installRecoveryAttentionProfileId == activeProfileId && installRecoveryAttentionGameId == selectedGameId
        val deployRecovery = deployRecoveryAttentionRequired &&
            deployRecoveryAttentionProfileId == activeProfileId && deployRecoveryAttentionGameId == selectedGameId
        val snapshotCurrent = snapshot != null && snapshot.phase != DeploymentHealthPhase.STALE_CONTEXT && !operationInProgress
        val recovery = if (!snapshotCurrent && deployRecovery && snapshot?.recovery != DeploymentRecoveryFact.FAILED) {
            DeploymentRecoveryFact.UNFINISHED
        } else snapshot?.recovery ?: DeploymentRecoveryFact.UNKNOWN
        val recoveryDetails = if (!snapshotCurrent && recovery == DeploymentRecoveryFact.UNFINISHED &&
            deployRecovery && deployRecoveryWarningText.isNotBlank()) {
            deployRecoveryWarningText
        } else snapshot?.recoveryDetails.orEmpty()
        return DeploymentHealthProjector.project(DeploymentHealthFacts(
            activeProfileId.orEmpty(), selectedGameId,
            snapshot?.targets ?: GameTargetType.entries.map { role ->
                DeploymentTargetHealth(role, null, TargetReadiness.UNKNOWN, null,
                    if (role == GameTargetType.DATA) dataPathReselectionRequired else rootPathReselectionRequired)
            },
            recovery,
            snapshot?.integrity ?: DeploymentIntegrityEvidence(), snapshot?.preflightCanDeploy,
            snapshot?.collectedAtEpochMillis ?: 0, recoveryDetails,
            contextStillCurrent = !operationInProgress && snapshot?.phase != DeploymentHealthPhase.STALE_CONTEXT &&
                (snapshot != null || deploymentHealth == null),
            installRecoveryRequired = installRecovery,
            preflightDetails = snapshot?.preflightDetails.orEmpty()
        ))
    }

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
    override var setupRootTargetPathText by mutableStateOf("")
    override var operationInProgress by mutableStateOf(false)
    override var integrityCheckInProgress by mutableStateOf(false)
    override var activeOperationText by mutableStateOf("")
    override var newProfileNameText by mutableStateOf("")
    override var newProfileGameId by mutableStateOf("skyrim_le")
    override var newProfileGameDisplayName by mutableStateOf("Skyrim Legendary Edition")
    override var newProfileDataPathText by mutableStateOf("")
    override var newProfileRootPathText by mutableStateOf("")
    override var developerTapCount = 0
    override var developerModeEnabled by mutableStateOf(false)
    override var lastOperationStatus by mutableStateOf("Ready.")
    override var logText by mutableStateOf("")
    override var summaryText by mutableStateOf("Loading...")
    override var deploymentHealth by mutableStateOf<DeploymentHealth?>(null)
    override var deploymentHealthContext by mutableStateOf<List<String>>(emptyList())
    override var visibleMods by mutableStateOf<List<Mod>>(emptyList())
    override var visiblePlugins by mutableStateOf<List<PluginEntry>>(emptyList())
    override var gameOptions by mutableStateOf(listOf("skyrim_le", "oblivion", "fallout_3", "fallout_nv", "ttw"))
    override var selectedGameId by mutableStateOf("skyrim_le")
    override var targetPathText by mutableStateOf("")
    override var selectedDataPathText by mutableStateOf("")
    override var selectedRootPathText by mutableStateOf("")
    override var rootTargetPathText by mutableStateOf("")
    override var dataPathReselectionRequired by mutableStateOf(false)
    override var rootPathReselectionRequired by mutableStateOf(false)
    override var realDeployEnabledState by mutableStateOf(false)
    override var pendingArchiveInstall by mutableStateOf<PreparedArchiveInstall?>(null)
    override var archiveImportInProgress by mutableStateOf(false)
    override var pendingInstallerArchiveRecordId by mutableStateOf<String?>(null)
    override var pendingInstallerSelectedOptionIds by mutableStateOf<Set<String>>(emptySet())
    override var showInstallerDialog by mutableStateOf(false)
    override var installerDialogFullscreen by mutableStateOf(false)
    override var visibleModContentIndexes by mutableStateOf<Map<String, ModContentIndex>>(emptyMap())
    override var visibleModFlags by mutableStateOf<Map<String, Set<ModFlag>>>(emptyMap())
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
    override var deployRecoveryAttentionRequired by mutableStateOf(false)
    override var deployRecoveryAttentionProfileId by mutableStateOf<String?>(null)
    override var deployRecoveryAttentionGameId by mutableStateOf<String?>(null)
    override var installRecoveryAttentionRequired by mutableStateOf(false)
    override var installRecoveryAttentionProfileId by mutableStateOf<String?>(null)
    override var installRecoveryAttentionGameId by mutableStateOf<String?>(null)
    override var dismissedAttentionIds by mutableStateOf<Set<AppAttentionId>>(emptySet())
    override var suppressedAttentionPromptIds by mutableStateOf<Set<AppAttentionId>>(emptySet())
    override var showDeployRecoveryDialog by mutableStateOf(false)
    override var showForceFullRedeployConfirmDialog by mutableStateOf(false)

    override fun toDashboardUiState(
        secondScreenEnabled: Boolean,
        allFilesAccessRequired: Boolean,
        directFolderState: DirectFolderSelectionUiState
    ): DashboardUiState {
        val health = currentDeploymentHealth()
        val appAttention = AppAttentionProjector.project(
            AppAttentionProjectionInput(
                profileSessionReady = setupComplete && activeProfileId != null,
                activeProfileId = activeProfileId,
                selectedGameId = selectedGameId,
                deploymentHealth = health
            )
        )

        return DashboardUiState(
            appName = "Droid Mod Loader",
            versionLabel = BuildConfig.VERSION_NAME,
            developerModeEnabled = developerModeEnabled,
            lastOperationStatus = lastOperationStatus,
            summaryText = summaryText,
            deploymentHealth = health,
            mods = visibleMods,
            plugins = visiblePlugins,
            gameOptions = gameOptions,
            selectedGameId = selectedGameId,
            selectedDataPathText = DeploymentConfigUiMapper.dataPathDisplayText(
                targetPathText,
                dataPathReselectionRequired
            ),
            selectedRootPathText = DeploymentConfigUiMapper.rootPathDisplayText(
                rootTargetPathText,
                rootPathReselectionRequired
            ),
            realDeployEnabled = realDeployEnabledState,
            dataTargetReady = DeploymentConfigUiMapper.isTargetReady(
                targetPathText,
                dataPathReselectionRequired
            ),
            rootTargetReady = DeploymentConfigUiMapper.isTargetReady(
                rootTargetPathText,
                rootPathReselectionRequired
            ),
            logText = logText,
            setupComplete = setupComplete,
            profileNameText = profileNameText,
            setupGameId = setupGameId,
            setupTargetPathText = setupTargetPathText,
            setupRootTargetPathText = setupRootTargetPathText,
            setupInstallationResolved = DeploymentConfigUiMapper.isGameInstallationResolved(
                setupRootTargetPathText,
                setupTargetPathText
            ),
            activeProfileName = activeProfileName,
            profileOptions = profileOptions,
            activeProfileId = activeProfileId,
            newProfileNameText = newProfileNameText,
            newProfileGameId = newProfileGameId,
            newProfileRootPathText = newProfileRootPathText,
            newProfileInstallationResolved = DeploymentConfigUiMapper.isGameInstallationResolved(
                newProfileRootPathText,
                newProfileDataPathText
            ),
            showProfileDialog = showProfileDialog,
            newProfileDataPathText = newProfileDataPathText,
            operationInProgress = operationInProgress,
            integrityCheckInProgress = integrityCheckInProgress,
            activeOperationText = activeOperationText,
            modContentIndexes = visibleModContentIndexes,
            modFlags = visibleModFlags,
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
            deployRecoveryWarningText = health.toDebugSummary(),
            deployRecoveryAttentionRequired = health.recovery == DeploymentRecoveryFact.UNFINISHED,
            appAttention = appAttention,
            dismissedAttentionIds = dismissedAttentionIds,
            suppressedAttentionPromptIds = suppressedAttentionPromptIds,
            showDeployRecoveryDialog = showDeployRecoveryDialog,
            showForceFullRedeployConfirmDialog = showForceFullRedeployConfirmDialog,
            showArchiveFolderSetupDialog = showArchiveFolderSetupDialog,
            archiveBrowserState = archiveBrowserState,
            allFilesAccessRequired = allFilesAccessRequired,
            allFilesAccessGranted = directFolderState.allFilesAccessGranted,
            showDirectFolderBrowser = directFolderState.showBrowser,
            directFolderBrowserTitle = directFolderState.browserTitle,
            directFolderBrowserRequiresWritable = directFolderState.browserRequiresWritable,
            directFolderBrowserState = directFolderState.browserState,
            archiveImportInProgress = archiveImportInProgress,
        )
    }
}

internal fun MainActivityUiState.updateInstallRecoveryAttention(
    profileId: String?,
    gameId: String,
    requiresAttention: Boolean
) {
    if (!requiresAttention && profileId != null && gameId.isNotBlank()) {
        val attentionId = AppAttentionIds.scoped(AppAttentionKind.INSTALL_RECOVERY, profileId, gameId)
        dismissedAttentionIds = dismissedAttentionIds - attentionId
        suppressedAttentionPromptIds = suppressedAttentionPromptIds - attentionId
    }

    val matchesActiveScope = profileId == activeProfileId && gameId == selectedGameId
    val matchesStoredScope =
        profileId == installRecoveryAttentionProfileId && gameId == installRecoveryAttentionGameId
    if (!matchesActiveScope && !matchesStoredScope) return

    installRecoveryAttentionRequired = requiresAttention
    installRecoveryAttentionProfileId = profileId.takeIf { requiresAttention }
    installRecoveryAttentionGameId = gameId.takeIf { requiresAttention }
}
