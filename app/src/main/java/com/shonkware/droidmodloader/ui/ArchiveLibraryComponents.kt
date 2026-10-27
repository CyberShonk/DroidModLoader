package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
                    "and remember the folder. When you install a mod, DML uses a temporary archive copy " +
                    "rather than keeping another archive. The original file stays where it is."
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
    archiveImportInProgress: Boolean,
    searchText: String,
    listState: LazyListState,
    onSearchTextChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onChangeFolder: () -> Unit,
    onInstallArchive: (String) -> Unit,
    onCancelArchiveImport: () -> Unit,
    onClose: () -> Unit,
    lastOperationStatus: String = "",
    onCleanOldArchiveCopies: () -> Unit = {}
) {
    var showCleanupConfirmation by rememberSaveable { mutableStateOf(false) }
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
        onDismissRequest = {
            if (!archiveImportInProgress) {
                onClose()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier.fillMaxWidth(),
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

                        if (archiveImportInProgress) {
                            TextButton(
                                onClick = onCancelArchiveImport
                            ) {
                                Text("Cancel Archive Work")
                            }
                        } else {
                            Button(onClick = onClose) {
                                Text("Close")
                            }
                        }
                    }
                }

                if (lastOperationStatus.isNotBlank()) {
                    Text(
                        text = lastOperationStatus,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "archive-cleanup") {
                        TextButton(
                            enabled = controlsEnabled,
                            onClick = { showCleanupConfirmation = true }
                        ) {
                            Text("Clean Old DML Copies…")
                        }
                    }
                    item(key = "archive-search") {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = onSearchTextChanged,
                            label = { Text("Search archives") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    state.errorMessage?.let { errorMessage ->
                        item(key = "archive-error") {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    state.inspection?.let { report ->
                        item(key = "archive-inspection") { ArchiveInspectionSummary(report) }
                    }
                    if (filteredItems.isEmpty()) {
                        item {
                            Text(buildArchiveEmptyMessage(state, searchText), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(items = filteredItems, key = { item -> item.stableId }) { item ->
                        ArchiveLibraryRow(
                            item = item,
                            operationInProgress = !controlsEnabled,
                            onInstallArchive = onInstallArchive
                        )
                    }
                }
            }
        }
    }

    if (showCleanupConfirmation) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirmation = false },
            title = { Text("Clean old DML archive copies?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Checks all stored profiles and removes only old DML copies that match a separate " +
                            "surviving original byte for byte. Originals, installer sessions, and import history " +
                            "stay unchanged. Missing, changed, selected, or uncertain files are preserved. " +
                            "Verification reads both archives and can take time. Results appear in the log."
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = controlsEnabled, onClick = {
                    showCleanupConfirmation = false
                    onCleanOldArchiveCopies()
                }) { Text("Verify and Clean") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirmation = false }) { Text("Cancel") }
            }
        )
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

                item.installedModName?.takeIf { it.isNotBlank() && it != item.displayName }?.let { installedModName ->
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

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (item.status != ArchiveBrowserItemStatus.INSTALLED) {
                    Button(
                        onClick = { onInstallArchive(item.stableId) },
                        enabled = !operationInProgress
                    ) { Text("Install") }
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

internal fun buildArchiveDetails(item: ArchiveBrowserUiItem): String {
    val format = item.archiveFormat.ifBlank { "archive" }.uppercase(Locale.getDefault())
    val size = formatArchiveSize(item.sizeBytes)

    return when (item.status) {
        ArchiveBrowserItemStatus.NEVER_INSTALLED -> {
            "$format • $size"
        }

        ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED -> {
            "$format • $size • Previously installed"
        }

        ArchiveBrowserItemStatus.INSTALLED -> {
            "$format • $size • Installed"
        }
    }
}

internal fun buildNexusSourceText(item: ArchiveBrowserUiItem): String? =
    item.nexusGameDomain?.takeIf { it.isNotBlank() }?.let { "Nexus game: $it" }

internal fun formatArchiveSize(sizeBytes: Long): String {
    if (sizeBytes < 1024L) return "$sizeBytes B"

    val kib = sizeBytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KB", kib)

    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.1f MB", mib)

    return String.format(Locale.US, "%.1f GB", mib / 1024.0)
}
