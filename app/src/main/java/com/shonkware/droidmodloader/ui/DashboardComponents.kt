package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.ui.theme.DmlDefaults
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlButtons

@Composable
fun HeaderCard(
    appName: String,
    versionLabel: String,
    onVersionTap: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = versionLabel,
                modifier = Modifier.clickable { onVersionTap() }
            )
        }
    }
}

@Composable
fun StatusCard(
    activeProfileName: String,
    selectedGameId: String,
    selectedTreeUriText: String,
    selectedRootTreeUriText: String,
    realDeployEnabled: Boolean,
    lastOperationStatus: String,
    summaryText: String,
    onOpenProfileDialog: () -> Unit
) {
    val dataTargetReady =
        selectedTreeUriText.isNotBlank() &&
                selectedTreeUriText != "No folder selected"

    val rootTargetReady =
        selectedRootTreeUriText.isNotBlank() &&
                selectedRootTreeUriText != "No root folder selected"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Profile: $activeProfileName",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Game: $selectedGameId",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                DmlButtons.Secondary(
                    text = "Manage",
                    onClick = onOpenProfileDialog
                )
            }

            Text(
                text = if (realDeployEnabled) {
                    "Deploy mode: Real target folders"
                } else {
                    "Deploy mode: Test output folder"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (dataTargetReady) {
                    "Data target: selected"
                } else {
                    "Data target: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (rootTargetReady) {
                    "Game Root target: selected"
                } else {
                    "Game Root target: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (!rootTargetReady) {
                Text(
                    text = "Pick Game Root if you use SKSE, NVSE, ENB, DLL loaders, or root EXE files.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text("Status: $lastOperationStatus")

            if (summaryText.isNotBlank()) {
                Text(summaryText)
            }
        }
    }
}

@Composable
fun QuickStartCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Quick Start", fontWeight = FontWeight.Bold)

            Text("1. Pick the game Data folder.")
            Text("2. Pick Game Root too if the mod uses SKSE, NVSE, ENB, DLLs, or root EXE files.")
            Text("3. Import a mod archive.")
            Text("4. Check the mod list and plugin list.")
            Text("5. Deploy.")
            Text("6. Write plugin files if needed.")
            Text("7. Share logs if something looks wrong.")
        }
    }
}

@Composable
fun MainActionsCard(
    operationInProgress: Boolean,
    onImportArchive: () -> Unit,
    onDeployMods: () -> Unit,
    onWriteLoadOrderFiles: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderHot)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Main Actions", fontWeight = FontWeight.Bold)

            DmlButtons.Primary(
                text = "Import Mod Archive",
                enabled = !operationInProgress,
                onClick = onImportArchive,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Deploy Mods",
                enabled = !operationInProgress,
                onClick = onDeployMods,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Write Plugin Files",
                enabled = !operationInProgress,
                onClick = onWriteLoadOrderFiles,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ModsCard(
    mods: List<Mod>,
    modContentIndexes: Map<String, ModContentIndex>,
    onToggleMod: (String) -> Unit,
    onMoveModUp: (String) -> Unit,
    onMoveModDown: (String) -> Unit,
    onDeleteMod: (Mod) -> Unit,
    onViewModFiles: (String) -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenOverwriteFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mods", fontWeight = FontWeight.Bold)

                DmlButtons.Secondary(
                    text = "Fullscreen",
                    onClick = onOpenFullscreen
                )
            }

            DmlButtons.Secondary(
                text = "Open Overwrite Folder",
                onClick = onOpenOverwriteFolder,
                modifier = Modifier.fillMaxWidth()
            )

            if (mods.isEmpty()) {
                Text("No installed mods found.")
            } else {
                mods.sortedBy { it.priority }.forEach { mod ->
                    CompactModRow(
                        mod = mod,
                        contentIndex = modContentIndexes[mod.id],
                        onToggleMod = onToggleMod,
                        onMoveModUp = onMoveModUp,
                        onMoveModDown = onMoveModDown,
                        onDeleteMod = onDeleteMod,
                        onViewModFiles = onViewModFiles
                    )
                }
            }
        }
    }
}

