package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.CardDefaults
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry

private data class UiFileTreeNode(
    val name: String,
    val path: String,
    val isFile: Boolean,
    val status: ModFilePreviewStatus?,
    val children: MutableList<UiFileTreeNode> = mutableListOf()
)

private fun buildPreviewTree(entries: List<ModFilePreviewEntry>): List<UiFileTreeNode> {
    val roots = mutableMapOf<String, UiFileTreeNode>()

    for (entry in entries) {
        val parts = entry.normalizedPath.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) continue

        var currentMap = roots
        var currentPath = ""

        for ((index, part) in parts.withIndex()) {
            currentPath = if (currentPath.isBlank()) part else "$currentPath/$part"
            val isFile = index == parts.lastIndex

            val node = currentMap.getOrPut(part) {
                UiFileTreeNode(
                    name = part,
                    path = currentPath,
                    isFile = isFile,
                    status = if (isFile) entry.status else null
                )
            }

            if (!isFile) {
                currentMap = node.children.associateBy { it.name }.toMutableMap()
                node.children.clear()
                node.children.addAll(currentMap.values)
            }
        }
    }

    return roots.values.sortedBy { it.name }
}

private fun groupPreviewByTopFolder(entries: List<ModFilePreviewEntry>): Map<String, List<ModFilePreviewEntry>> {
    return entries.groupBy { entry ->
        entry.normalizedPath.substringBefore("/", entry.normalizedPath)
    }.toSortedMap()
}


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
    onViewModFiles: (String) -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenOverwriteFolder: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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

                Button(onClick = onOpenFullscreen) {
                    Text("Open Fullscreen")
                }
            }

            Button(
                onClick = onOpenOverwriteFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Overwrite Folder")
            }

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
    onMovePluginDown: (String) -> Unit,
    onOpenFullscreen: () -> Unit
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
                Text("Plugins", fontWeight = FontWeight.Bold)

                Button(onClick = onOpenFullscreen) {
                    Text("Open Fullscreen")
                }
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

@Composable
fun DeploymentSettingsCard(
    gameOptions: List<String>,
    selectedGameId: String,
    onSelectGame: (String) -> Unit,
    selectedTreeUriText: String,
    realDeployEnabled: Boolean,
    secondScreenEnabled: Boolean,
    onRealDeployChanged: (Boolean) -> Unit,
    onPickTargetFolder: () -> Unit,
    onSaveSettings: () -> Unit,
    onToggleSecondScreen: () -> Unit
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
            Text("Pick 'Data' folder of your installed game")
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
            Button(
                onClick = onToggleSecondScreen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (secondScreenEnabled) {
                        "Disable Second Screen Plugin Display"
                    } else {
                        "Enable Second Screen Plugin Display"
                    }
                )
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
                Text("Plugins: ${preview.pluginFiles.size}")
                Text("Archives: ${preview.archiveFiles.size}")
                Text("Configs: ${preview.configFiles.size}")

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
                    text = "Files detected in the target Data folder that are not currently tracked by Droid Mod Loader.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (entries.isEmpty()) {
                    Text("No overwrite files detected.")
                } else {
                    entries.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(entry.normalizedPath, fontWeight = FontWeight.Bold)
                                Text(entry.reason, style = MaterialTheme.typography.bodySmall)

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
fun FolderSummaryRow(
    summary: ModFileFolderSummary
) {
    val background = when (summary.dominantStatus) {
        ModFilePreviewStatus.WINNING -> Color(0xFFE5F4E8)
        ModFilePreviewStatus.OVERWRITTEN -> Color(0xFFFFE5E5)
        ModFilePreviewStatus.PLUGIN -> Color(0xFFE8F0FE)
        ModFilePreviewStatus.ARCHIVE -> Color(0xFFE8F0FE)
        ModFilePreviewStatus.CONFIG -> Color(0xFFFFF4D6)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

@Composable
fun FullscreenModsPanel(
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

    fun moveMod(index: Int, direction: Int) {
        val target = (index + direction).coerceIn(0, orderedMods.lastIndex)
        if (target == index) return

        val mutable = orderedMods.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedMods = mutable
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                ) { index, mod ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DragHandle(
                            onStep = { direction ->
                                moveMod(index, direction)
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

@Composable
fun FullscreenPluginsPanel(
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

    fun movePlugin(index: Int, direction: Int) {
        val plugin = orderedPlugins.getOrNull(index) ?: return
        if (plugin.locked) return

        val target = (index + direction).coerceIn(0, orderedPlugins.lastIndex)
        if (target == index) return
        if (orderedPlugins[target].locked) return

        val mutable = orderedPlugins.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        orderedPlugins = mutable
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                ) { index, plugin ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DragHandle(
                            enabled = !plugin.locked,
                            onStep = { direction ->
                                movePlugin(index, direction)
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

