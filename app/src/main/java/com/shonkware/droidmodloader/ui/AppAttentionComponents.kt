package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.attention.AppAttention
import com.shonkware.droidmodloader.attention.AppAttentionAction
import com.shonkware.droidmodloader.ui.attention.AppAttentionPresentationMapper
import com.shonkware.droidmodloader.ui.theme.DmlButtons
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

@Composable
fun AppAttentionCard(
    attention: AppAttention,
    onAction: (AppAttentionAction) -> Unit,
    onDismissForSession: () -> Unit
) {
    val presentation = AppAttentionPresentationMapper.map(attention)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderHot)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = presentation.title,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = presentation.message,
                style = MaterialTheme.typography.bodySmall
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (
                    presentation.primaryAction != null &&
                    presentation.primaryActionLabel != null
                ) {
                    DmlButtons.Secondary(
                        text = presentation.primaryActionLabel,
                        onClick = { onAction(presentation.primaryAction) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (
                    presentation.secondaryAction != null &&
                    presentation.secondaryActionLabel != null
                ) {
                    DmlButtons.Secondary(
                        text = presentation.secondaryActionLabel,
                        onClick = { onAction(presentation.secondaryAction) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (presentation.dismissibleForSession) {
                    DmlButtons.Secondary(
                        text = "Hide for Now",
                        onClick = onDismissForSession,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun AppAttentionPromptDialog(
    attention: AppAttention,
    onAction: (AppAttentionAction) -> Unit,
    onNotNow: () -> Unit
) {
    val presentation = AppAttentionPresentationMapper.map(attention)
    val primaryAction = presentation.primaryAction
    val primaryActionLabel = presentation.primaryActionLabel

    if (!presentation.automaticPrompt || primaryAction == null || primaryActionLabel == null) {
        return
    }

    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(presentation.title)
        },
        text = {
            Text(presentation.message)
        },
        confirmButton = {
            TextButton(onClick = { onAction(primaryAction) }) {
                Text(primaryActionLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text("Not Now")
            }
        }
    )
}
