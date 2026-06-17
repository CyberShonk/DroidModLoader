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
    selectedTreeUriText: String,
    selectedRootTreeUriText: String,
    realDeployEnabled: Boolean,
    lastOperationStatus: String,
    summaryText: String,
    onOpenProfileDialog: () -> Unit
) {
    val dataTargetReady =
        selectedTreeUriText.isNotBlank() &&
                selectedTreeUriText != "No folder selected"

    val rootTargetReady =
        selectedRootTreeUriText.isNotBlank() &&
                selectedRootTreeUriText != "No root folder selected"

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
                        text = "Game: $selectedGameId",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                DmlButtons.Secondary(
                    text = "Manage",
                    onClick = onOpenProfileDialog
                )
            }

            Text(
                text = if (realDeployEnabled) {
                    "Deploy mode: Real target folders"
                } else {
                    "Deploy mode: Test output folder"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (dataTargetReady) {
                    "Data target: selected"
                } else {
                    "Data target: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (rootTargetReady) {
                    "Game Root target: selected"
                } else {
                    "Game Root target: not selected"
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (!rootTargetReady) {
                Text(
                    text = "Pick Game Root if you use SKSE, NVSE, ENB, DLL loaders, or root EXE files.",
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

            Text("1. Pick the game Data folder.")
            Text("2. Pick Game Root too if the mod uses SKSE, NVSE, ENB, DLLs, or root EXE files.")
            Text("3. Install a mod archive.")
            Text("4. Check the mod list and plugin list.")
            Text("5. Deploy.")
            Text("6. Write plugin files if needed.")
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
                text = "Write Plugin Files",
                enabled = !operationInProgress,
                onClick = onWriteLoadOrderFiles,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}