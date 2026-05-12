package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

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
    lastOperationStatus: String,
    summaryText: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Status", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(lastOperationStatus)
            Spacer(Modifier.height(8.dp))
            Text(summaryText)
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
                onClick = onImportArchive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Archive")
            }

            Button(
                onClick = onDeployMods,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Deploy Mods")
            }

            Button(
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
    onToggleMod: (String) -> Unit,
    onMoveModUp: (String) -> Unit,
    onMoveModDown: (String) -> Unit,
    onDeleteMod: (Mod) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Mods", fontWeight = FontWeight.Bold)

            if (mods.isEmpty()) {
                Text("No installed mods found.")
            } else {
                mods.forEach { mod ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${mod.priority} | ${mod.name} | ${mod.modType}")
                            Text(if (mod.enabled) "Enabled" else "Disabled")

                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onToggleMod(mod.id) }) {
                                    Text(if (mod.enabled) "Disable" else "Enable")
                                }
                                Button(onClick = { onMoveModUp(mod.id) }) {
                                    Text("Up")
                                }
                                Button(onClick = { onMoveModDown(mod.id) }) {
                                    Text("Down")
                                }
                                Button(onClick = { onDeleteMod(mod) }) {
                                    Text("Delete")
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${plugin.priority} | ${plugin.pluginName} | ${plugin.pluginType}")
                            Text(if (plugin.enabled) "Enabled" else "Disabled")
                            Text("From: ${plugin.sourceModName}")

                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onTogglePlugin(plugin.normalizedPath) }) {
                                    Text(if (plugin.enabled) "Disable" else "Enable")
                                }
                                Button(onClick = { onMovePluginUp(plugin.normalizedPath) }) {
                                    Text("Up")
                                }
                                Button(onClick = { onMovePluginDown(plugin.normalizedPath) }) {
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
    targetPathText: String,
    onTargetPathChanged: (String) -> Unit,
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

            OutlinedTextField(
                value = targetPathText,
                onValueChange = onTargetPathChanged,
                label = { Text("Target Data Path") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

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