package com.shonkware.droidmodloader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shonkware.droidmodloader.engine.install.InstallerGroup
import com.shonkware.droidmodloader.engine.install.InstallerGroupType
import com.shonkware.droidmodloader.engine.install.InstallerOption
import com.shonkware.droidmodloader.engine.install.InstallerPlan
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import org.junit.Rule
import org.junit.Test

class OperationStatusPresentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun archiveLibraryShowsUpdatedStatusAndHidesBlankStatus() {
        var status by mutableStateOf("Importing archive...")

        composeRule.setContent {
            ArchiveLibraryPanelDialog(
                state = ArchiveBrowserUiState(),
                operationInProgress = false,
                archiveImportInProgress = false,
                searchText = "",
                listState = LazyListState(),
                onSearchTextChanged = {},
                onRefresh = {},
                onChangeFolder = {},
                onInstallArchive = {},
                onCancelArchiveImport = {},
                onClose = {},
                lastOperationStatus = status
            )
        }

        composeRule.onNodeWithText("Importing archive...").assertIsDisplayed()

        composeRule.runOnIdle {
            status = "Import archive failed: unsupported archive format."
        }

        composeRule.onNodeWithText(status).assertIsDisplayed()
        composeRule.onNodeWithText("Importing archive...").assertDoesNotExist()

        composeRule.runOnIdle { status = "" }
        composeRule.onNodeWithText("Import archive failed: unsupported archive format.")
            .assertDoesNotExist()
    }

    @Test
    fun installerChoiceShowsUpdatedStatus() {
        var status by mutableStateOf("Installing...")

        composeRule.setContent {
            InstallerChoiceDialog(
                prepared = samplePrepared(),
                selectedOptionIds = emptySet(),
                fullscreen = false,
                onToggleOption = {},
                onConfirm = {},
                onCancel = {},
                onToggleFullscreen = {},
                lastOperationStatus = status
            )
        }

        composeRule.onNodeWithText("Installing...").assertIsDisplayed()

        composeRule.runOnIdle { status = "Cancelling installer..." }

        composeRule.onNodeWithText(status).assertIsDisplayed()
        composeRule.onNodeWithText("Installing...").assertDoesNotExist()
    }

    @Test
    fun dashboardRetainsCompletionStatus() {
        var status by mutableStateOf("Deploying...")

        composeRule.setContent {
            StatusCard(
                activeProfileName = "Test",
                selectedGameId = "fallout_nv",
                dataTargetReady = true,
                rootTargetReady = true,
                realDeployEnabled = false,
                lastOperationStatus = status,
                summaryText = "",
                onOpenProfileDialog = {}
            )
        }

        composeRule.onNodeWithText("Status: Deploying...").assertIsDisplayed()

        composeRule.runOnIdle { status = "Deploy complete. (1 ms)" }

        composeRule.onNodeWithText("Status: $status").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Deploying...").assertDoesNotExist()
    }

    private fun samplePrepared(): PreparedArchiveInstall {
        return PreparedArchiveInstall(
            archivePath = "/archives/sample.zip",
            archiveName = "sample.zip",
            modName = "Sample Mod",
            sessionRootPath = "/session",
            extractedRootPath = "/session/extracted",
            installRootPath = "/session/install",
            plan = InstallerPlan(
                installerType = InstallerType.SIMPLE,
                displayName = "Simple",
                rootPath = "/session/install",
                groups = listOf(
                    InstallerGroup(
                        id = "group",
                        name = "Group",
                        type = InstallerGroupType.SELECT_ANY,
                        options = listOf(
                            InstallerOption(
                                id = "opt",
                                name = "Option",
                                sourcePath = "/session/install/opt"
                            )
                        )
                    )
                )
            )
        )
    }
}
