package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewEntry

@Composable
fun HeaderCard(
    appName: String,
    versionLabel: String,
    onVersionTap: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
    lastOperationStatus: String,
    summaryText: String,
    onOpenProfileDialog: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Profile: $activeProfileName",
                    fontWeight = FontWeight.Bold
                )

                Button(onClick = onOpenProfileDialog) {
                    Text("Manage")
                }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Start", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("1. Pick target folder")
            Text("2. Import archive")
            Text("3. Deploy mods")
            Text("4. Write load order files if needed")
            Text("5. Share logs if something fails")
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Main Actions", fontWeight = FontWeight.Bold)

            Button(
                enabled = !operationInProgress,
                onClick = onImportArchive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Archive")
            }

            Button(
                enabled = !operationInProgress,
                onClick = onDeployMods,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Deploy Mods")
            }

            Button(
                enabled = !operationInProgress,
                onClick = onWriteLoadOrderFiles,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Write Load Order Files")
            }
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
    onViewModFiles: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Mods", fontWeight = FontWeight.Bold)

            if (mods.isEmpty()) {
                Text("No installed mods found.")
            } else {
                mods.forEach { mod ->
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            text = "Files ${contentIndex.deployableFiles.size} | Plugins ${contentIndex.plugins.size} | Optional ${contentIndex.optionalModules.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onMoveModUp(mod.id) }) {
                            Text("Up")
                        }

                        Button(onClick = { onMoveModDown(mod.id) }) {
                            Text("Down")
                        }
                    }

                    Button(
                        onClick = { onViewModFiles(mod.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Files")
                    }

                    Button(
                        onClick = { onDeleteMod(mod) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete")
                    }

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
    onMovePluginDown: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Plugins", fontWeight = FontWeight.Bold)

            if (plugins.isEmpty()) {
                Text("No plugins found.")
            } else {
                plugins.forEach { plugin ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

                            val sourceLabel = when (plugin.sourceType) {
                                "base_game" -> "Base Game"
                                "official_dlc" -> "Official DLC"
                                "unmanaged_data" -> "Unmanaged Data Folder"
                                "managed_mod" -> plugin.sourceModName
                                else -> plugin.sourceModName
                            }

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
                                Button(
                                    enabled = !plugin.locked,
                                    onClick = { onTogglePlugin(plugin.normalizedPath) }
                                ) {
                                    Text(if (plugin.enabled) "Disable" else "Enable")
                                }

                                Button(
                                    enabled = !plugin.locked,
                                    onClick = { onMovePluginUp(plugin.normalizedPath) }
                                ) {
                                    Text("Up")
                                }

                                Button(
                                    enabled = !plugin.locked,
                                    onClick = { onMovePluginDown(plugin.normalizedPath) }
                                ) {
                                    Text("Down")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeploymentSettingsCard(
    gameOptions: List<String>,
    selectedGameId: String,
    onSelectGame: (String) -> Unit,
    selectedTreeUriText: String,
    realDeployEnabled: Boolean,
    onRealDeployChanged: (Boolean) -> Unit,
    onPickTargetFolder: () -> Unit,
    onSaveSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Deployment & Settings", fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gameOptions.forEach { gameId ->
                    FilterChip(
                        selected = selectedGameId == gameId,
                        onClick = { onSelectGame(gameId) },
                        label = { Text(gameId) }
                    )
                }
            }

            Text("Selected folder: $selectedTreeUriText")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = realDeployEnabled,
                    onCheckedChange = onRealDeployChanged
                )

                Spacer(Modifier.width(8.dp))

                Text("Write to Real Target Folder")
            }

            Button(
                onClick = onPickTargetFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pick Target Folder")
            }

            Button(
                onClick = onSaveSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}

@Composable
fun ReportCard(
    logText: String,
    onShareLogs: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Report & Diagnostics", fontWeight = FontWeight.Bold)

            Button(
                onClick = onShareLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share Logs")
            }

            Text(logText)
        }
    }
}

@Composable
fun DeveloperToolsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Developer Tools", fontWeight = FontWeight.Bold)
            Text("Keep your old lesson/test actions here later if needed.")
        }
    }
}

@Composable
fun SetupScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("skyrim_le", "fallout_nv").forEach { gameId ->
                            FilterChip(
                                selected = state.setupGameId == gameId,
                                onClick = { actions.onSetupGameChanged(gameId) },
                                label = { Text(gameId) }
                            )
                        }
                    }

                    Text("Target folder: ${state.selectedTreeUriText}")

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
    onClose: () -> Unit
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("skyrim_le", "fallout_nv").forEach { gameId ->
                        FilterChip(
                            selected = newProfileGameId == gameId,
                            onClick = { onNewProfileGameChanged(gameId) },
                            label = { Text(gameId) }
                        )
                    }
                }

                Text("Selected folder: $newProfileTreeUriText")

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
        properties = DialogProperties(usePlatformDefaultWidth = !fullscreen)
    ) {
        Card(
            modifier = if (fullscreen) {
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            }
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
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                            Text(
                                                text = "Required",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        if (option.description.isNotBlank()) {
                                            Text(
                                                text = option.description,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        Text(
                                            text = "Source: ${option.sourcePath}",
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        if (option.destinationPath.isNotBlank()) {
                                            Text(
                                                text = "Destination: ${option.destinationPath}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onToggleFullscreen) {
                        Text(if (fullscreen) "Windowed" else "Fullscreen")
                    }

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
        properties = DialogProperties(usePlatformDefaultWidth = !fullscreen)
    ) {
        Card(
            modifier = if (fullscreen) {
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            }
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
                Text("Plugins: ${preview.pluginFiles.size}")
                Text("Archives: ${preview.archiveFiles.size}")
                Text("Configs: ${preview.configFiles.size}")
                Text("Setup/docs/optional/unknown: ${preview.setupFiles.size + preview.documentationFiles.size + preview.optionalFiles.size + preview.unknownFiles.size}")

                FilePreviewSection("Winning / Deployed", preview.winningFiles)
                FilePreviewSection("Overwritten", preview.overwrittenFiles)
                FilePreviewSection("Plugins", preview.pluginFiles)
                FilePreviewSection("Archives", preview.archiveFiles)
                FilePreviewSection("Config Files", preview.configFiles)
                FilePreviewSection("Setup Files", preview.setupFiles)
                FilePreviewSection("Documentation", preview.documentationFiles)
                FilePreviewSection("Optional", preview.optionalFiles)
                FilePreviewSection("Unknown", preview.unknownFiles)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onToggleFullscreen) {
                        Text(if (fullscreen) "Windowed" else "Fullscreen")
                    }

                    Button(onClick = onClose) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun FilePreviewSection(
    title: String,
    entries: List<ModFilePreviewEntry>
) {
    if (entries.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)

            entries.take(50).forEach { entry ->
                val suffix = when {
                    entry.winningModName != null && title == "Overwritten" ->
                        " → winner: ${entry.winningModName}"

                    else -> ""
                }

                Text(
                    text = entry.normalizedPath + suffix,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (entries.size > 50) {
                Text(
                    text = "...and ${entries.size - 50} more",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}