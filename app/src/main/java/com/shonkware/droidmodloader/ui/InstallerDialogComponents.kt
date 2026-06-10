package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

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