@Composable
fun CompactModRow(
    mod: Mod,
    contentIndex: ModContentIndex?,
    onToggleMod: (String) -> Unit,
    onMoveModUp: (String) -> Unit,
    onMoveModDown: (String) -> Unit,
    onDeleteMod: (Mod) -> Unit,
    onViewModFiles: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.raisedCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = mod.enabled,
                    onCheckedChange = { onToggleMod(mod.id) }
                )

                Text(
                    text = mod.priority.toString().padStart(3, '0'),
                    fontWeight = FontWeight.Bold
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.name,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = mod.modType.toString(),
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (contentIndex != null) {
                        Text(
                            text = "Data ${contentIndex.dataFiles.size} | Root ${contentIndex.gameRootFiles.size} | Plugins ${contentIndex.plugins.size} | Optional ${contentIndex.optionalModules.size}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (contentIndex.hasGameRootFiles) {
                            Text(
                                text = "Contains Game Root files. Pick Game Root Folder before deploying SKSE/NVSE/ENB-style mods.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (
                        contentIndex != null &&
                        contentIndex.deployableFiles.isEmpty() &&
                        contentIndex.plugins.isEmpty()
                    ) {
                        Text(
                            text = "Warning: no deployable game files detected",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "More")
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DmlButtons.Secondary(
                            text = "Up",
                            onClick = { onMoveModUp(mod.id) }
                        )

                        DmlButtons.Secondary(
                            text = "Down",
                            onClick = { onMoveModDown(mod.id) }
                        )
                    }

                    DmlButtons.Secondary(
                        text = "View Files",
                        onClick = { onViewModFiles(mod.id) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    DmlButtons.Danger(
                        text = "Delete",
                        onClick = { onDeleteMod(mod) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Path: ${mod.installPath}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (contentIndex != null) {
                        ModContentSummary(contentIndex)
                    }
                }
            }
        }
    }
}

@Composable
fun ModContentSummary(
    contentIndex: ModContentIndex
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Content Index", fontWeight = FontWeight.Bold)

        Text("Deployable files: ${contentIndex.deployableFiles.size}")
        Text("Data-scope files: ${contentIndex.dataFiles.size}")
        Text("Game root files: ${contentIndex.gameRootFiles.size}")
        Text("Plugins: ${contentIndex.plugins.size}")
        Text("Archives: ${contentIndex.archives.size}")
        Text("Config files: ${contentIndex.configs.size}")
        Text("Setup-only files: ${contentIndex.setupOnlyFiles.size}")
        Text("Documentation files: ${contentIndex.documentationFiles.size}")
        Text("Optional modules: ${contentIndex.optionalModules.size}")
        Text("Ignored files: ${contentIndex.ignoredFiles.size}")
        Text("Unknown files: ${contentIndex.unknownFiles.size}")

        if (contentIndex.plugins.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Plugins:", fontWeight = FontWeight.Bold)
            contentIndex.plugins.take(5).forEach {
                Text(
                    text = it.normalizedPath,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (contentIndex.archives.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Archives:", fontWeight = FontWeight.Bold)
            contentIndex.archives.take(5).forEach {
                Text(
                    text = it.normalizedPath,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (contentIndex.configs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Config files:", fontWeight = FontWeight.Bold)
            contentIndex.configs.take(5).forEach {
                Text(
                    text = it.normalizedPath,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (contentIndex.gameRootFiles.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Game Root files:", fontWeight = FontWeight.Bold)
            contentIndex.gameRootFiles.take(8).forEach {
                Text(
                    text = it.normalizedPath,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (contentIndex.optionalModules.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Optional:", fontWeight = FontWeight.Bold)
            contentIndex.optionalModules.take(5).forEach {
                Text(
                    text = "${it.normalizedPath} — ${it.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (contentIndex.unknownFiles.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Unknown:", fontWeight = FontWeight.Bold)
            contentIndex.unknownFiles.take(5).forEach {
                Text(
                    text = "${it.normalizedPath} — ${it.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun PluginsCard(
    plugins: List<PluginEntry>,
    onTogglePlugin: (String) -> Unit,
    onMovePluginUp: (String) -> Unit,
    onMovePluginDown: (String) -> Unit,
    onOpenFullscreen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Plugins", fontWeight = FontWeight.Bold)

                DmlButtons.Secondary(
                    text = "Fullscreen",
                    onClick = onOpenFullscreen
                )
            }

            if (plugins.isEmpty()) {
                Text("No plugins found.")
            } else {
                plugins.sortedBy { it.priority }.forEach { plugin ->
                    PluginRow(
                        plugin = plugin,
                        onTogglePlugin = onTogglePlugin,
                        onMovePluginUp = onMovePluginUp,
                        onMovePluginDown = onMovePluginDown
                    )
                }
            }
        }
    }
}

@Composable
fun PluginRow(
    plugin: PluginEntry,
    onTogglePlugin: (String) -> Unit,
    onMovePluginUp: (String) -> Unit,
    onMovePluginDown: (String) -> Unit
) {
    val sourceLabel = when (plugin.sourceType) {
        "base_game" -> "Base Game"
        "official_dlc" -> "Official DLC"
        "unmanaged_data" -> "Unmanaged Data Folder"
        "managed_mod" -> plugin.sourceModName
        else -> plugin.sourceModName
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.raisedCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${plugin.priority.toString().padStart(3, '0')} | ${plugin.pluginName} | ${plugin.pluginType}",
                fontWeight = FontWeight.Bold
            )

            Text(if (plugin.enabled) "Enabled" else "Disabled")
            Text("From: $sourceLabel")

            if (plugin.sourceType == "unmanaged_data") {
                Text(
                    text = "Detected in target Data folder",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (plugin.locked) {
                Text(
                    text = "Locked official plugin",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DmlButtons.Secondary(
                    text = if (plugin.enabled) "Disable" else "Enable",
                    enabled = !plugin.locked,
                    onClick = { onTogglePlugin(plugin.normalizedPath) }
                )

                DmlButtons.Secondary(
                    text = "Up",
                    enabled = !plugin.locked,
                    onClick = { onMovePluginUp(plugin.normalizedPath) }
                )

                DmlButtons.Secondary(
                    text = "Down",
                    enabled = !plugin.locked,
                    onClick = { onMovePluginDown(plugin.normalizedPath) }
                )
            }
        }
    }
}

@Composable
fun DeploymentSettingsCard(
    selectedTreeUriText: String,
    selectedRootTreeUriText: String,
    realDeployEnabled: Boolean,
    secondScreenEnabled: Boolean,
    onRealDeployChanged: (Boolean) -> Unit,
    onPickTargetFolder: () -> Unit,
    onPickRootTargetFolder: () -> Unit,
    onSaveSettings: () -> Unit,
    onToggleSecondScreen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Deploy Targets", fontWeight = FontWeight.Bold)

            Text(
                text = "Data folder: $selectedTreeUriText",
                style = MaterialTheme.typography.bodySmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = realDeployEnabled,
                    onCheckedChange = onRealDeployChanged
                )

                Spacer(Modifier.width(8.dp))

                Text("Deploy into selected game folders")
            }

            Text(
                text = "Pick the game's Data folder. Most mod files deploy here.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Pick Data Folder",
                onClick = onPickTargetFolder,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Game Root folder: $selectedRootTreeUriText",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Pick the main game folder, not Data. Needed for script extenders, DLL loaders, ENB files, and root EXE files.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Pick Game Root Folder",
                onClick = onPickRootTargetFolder,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Save Settings",
                onClick = onSaveSettings,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = if (secondScreenEnabled) {
                    "Disable Second Screen Plugin Display"
                } else {
                    "Enable Second Screen Plugin Display"
                },
                onClick = onToggleSecondScreen,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GameOptionChips(
    gameOptions: List<String>,
    selectedGameId: String,
    onSelectGame: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gameOptions.chunked(2).forEach { rowGames ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowGames.forEach { gameId ->
                    FilterChip(
                        selected = selectedGameId == gameId,
                        onClick = { onSelectGame(gameId) },
                        label = { Text(gameId) }
                    )
                }
            }
        }
    }
}

@Composable
fun ReportCard(
    logText: String,
    onShareLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Report & Diagnostics", fontWeight = FontWeight.Bold)

            DmlButtons.Secondary(
                text = "Share Logs",
                onClick = onShareLogs,
                modifier = Modifier.fillMaxWidth()
            )

            Text(logText)
        }
    }
}

@Composable
fun DeveloperToolsCard(
    operationInProgress: Boolean,
    onBuildResolvedDataGraph: () -> Unit,
    onBuildDeploymentPlan: () -> Unit,
    onRepairV050Artifacts: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Developer Tools", fontWeight = FontWeight.Bold)

            Text(
                text = "Advanced tools for testing and repair. Most users should not need these every day.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                enabled = !operationInProgress,
                onClick = onBuildResolvedDataGraph,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Build Resolved Graph Summary")
            }

            Text(
                text = "Developer check: builds the current profile's resolved game view summary without deploying or modifying target files.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                enabled = !operationInProgress,
                onClick = onBuildDeploymentPlan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Build Deploy Plan Summary")
            }

            Text(
                text = "Developer check: compares the current resolved winners against the saved deploy manifests. No files are changed.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Advanced repair tools. Use these only when troubleshooting beta builds.",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                enabled = !operationInProgress,
                onClick = onRepairV050Artifacts,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Repair v0.5.0-beta Artifacts")
            }

            Text(
                text = "Repairs earlier beta artifacts such as .ini.txt/.xml.txt files, duplicate folders like sound(1), and incorrectly wrapped installed mod folders. Existing correct files are not overwritten.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun SetupScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("First Setup", fontWeight = FontWeight.Bold)
                    Text("Create your first game profile before using the mod manager.")

                    OutlinedTextField(
                        value = state.profileNameText,
                        onValueChange = actions.onProfileNameChanged,
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    GameOptionChips(
                        gameOptions = state.gameOptions,
                        selectedGameId = state.setupGameId,
                        onSelectGame = actions.onSetupGameChanged
                    )

                    Text("Target folder: ${state.selectedTreeUriText}")
                    Text(
                        text = "Pick the Data folder of your installed game.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = actions.onPickTargetFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick Target Folder")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.setupRealDeployEnabled,
                            onCheckedChange = actions.onSetupRealDeployChanged
                        )

                        Spacer(Modifier.width(8.dp))

                        Text("Write to Real Target Folder")
                    }

                    Button(
                        onClick = actions.onCompleteSetup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Profile")
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileManagerDialog(
    profiles: List<GameProfile>,
    activeProfileId: String?,
    newProfileNameText: String,
    newProfileGameId: String,
    newProfileTreeUriText: String,
    newProfileRealDeployEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onNewProfileNameChanged: (String) -> Unit,
    onNewProfileGameChanged: (String) -> Unit,
    onPickNewProfileTargetFolder: () -> Unit,
    onNewProfileRealDeployChanged: (Boolean) -> Unit,
    onCreateAdditionalProfile: () -> Unit,
    onClose: () -> Unit,
    gameOptions: List<String>
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        },
        title = {
            Text("Manage Profiles")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Switch Profile", fontWeight = FontWeight.Bold)

                if (profiles.isEmpty()) {
                    Text("No profiles found.")
                } else {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = profile.profileId == activeProfileId,
                                onClick = { onSelectProfile(profile.profileId) },
                                label = { Text(profile.profileName) }
                            )

                            Button(
                                onClick = { onDeleteProfile(profile.profileId) }
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text("Add Profile", fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = newProfileNameText,
                    onValueChange = onNewProfileNameChanged,
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                GameOptionChips(
                    gameOptions = gameOptions,
                    selectedGameId = newProfileGameId,
                    onSelectGame = onNewProfileGameChanged
                )

                Text("Selected folder: $newProfileTreeUriText")
                Text(
                    text = "Pick the Data folder of your installed game.",
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = onPickNewProfileTargetFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick Target Folder")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = newProfileRealDeployEnabled,
                        onCheckedChange = onNewProfileRealDeployChanged
                    )

                    Spacer(Modifier.width(8.dp))

                    Text("Write to Real Target Folder")
                }

                Button(
                    onClick = onCreateAdditionalProfile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Profile")
                }
            }
        }
    )
}

@Composable
fun InstallerChoiceDialog(
    prepared: PreparedArchiveInstall,
    selectedOptionIds: Set<String>,
    fullscreen: Boolean,
    onToggleOption: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Install Options",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                Text("Archive: ${prepared.archiveName}")
                Text("Installer type: ${prepared.plan.installerType}")

                prepared.plan.warnings.forEach { warning ->
                    Text(
                        text = "Warning: $warning",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                prepared.plan.groups.forEach { group ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = DmlDefaults.raisedCardColors(),
                        border = BorderStroke(1.dp, DmlColors.BorderDim)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(group.name, fontWeight = FontWeight.Bold)

                            group.options.forEach { option ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = option.required || selectedOptionIds.contains(option.id),
                                        enabled = !option.required,
                                        onCheckedChange = { onToggleOption(option.id) }
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(option.name)

                                        if (option.required) {
                                            Text("Required", style = MaterialTheme.typography.bodySmall)
                                        }

                                        if (option.description.isNotBlank()) {
                                            Text(option.description, style = MaterialTheme.typography.bodySmall)
                                        }

                                        Text("Source: ${option.sourcePath}", style = MaterialTheme.typography.bodySmall)

                                        if (option.destinationPath.isNotBlank()) {
                                            Text("Destination: ${option.destinationPath}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }

                    Button(onClick = onConfirm) {
                        Text("Install Selected")
                    }
                }
            }
        }
    }
}

@Composable
fun ModFilePreviewDialog(
    preview: ModFilePreview,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Files: ${preview.modName}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                Text("Winning/deployed: ${preview.winningFiles.size}")
                Text("Overwritten: ${preview.overwrittenFiles.size}")
                Text("Data-scope files: ${preview.dataFiles.size}")
                Text("Game root files: ${preview.gameRootFiles.size}")
                Text("Plugins: ${preview.pluginFiles.size}")
                Text("Archives: ${preview.archiveFiles.size}")
                Text("Configs: ${preview.configFiles.size}")

                if (preview.gameRootFiles.isNotEmpty()) {
                    Text(
                        text = "This mod contains Game Root files. These deploy to the selected Game Root Folder, not the Data folder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (preview.folderSummaries.isEmpty()) {
                    Text("No files found.")
                } else {
                    preview.folderSummaries.forEach { summary ->
                        FolderSummaryRow(summary)
                    }
                }

                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun OverwriteDialog(
    entries: List<OverwriteEntry>,
    baselineExists: Boolean,
    message: String,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Overwrite Folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "MO2-style catch-all for files created or changed after the target Data folder was indexed.",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(message)

                if (!baselineExists) {
                    Text(
                        text = "No Data baseline is available yet. Droid Mod Loader will create one automatically after a target folder is selected.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (entries.isEmpty()) {
                    Text("Overwrite is clean.")
                } else {
                    entries.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = DmlDefaults.raisedCardColors(),
                            border = BorderStroke(1.dp, DmlColors.BorderDim)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "[${entry.status}] ${entry.normalizedPath}",
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = entry.reason,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (entry.sizeBytes != null) {
                                    Text(
                                        text = "Size: ${entry.sizeBytes} bytes",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun DeployRecoveryWarningCard(
    warningText: String,
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    if (warningText.isBlank()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderHot)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Previous deploy may need review",
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Droid Mod Loader found a deploy journal that was not marked completed. This build will warn only. Recovery actions are coming later.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DmlButtons.Secondary(
                    text = "View Details",
                    onClick = onViewDetails
                )

                DmlButtons.Secondary(
                    text = "Dismiss",
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
fun RecoveryToolsCard(
    operationInProgress: Boolean,
    deployRecoveryWarningText: String,
    onViewLastDeployJournal: () -> Unit,
    onMarkDeployRecoveryReviewed: () -> Unit,
    onRequestForceFullRedeploy: () -> Unit,
    onBuildFullRedeployPlan: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recovery Tools",
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Tools for reviewing deploy state after a failed or interrupted deploy. Only journal review is active right now.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "View Last Deploy Journal",
                enabled = !operationInProgress,
                onClick = onViewLastDeployJournal,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Build Full Redeploy Plan",
                enabled = !operationInProgress,
                onClick = onBuildFullRedeployPlan,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Shows what DML would rewrite if you rebuilt the deployed game folder from the current mod list. This does not change files.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Mark Warning Reviewed",
                enabled = !operationInProgress && deployRecoveryWarningText.isNotBlank(),
                onClick = onMarkDeployRecoveryReviewed,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Coming later:",
                fontWeight = FontWeight.Bold
            )

            DmlButtons.Secondary(
                text = "Resume Interrupted Deploy",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Rollback Last Deploy",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Danger(
                text = "Force Full Redeploy",
                enabled = !operationInProgress,
                onClick = onRequestForceFullRedeploy,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Rewrites all current managed files from the active mod list. Use this after an interrupted deploy or if the target folder looks out of sync.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Rebuild Deploy Manifest",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Rebuild Data Baseline",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Disabled tools are planned recovery actions. They are shown here early so testers know where this system is going.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun FolderSummaryRow(
    summary: ModFileFolderSummary
) {
    val background = when (summary.dominantStatus) {
        ModFilePreviewStatus.WINNING -> DmlColors.Green.copy(alpha = 0.18f)
        ModFilePreviewStatus.OVERWRITTEN -> DmlColors.RedDark.copy(alpha = 0.55f)
        ModFilePreviewStatus.PLUGIN -> DmlColors.SurfaceRaised
        ModFilePreviewStatus.ARCHIVE -> DmlColors.SurfaceRaised
        ModFilePreviewStatus.CONFIG -> DmlColors.Amber.copy(alpha = 0.18f)
        else -> DmlColors.SurfaceRaised
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = background,
            contentColor = DmlColors.Text
        ),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = summary.displayName,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = buildString {
                    append("Total: ${summary.totalCount}")

                    if (summary.dataFileCount > 0) append(" | Data: ${summary.dataFileCount}")
                    if (summary.gameRootFileCount > 0) append(" | Root: ${summary.gameRootFileCount}")
                    if (summary.winningCount > 0) append(" | Winning: ${summary.winningCount}")
                    if (summary.overwrittenCount > 0) append(" | Overwritten: ${summary.overwrittenCount}")
                    if (summary.notDeployedCount > 0) append(" | Not deployed: ${summary.notDeployedCount}")
                    if (summary.pluginCount > 0) append(" | Plugins: ${summary.pluginCount}")
                    if (summary.archiveCount > 0) append(" | Archives: ${summary.archiveCount}")
                    if (summary.configCount > 0) append(" | Configs: ${summary.configCount}")
                    if (summary.setupCount > 0) append(" | Setup: ${summary.setupCount}")
                    if (summary.documentationCount > 0) append(" | Docs: ${summary.documentationCount}")
                    if (summary.optionalCount > 0) append(" | Optional: ${summary.optionalCount}")
                    if (summary.ignoredCount > 0) append(" | Ignored: ${summary.ignoredCount}")
                    if (summary.unknownCount > 0) append(" | Unknown: ${summary.unknownCount}")
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ModsPanelDialog(
    mods: List<Mod>,
    modContentIndexes: Map<String, ModContentIndex>,
    onToggleMod: (String) -> Unit,
    onMoveModUp: (String) -> Unit,
    onMoveModDown: (String) -> Unit,
    onDeleteMod: (Mod) -> Unit,
    onViewModFiles: (String) -> Unit,
    onApplyModOrder: (List<String>) -> Unit,
    onOpenOverwriteFolder: () -> Unit,
    onClose: () -> Unit
) {
    var orderedMods by remember(mods) {
        mutableStateOf(mods.sortedBy { it.priority })
    }

    fun moveMod(modId: String, direction: Int) {
        val index = orderedMods.indexOfFirst { it.id == modId }
        if (index == -1) return

        val target = (index + direction).coerceIn(0, orderedMods.lastIndex)
        if (target == index) return

        val mutable = orderedMods.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedMods = mutable
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mods",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenOverwriteFolder) {
                            Text("Overwrite")
                        }

                        Button(onClick = {
                            onApplyModOrder(orderedMods.map { it.id })
                        }) {
                            Text("Save Order")
                        }

                        Button(onClick = onClose) {
                            Text("Close")
                        }
                    }
                }

                Text(
                    text = "Long-press and drag ☰ to reorder. Tap Save Order when done.",
                    style = MaterialTheme.typography.bodySmall
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = orderedMods,
                        key = { _, mod -> mod.id }
                    ) { _, mod ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DragHandle(
                                onStep = { direction ->
                                    moveMod(mod.id, direction)
                                },
                                onDragFinished = {
                                    onApplyModOrder(orderedMods.map { it.id })
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                CompactModRow(
                                    mod = mod,
                                    contentIndex = modContentIndexes[mod.id],
                                    onToggleMod = onToggleMod,
                                    onMoveModUp = onMoveModUp,
                                    onMoveModDown = onMoveModDown,
                                    onDeleteMod = onDeleteMod,
                                    onViewModFiles = onViewModFiles
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PluginsPanelDialog(
    plugins: List<PluginEntry>,
    onTogglePlugin: (String) -> Unit,
    onMovePluginUp: (String) -> Unit,
    onMovePluginDown: (String) -> Unit,
    onApplyPluginOrder: (List<String>) -> Unit,
    onClose: () -> Unit
) {
    var orderedPlugins by remember(plugins) {
        mutableStateOf(plugins.sortedBy { it.priority })
    }

    fun movePlugin(pluginPath: String, direction: Int) {
        val index = orderedPlugins.indexOfFirst { it.normalizedPath == pluginPath }
        if (index == -1) return

        val plugin = orderedPlugins[index]
        if (plugin.locked) return

        val target = (index + direction).coerceIn(0, orderedPlugins.lastIndex)
        if (target == index) return
        if (orderedPlugins[target].locked) return

        val mutable = orderedPlugins.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedPlugins = mutable
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Plugins",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onApplyPluginOrder(orderedPlugins.map { it.normalizedPath })
                        }) {
                            Text("Save Order")
                        }

                        Button(onClick = onClose) {
                            Text("Close")
                        }
                    }
                }

                Text(
                    text = "Long-press and drag ☰ to reorder unlocked plugins. Official plugins stay pinned.",
                    style = MaterialTheme.typography.bodySmall
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = orderedPlugins,
                        key = { _, plugin -> plugin.normalizedPath }
                    ) { _, plugin ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DragHandle(
                                enabled = !plugin.locked,
                                onStep = { direction ->
                                    movePlugin(plugin.normalizedPath, direction)
                                },
                                onDragFinished = {
                                    onApplyPluginOrder(orderedPlugins.map { it.normalizedPath })
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                PluginRow(
                                    plugin = plugin,
                                    onTogglePlugin = onTogglePlugin,
                                    onMovePluginUp = onMovePluginUp,
                                    onMovePluginDown = onMovePluginDown
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandle(
    enabled: Boolean = true,
    onStep: (Int) -> Unit,
    onDragFinished: () -> Unit
) {
    val thresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    var accumulatedY by remember { mutableStateOf(0f) }

    Text(
        text = "☰",
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(8.dp)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                accumulatedY = 0f
                            },
                            onDragEnd = {
                                accumulatedY = 0f
                                onDragFinished()
                            },
                            onDragCancel = {
                                accumulatedY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedY += dragAmount.y

                                while (accumulatedY > thresholdPx) {
                                    onStep(1)
                                    accumulatedY -= thresholdPx
                                }

                                while (accumulatedY < -thresholdPx) {
                                    onStep(-1)
                                    accumulatedY += thresholdPx
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    )
}
