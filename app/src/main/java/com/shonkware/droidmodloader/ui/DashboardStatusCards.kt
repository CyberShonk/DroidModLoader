package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import com.shonkware.droidmodloader.ui.workflow.GameCatalog


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
    dataTargetReady: Boolean,
    rootTargetReady: Boolean,
    realDeployEnabled: Boolean,
    lastOperationStatus: String,
    summaryText: String,
    onOpenProfileDialog: () -> Unit
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Profile: $activeProfileName",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Game: ${GameCatalog.displayName(selectedGameId)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                DmlButtons.Secondary(
                    text = "Manage Profiles",
                    onClick = onOpenProfileDialog
                )
            }

            Text(
                text = if (realDeployEnabled) {
                    "Deploy to: Game folders"
                } else {
                    "Deploy to: Test folders"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (dataTargetReady) {
                    "Data folder: selected"
                } else {
                    "Data folder: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (rootTargetReady) {
                    "Game Root folder: selected"
                } else {
                    "Game Root folder: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (!rootTargetReady) {
                Text(
                    text = "Choose the game's main folder so DML can check it and find its Data folder.",
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

            Text("1. Choose the installed game's main folder.")
            Text("2. DML checks that folder and finds Data automatically.")
            Text("3. Install a mod archive.")
            Text("4. Check the mod list and plugin list.")
            Text("5. Choose Deploy Mods to copy enabled mod files.")
            Text("6. Choose Apply Plugin Changes to save the game's plugin list if needed.")
            Text("7. Share logs if something looks wrong.")
        }
    }
}

@Composable
fun MainActionsCard(
    operationInProgress: Boolean,
    onInstallMod: () -> Unit,
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
                text = "Install Mod",
                enabled = !operationInProgress,
                onClick = onInstallMod,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Deploy Mods",
                enabled = !operationInProgress,
                onClick = onDeployMods,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Primary(
                text = "Apply Plugin Changes",
                enabled = !operationInProgress,
                onClick = onWriteLoadOrderFiles,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}