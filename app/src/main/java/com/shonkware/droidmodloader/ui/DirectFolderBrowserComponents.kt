package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.storage.DirectFolderBrowserState

@Composable
fun DirectFolderBrowserDialog(
    title: String,
    state: DirectFolderBrowserState,
    requireWritable: Boolean,
    onOpenPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectCurrent: () -> Unit,
    onCancel: () -> Unit
) {
    val currentCanBeSelected = state.canSelectCurrent &&
        (!requireWritable || state.currentWritable)

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(title)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(state.currentPath ?: "Shared storage roots")

                if (state.currentPath != null) {
                    TextButton(onClick = onNavigateUp) {
                        Text(if (state.parentPath == null) "Storage Roots" else "Up")
                    }
                }

                state.errorMessage?.let { message ->
                    Text(message)
                }

                if (requireWritable && state.canSelectCurrent && !state.currentWritable) {
                    Text("This folder is not writable and cannot be selected as a deployment target.")
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(
                        items = state.entries,
                        key = { entry -> entry.path }
                    ) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPath(entry.path) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(entry.name)
                                if (!entry.writable) {
                                    Text("Read only")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSelectCurrent,
                enabled = currentCanBeSelected
            ) {
                Text("Use This Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
