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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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

@Composable
private fun GameOptionChips(
    gameOptions: List<String>,
    selectedGameId: String,
    onSelectGame: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gameOptions.chunked(2).forEach { rowGames ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowGames.forEach { gameId ->
                    FilterChip(
                        selected = selectedGameId == gameId,
                        onClick = { onSelectGame(gameId) },
                        label = { Text(gameId) }
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

                    Text("Target folder: ${state.selectedDataPathText}")
                    Text(
                        text = "Pick the Data folder of your installed game.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = actions.onPickTargetFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick Target Folder")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.setupRealDeployEnabled,
                            onCheckedChange = actions.onSetupRealDeployChanged
                        )

                        Spacer(Modifier.width(8.dp))

                        Text("Write to Real Target Folder")
                    }

                    Button(
                        onClick = actions.onCompleteSetup,
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
    newProfileRealDeployEnabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onNewProfileNameChanged: (String) -> Unit,
    onNewProfileGameChanged: (String) -> Unit,
    onPickNewProfileTargetFolder: () -> Unit,
    onNewProfileRealDeployChanged: (Boolean) -> Unit,
    onCreateAdditionalProfile: () -> Unit,
    onClose: () -> Unit,
    gameOptions: List<String>
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

                Text("Selected folder: $newProfileDataPathText")
                Text(
                    text = "Pick the Data folder of your installed game.",
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = onPickNewProfileTargetFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick Target Folder")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = newProfileRealDeployEnabled,
                        onCheckedChange = onNewProfileRealDeployChanged
                    )

                    Spacer(Modifier.width(8.dp))

                    Text("Write to Real Target Folder")
                }

                Button(
                    onClick = onCreateAdditionalProfile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Profile")
                }
            }
        }
    )
}