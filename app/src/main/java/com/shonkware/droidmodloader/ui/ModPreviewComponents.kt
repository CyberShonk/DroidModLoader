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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.engine.index.ModFileFolderSummary
import com.shonkware.droidmodloader.engine.index.ModFilePreview
import com.shonkware.droidmodloader.engine.index.ModFilePreviewStatus
import com.shonkware.droidmodloader.engine.overwrite.OverwriteEntry
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults

@Composable
fun ModFilePreviewDialog(
    preview: ModFilePreview,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit
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
                    text = "Files: ${preview.modName}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                Text("Winning files: ${preview.winningFiles.size}")
                Text("Winners are chosen by mod order; this does not check which files are currently in the game folders.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Overwritten: ${preview.overwrittenFiles.size}")
                Text("Data folder files: ${preview.dataFiles.size}")
                Text("Game root files: ${preview.gameRootFiles.size}")
                Text("Plugins: ${preview.pluginFiles.size}")
                Text("Archives: ${preview.archiveFiles.size}")
                Text("Config files: ${preview.configFiles.size}")

                if (preview.gameRootFiles.isNotEmpty()) {
                    Text(
                        text = "This mod contains Game Root files. These deploy to the selected Game Root Folder, not the Data folder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (preview.folderSummaries.isEmpty()) {
                    Text("No files found.")
                } else {
                    preview.folderSummaries.forEach { summary ->
                        FolderSummaryRow(summary)
                    }
                }

                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun FolderSummaryRow(
    summary: ModFileFolderSummary
) {
    val background = when (summary.dominantStatus) {
        ModFilePreviewStatus.WINNING -> DmlColors.Green.copy(alpha = 0.18f)
        ModFilePreviewStatus.OVERWRITTEN -> DmlColors.RedDark.copy(alpha = 0.55f)
        ModFilePreviewStatus.PLUGIN -> DmlColors.SurfaceRaised
        ModFilePreviewStatus.ARCHIVE -> DmlColors.SurfaceRaised
        ModFilePreviewStatus.CONFIG -> DmlColors.Amber.copy(alpha = 0.18f)
        else -> DmlColors.SurfaceRaised
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = background,
            contentColor = DmlColors.Text
        ),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = summary.displayName,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = buildString {
                    append("Total: ${summary.totalCount}")

                    if (summary.dataFileCount > 0) append(" | Data: ${summary.dataFileCount}")
                    if (summary.gameRootFileCount > 0) append(" | Root: ${summary.gameRootFileCount}")
                    if (summary.winningCount > 0) append(" | Winning: ${summary.winningCount}")
                    if (summary.overwrittenCount > 0) append(" | Overwritten: ${summary.overwrittenCount}")
                    if (summary.notDeployedCount > 0) append(" | Not selected: ${summary.notDeployedCount}")
                    if (summary.pluginCount > 0) append(" | Plugins: ${summary.pluginCount}")
                    if (summary.archiveCount > 0) append(" | Archives: ${summary.archiveCount}")
                    if (summary.configCount > 0) append(" | Config files: ${summary.configCount}")
                    if (summary.setupCount > 0) append(" | Setup: ${summary.setupCount}")
                    if (summary.documentationCount > 0) append(" | Docs: ${summary.documentationCount}")
                    if (summary.optionalCount > 0) append(" | Optional: ${summary.optionalCount}")
                    if (summary.ignoredCount > 0) append(" | Ignored: ${summary.ignoredCount}")
                    if (summary.unknownCount > 0) append(" | Unknown: ${summary.unknownCount}")
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

