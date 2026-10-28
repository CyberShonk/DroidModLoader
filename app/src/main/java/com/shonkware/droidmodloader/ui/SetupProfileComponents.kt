package com.shonkware.droidmodloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shonkware.droidmodloader.engine.model.GameProfile
import com.shonkware.droidmodloader.ui.theme.DmlColors
import com.shonkware.droidmodloader.ui.theme.DmlDefaults
import com.shonkware.droidmodloader.ui.workflow.GameCatalog

@Composable
private fun GameOptionChips(
    gameOptions: List<String>,
    selectedGameId: String,
    onSelectGame: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gameOptions.chunked(2).forEach { rowGames ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowGames.forEach { gameId ->
                    FilterChip(
                        selected = selectedGameId == gameId,
                        onClick = { onSelectGame(gameId) },
                        modifier = Modifier.weight(1f),
                        label = { Text(GameCatalog.displayName(gameId)) }
                    )
                }
            }
        }
    }
}

@Composable
fun SetupScreen(
    state: DashboardUiState,
    actions: DashboardActions
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Text("First Setup", fontWeight = FontWeight.Bold)
                    Text("Create your first game profile before using the mod manager.")

                    OutlinedTextField(
                        value = state.profileNameText,
                        onValueChange = actions.onProfileNameChanged,
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    GameOptionChips(
                        gameOptions = state.gameOptions,
                        selectedGameId = state.setupGameId,
                        onSelectGame = actions.onSetupGameChanged
                    )

                    Text(
                        "Game folder: " + state.setupRootTargetPathText.ifBlank {
                            "No folder selected"
                        }
                    )
                    Text(
                        text = "Choose the installed game's main folder. DML checks it and finds the Data folder automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = actions.onPickTargetFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Game Folder")
                    }

                    Text(
                        "Data folder: " + state.setupTargetPathText.ifBlank {
                            "Not detected yet"
                        }
                    )

                    Text(
                        text = if (state.setupInstallationResolved) {
                            "Game installation validated."
                        } else {
                            "Select a valid game folder to continue."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = actions.onCompleteSetup,
                        enabled = state.setupInstallationResolved,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Profile")
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileManagerDialog(
    profiles: List<GameProfile>,
    activeProfileId: String?,
    newProfileNameText: String,
    newProfileGameId: String,
    newProfileDataPathText: String,
    newProfileInstallationResolved: Boolean,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onNewProfileNameChanged: (String) -> Unit,
    onNewProfileGameChanged: (String) -> Unit,
    onPickNewProfileTargetFolder: () -> Unit,
    onCreateAdditionalProfile: () -> Unit,
    onClose: () -> Unit,
    gameOptions: List<String>,
    newProfileRootPathText: String = ""
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        },
        title = {
            Text("Manage Profiles")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Switch Profile", fontWeight = FontWeight.Bold)

                if (profiles.isEmpty()) {
                    Text("No profiles found.")
                } else {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = profile.profileId == activeProfileId,
                                onClick = { onSelectProfile(profile.profileId) },
                                label = { Text(profile.profileName) }
                            )

                            Button(
                                onClick = { onDeleteProfile(profile.profileId) }
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text("Add Profile", fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = newProfileNameText,
                    onValueChange = onNewProfileNameChanged,
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                GameOptionChips(
                    gameOptions = gameOptions,
                    selectedGameId = newProfileGameId,
                    onSelectGame = onNewProfileGameChanged
                )

                Text(
                    "Game folder: " + newProfileRootPathText.ifBlank {
                        "No folder selected"
                    }
                )
                Text(
                    text = "Choose the installed game's main folder. DML checks it and finds the Data folder automatically.",
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = onPickNewProfileTargetFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose Game Folder")
                }

                Text(
                    "Data folder: " + newProfileDataPathText.ifBlank {
                        "Not detected yet"
                    }
                )

                Text(
                    text = if (newProfileInstallationResolved) {
                        "Game installation validated."
                    } else {
                        "Select a valid game folder to continue."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = onCreateAdditionalProfile,
                    enabled = newProfileInstallationResolved,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Profile")
                }
            }
        }
    )
}