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
fun RecoveryToolsCard(
    operationInProgress: Boolean,
    deployRecoveryAttentionRequired: Boolean,
    deploymentBlockedByRecovery: Boolean,
    onViewLastDeployJournal: () -> Unit,
    onMarkDeployRecoveryReviewed: () -> Unit,
    onRequestForceFullRedeploy: () -> Unit,
    onBuildFullRedeployPlan: () -> Unit,
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
            Text(
                text = "Recovery Tools",
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Review unfinished changes without changing game files. Mark Reviewed only records that you saw the warning; it does not repair files or allow another deployment.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "View Last Deployment",
                enabled = !operationInProgress,
                onClick = onViewLastDeployJournal,
                modifier = Modifier.fillMaxWidth()
            )

            DmlButtons.Secondary(
                text = "Preview Full Redeploy",
                enabled = !operationInProgress,
                onClick = onBuildFullRedeployPlan,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Shows planned changes for a full redeploy in the log. No files are changed.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Secondary(
                text = "Mark Reviewed",
                enabled = !operationInProgress && deployRecoveryAttentionRequired,
                onClick = onMarkDeployRecoveryReviewed,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "DML cannot safely retry or undo this deployment from the saved record. Having a backup alone does not make that safe.",
                style = MaterialTheme.typography.bodySmall
            )

            DmlButtons.Danger(
                text = "Force Full Redeploy",
                enabled = !operationInProgress && !deploymentBlockedByRecovery,
                onClick = onRequestForceFullRedeploy,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Rewrites files managed by the current mod list. This is not a repair, and is blocked while earlier changes still need recovery or their records cannot be read.",
                style = MaterialTheme.typography.bodySmall
            )

        }
    }
}