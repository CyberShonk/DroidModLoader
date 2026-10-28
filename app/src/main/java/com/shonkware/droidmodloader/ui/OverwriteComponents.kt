package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

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
                    text = "Files added or changed in the game's Data folder since DML last recorded it.",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(message)

                if (!baselineExists) {
                    Text(
                        text = "DML has no saved file list for this Data folder yet. It creates one after you choose a game folder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (entries.isEmpty()) {
                    Text("No changes found.")
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