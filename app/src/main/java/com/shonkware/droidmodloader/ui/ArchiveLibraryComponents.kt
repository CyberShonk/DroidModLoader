package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiItem
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiState
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArchiveFolderSetupDialog(
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Choose your mod archive folder")
        },
        text = {
            Text(
                "Select the folder where you keep downloaded mod archives. " +
                    "DML will scan files directly inside it for ZIP, 7Z, and RAR archives " +
                    "and remember the folder. When you install a mod, DML copies the archive " +
                    "into its managed storage. The original file stays where it is."
            )
        },
        confirmButton = {
            Button(onClick = onChooseFolder) {
                Text("Choose Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ArchiveLibraryPanelDialog(
    state: ArchiveBrowserUiState,
    operationInProgress: Boolean,
    searchText: String,
    listState: LazyListState,
    onSearchTextChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onChangeFolder: () -> Unit,
    onInstallArchive: (String) -> Unit,
    onClose: () -> Unit
) {
    val filteredItems = remember(state.items, searchText) {
        val query = searchText.trim()
        if (query.isBlank()) {
            state.items
        } else {
            state.items.filter { item ->
                item.displayName.contains(query, ignoreCase = true) ||
                    item.fileName.contains(query, ignoreCase = true) ||
                    item.version?.contains(query, ignoreCase = true) == true ||
                    item.nexusGameDomain?.contains(query, ignoreCase = true) == true ||
                    item.nexusFileName?.contains(query, ignoreCase = true) == true
            }
        }
    }

    val controlsEnabled = !operationInProgress && !state.isLoading

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .padding(8.dp),
            colors = DmlDefaults.panelCardColors(),
            border = BorderStroke(1.dp, DmlColors.BorderDim)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Archive Library",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = buildArchiveFolderSummary(state),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onRefresh,
                            enabled = controlsEnabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Refresh archive list"
                            }
                        ) {
                            Text(
                                text = "↻",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        TextButton(
                            onClick = onChangeFolder,
                            enabled = controlsEnabled
                        ) {
                            Text("Folder")
                        }

                        Button(onClick = onClose) {
                            Text("Close")
                        }
                    }
                }

                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChanged,
                    label = { Text("Search archives") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                state.errorMessage?.let { errorMessage ->
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (filteredItems.isEmpty()) {
                    Text(
                        text = buildArchiveEmptyMessage(
                            state = state,
                            searchText = searchText
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredItems,
                            key = { item -> item.stableId }
                        ) { item ->
                            ArchiveLibraryRow(
                                item = item,
                                operationInProgress = operationInProgress,
                                onInstallArchive = onInstallArchive
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveLibraryRow(
    item: ArchiveBrowserUiItem,
    operationInProgress: Boolean,
    onInstallArchive: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = DmlDefaults.panelCardColors(),
        border = BorderStroke(1.dp, DmlColors.BorderDim)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.displayName,
                    fontWeight = FontWeight.Bold
                )

                if (item.fileName != item.displayName) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = buildArchiveDetails(item),
                    style = MaterialTheme.typography.bodySmall
                )

                item.installedModName?.let { installedModName ->
                    Text(
                        text = "Installed as: $installedModName",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item.version?.takeIf { it.isNotBlank() }?.let { version ->
                    Text(
                        text = "Version: $version",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                buildNexusSourceText(item)?.let { nexusText ->
                    Text(
                        text = nexusText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (item.status != ArchiveBrowserItemStatus.INSTALLED) {
                Button(
                    onClick = { onInstallArchive(item.stableId) },
                    enabled = !operationInProgress
                ) {
                    Text("Install")
                }
            }
        }
    }
}

private fun buildArchiveFolderSummary(state: ArchiveBrowserUiState): String {
    val folderName = state.folderName ?: "Selected folder"
    val count = state.items.size
    val archiveLabel = if (count == 1) "archive" else "archives"
    return "$folderName • $count $archiveLabel"
}

private fun buildArchiveEmptyMessage(
    state: ArchiveBrowserUiState,
    searchText: String
): String {
    return when {
        state.isLoading && state.items.isEmpty() -> {
            "Scanning the selected folder..."
        }

        state.errorMessage != null -> {
            "Use Refresh to try again, or choose a different folder."
        }

        searchText.isNotBlank() -> {
            "No archives match your search."
        }

        else -> {
            "No ZIP, 7Z, or RAR archives were found directly inside this folder."
        }
    }
}

private fun buildArchiveDetails(item: ArchiveBrowserUiItem): String {
    val format = item.archiveFormat.ifBlank { "archive" }.uppercase(Locale.getDefault())
    val size = formatArchiveSize(item.sizeBytes)

    return when (item.status) {
        ArchiveBrowserItemStatus.NEVER_INSTALLED -> {
            "$format • $size • Downloaded ${formatArchiveDate(item.downloadedAtMillis)}"
        }

        ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED -> {
            "$format • $size • Previously installed ${formatArchiveDate(item.installedAtMillis)}"
        }

        ArchiveBrowserItemStatus.INSTALLED -> {
            "$format • $size • Installed ${formatArchiveDate(item.installedAtMillis)}"
        }
    }
}

private fun buildNexusSourceText(item: ArchiveBrowserUiItem): String? {
    if (!item.nexusGameDomain.isNullOrBlank() && item.nexusModId != null) {
        val fileSuffix = item.nexusFileId?.let { " • file $it" }.orEmpty()
        return "Nexus: ${item.nexusGameDomain} • mod ${item.nexusModId}$fileSuffix"
    }

    return item.sourceUrl?.takeIf { it.isNotBlank() }
}

private fun formatArchiveDate(timestampMillis: Long?): String {
    if (timestampMillis == null || timestampMillis <= 0L) {
        return "date unavailable"
    }

    return DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(timestampMillis))
}

internal fun formatArchiveSize(sizeBytes: Long): String {
    if (sizeBytes < 1024L) return "$sizeBytes B"

    val kib = sizeBytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KB", kib)

    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.1f MB", mib)

    return String.format(Locale.US, "%.1f GB", mib / 1024.0)
}
