package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.model.PluginEntry
import com.shonkware.droidmodloader.ui.theme.DmlButtons
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults
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