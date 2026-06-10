package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.index.ModContentIndex
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.ui.theme.DmlButtons
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

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
