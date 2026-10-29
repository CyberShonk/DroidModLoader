package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.ui.theme.DmlButtons
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults


@Composable
fun ReportCard(
    logText: String,
    onShareLogs: () -> Unit,
    operationInProgress: Boolean = false,
    integrityCheckInProgress: Boolean = false,
    onVerifyManagedContent: () -> Unit = {},
    onCancelIntegrityCheck: () -> Unit = {},
    deploymentHealth: DeploymentHealth? = null,
    onRefreshDeploymentHealth: () -> Unit = {},
    onInspectStorage: () -> Unit = {},
    onInspectEnvironment: () -> Unit = {}
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
            Text("Report & Diagnostics", fontWeight = FontWeight.Bold)

            var showHealthDetails by rememberSaveable { mutableStateOf(false) }
            Text("Deployment Summary: ${deploymentHealth?.phase?.summary ?: "Not checked yet"}")
            DmlButtons.Secondary(
                text = if (showHealthDetails) "Hide Deployment Details" else "Show Deployment Details",
                onClick = { showHealthDetails = !showHealthDetails },
                modifier = Modifier.fillMaxWidth()
            )
            if (showHealthDetails) {
                Text(deploymentHealth?.toDebugSummary() ?: "Refresh the deployment summary for this profile and its game folders.")
            }
            DmlButtons.Secondary(
                text = "Refresh Deployment Summary",
                enabled = !operationInProgress,
                onClick = onRefreshDeploymentHealth,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Check Game Setup",
                enabled = !operationInProgress,
                onClick = onInspectEnvironment,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Checks expected game files, folder access, app and Android versions, and storage permission. Results appear in the log. This does not test whether the game or mods will run.")

            DmlButtons.Secondary(
                text = "Show Storage Info",
                enabled = !operationInProgress,
                onClick = onInspectStorage,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Shows DML's storage locations, available space and a limited archive listing in the log. Nothing is removed. Run it again after changing files or folders.")

            Row(verticalAlignment = Alignment.CenterVertically) {
                DmlButtons.Secondary(
                    text = "Share Logs",
                    onClick = onShareLogs,
                    modifier = Modifier.weight(1f)
                )
                InfoPopover(
                    text = "Reports include diagnostic information, logs, and full folder paths, which may reveal personal names. Only share with someone you trust. Nothing is uploaded automatically.",
                    accessibilityLabel = "About shared logs"
                )
            }

            DmlButtons.Secondary(
                text = "Check Recorded Files",
                enabled = !operationInProgress,
                onClick = onVerifyManagedContent,
                modifier = Modifier.fillMaxWidth()
            )
            DmlButtons.Secondary(
                text = "Cancel File Check",
                enabled = integrityCheckInProgress,
                onClick = onCancelIntegrityCheck,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Compares files recorded for installed mods and game folders with DML's saved checks. Reports missing, changed or unreadable files without changing them. Files with no saved check cannot be verified; this is not a full game scan.")

            Text(logText)
        }
    }
}
