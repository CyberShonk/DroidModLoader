package com.shonkware.droidmodloader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun AllFilesAccessDialog(
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text("Storage Access Required")
        },
        text = {
            Text(
                "Droid Mod Loader manages game, mod, archive, backup, and plugin files " +
                    "through direct filesystem paths. Enable Allow access to manage all " +
                    "files for Droid Mod Loader, then return to the app."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        }
    )
}
