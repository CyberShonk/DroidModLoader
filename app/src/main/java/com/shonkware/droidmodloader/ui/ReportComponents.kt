package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onCancelIntegrityCheck: () -> Unit = {}
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

            DmlButtons.Secondary(
                text = "Share Logs",
                onClick = onShareLogs,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Check Recorded Managed Content",
                enabled = !operationInProgress,
                onClick = onVerifyManagedContent,
                modifier = Modifier.fillMaxWidth()
            )
            DmlButtons.Secondary(
                text = "Cancel Integrity Check",
                enabled = integrityCheckInProgress,
                onClick = onCancelIntegrityCheck,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Read-only SHA-256 check of recorded Data, Game Root and installed mod files. Results below are historical observations, not live health; missing evidence is not rebuilt.")

            Text(logText)
        }
    }
}
