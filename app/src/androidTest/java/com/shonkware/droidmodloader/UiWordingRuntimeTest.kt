package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.shonkware.droidmodloader.attention.*
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState
import com.shonkware.droidmodloader.ui.*
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure synthetic presentation/callback tests; no ViewModel, engine, real folders or permissions. */
class UiWordingRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reportNamesMatchTheirActionsAndBusyState() {
        val calls = mutableListOf<String>()
        var busy by mutableStateOf(false)
        var checking by mutableStateOf(false)
        compose.setContent {
            DmlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReportCard("Synthetic log", { calls += "share" }, operationInProgress = busy,
                        integrityCheckInProgress = checking,
                        onVerifyManagedContent = { calls += "check" }, onCancelIntegrityCheck = { calls += "cancel" },
                        onRefreshDeploymentHealth = { calls += "refresh" }, onInspectStorage = { calls += "storage" },
                        onInspectEnvironment = { calls += "setup" })
                }
            }
        }
        compose.onNodeWithText("Deployment Summary: Not checked yet").assertIsDisplayed()
        for (label in listOf("Refresh Deployment Summary", "Check Game Setup", "Show Storage Info", "Check Recorded Files", "Share Logs")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        compose.onNodeWithText("Cancel File Check").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("refresh", "setup", "storage", "check", "share"), calls)
            busy = true
            checking = true
        }
        for (label in listOf("Refresh Deployment Summary", "Check Game Setup", "Show Storage Info", "Check Recorded Files")) {
            compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithText("Cancel File Check").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("cancel", calls.last()) }
    }

    @Test fun notificationsHeadingAndHideDoNotRunRecovery() {
        val notice = UiWordingFixtures.notice()
        val state = UiWordingFixtures.state().copy(appAttention = listOf(notice))
        var dismissed by mutableStateOf(emptySet<AppAttentionId>())
        val actions = mutableListOf<AppAttentionAction>()
        compose.setContent {
            DmlTheme {
                DroidModLoaderScreen(state.copy(dismissedAttentionIds = dismissed), UiWordingFixtures.actions().copy(
                    onAttentionAction = actions::add,
                    onDismissAttentionForSession = { dismissed = dismissed + it }))
            }
        }
        compose.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unfinished deployment").assertIsDisplayed()
        compose.onNodeWithText("View Details").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY), actions) }
        compose.onNodeWithText("Hide for Now").performScrollTo().performClick()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf(notice.id), dismissed)
            assertEquals(listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY), actions)
        }
    }

    @Test fun readableGameNamesKeepTheOriginalSelectionIds() {
        var selected: String? = null
        compose.setContent {
            DmlTheme {
                SetupScreen(UiWordingFixtures.state().copy(setupComplete = false),
                    UiWordingFixtures.actions().copy(onSetupGameChanged = { selected = it }))
            }
        }
        compose.onNodeWithText("Skyrim Legendary Edition").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fallout New Vegas").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("skyrim_le").assertDoesNotExist()
        compose.runOnIdle { assertEquals("fallout_nv", selected) }
    }

    @Test fun winningFilesAreNotPresentedAsAlreadyDeployed() {
        var closed = 0
        compose.setContent {
            DmlTheme {
                ModFilePreviewDialog(ModFilePreview("synthetic", "Synthetic mod", emptyList()), false, { closed++ }, {})
            }
        }
        compose.onNodeWithText("Winning files: 0").assertIsDisplayed()
        compose.onNodeWithText("Winning/deployed: 0").assertDoesNotExist()
        compose.onNodeWithText("Winners are chosen by mod order; this does not check which files are currently in the game folders.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }
}

/** Shared only by instrumentation tests and the private native capture producer. */
internal object UiWordingFixtures {
    fun state(): DashboardUiState = MutableMainActivityUiState().apply {
        setupComplete = true
        activeProfileId = "synthetic"
        activeProfileName = "Example (synthetic)"
        profileNameText = "Example (synthetic)"
        summaryText = "Synthetic example; no game files checked."
    }.toDashboardUiState(false, false,
        DirectFolderSelectionUiState(true, false, "Choose Folder", false, DirectFolderBrowserState()))

    fun notice() = AppAttention(AppAttentionId("synthetic-notification"), AppAttentionKind.DEPLOY_RECOVERY,
        AppAttentionSeverity.WARNING, actions = listOf(AppAttentionAction.VIEW_DEPLOY_RECOVERY,
            AppAttentionAction.MARK_DEPLOY_RECOVERY_REVIEWED))

    fun actions() = DashboardActions(
        onVersionTap = {}, onInstallMod = {}, onChooseArchiveFolder = {}, onDismissArchiveFolderSetup = {},
        onRefreshArchiveFolder = {}, onChangeArchiveFolder = {}, onInstallArchiveFromFolder = {},
        onDeployMods = {}, onWriteLoadOrderFiles = {}, onToggleMod = {}, onMoveModUp = {}, onMoveModDown = {},
        onDeleteMod = {}, onTogglePlugin = {}, onMovePluginUp = {}, onMovePluginDown = {}, onSelectGame = {},
        onPickTargetFolder = {}, onSaveSettings = {}, onShareLogs = {}, onProfileNameChanged = {},
        onSetupGameChanged = {}, onSetupTargetPathChanged = {}, onCompleteSetup = {}, onSelectProfile = {},
        onNewProfileNameChanged = {}, onNewProfileGameChanged = {}, onCreateAdditionalProfile = {},
        onOpenProfileDialog = {}, onCloseProfileDialog = {}, onPickNewProfileTargetFolder = {}, onDeleteProfile = {},
        onToggleInstallerOption = {}, onConfirmInstaller = {}, onCancelInstaller = {}, onToggleInstallerFullscreen = {},
        onViewModFiles = {}, onCloseModFilePreview = {}, onToggleModFilePreviewFullscreen = {}, onToggleSecondScreen = {},
        onOpenModsFullscreen = {}, onOpenPluginsFullscreen = {}, onCloseFullscreenPanel = {},
        onApplyModOrder = {}, onApplyPluginOrder = {}, onOpenOverwriteFolder = {}, onCloseOverwriteFolder = {},
        onCancelArchiveImport = {})
}
