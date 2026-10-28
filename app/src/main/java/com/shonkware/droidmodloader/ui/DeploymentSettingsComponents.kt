package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    secondScreenEnabled: Boolean,
    onPickTargetFolder: () -> Unit,
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
            Text("Game Folders", fontWeight = FontWeight.Bold)

            Text(
                text = "Game folder: $selectedRootPathText",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Data folder: $selectedDataPathText",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Choose the installed game's main folder. DML checks it and finds the Data folder automatically.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Change Game Folder",
                onClick = onPickTargetFolder,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Save Settings",
                onClick = onSaveSettings,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = if (secondScreenEnabled) {
                    "Hide Plugins on Second Screen"
                } else {
                    "Show Plugins on Second Screen"
                },
                onClick = onToggleSecondScreen,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
