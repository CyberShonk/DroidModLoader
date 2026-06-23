package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.ui.theme.DmlButtons
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

@Composable
fun DeploymentSettingsCard(
    selectedDataPathText: String,
    selectedRootPathText: String,
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
                text = "Data folder: $selectedDataPathText",
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
                text = "Game Root folder: $selectedRootPathText",
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