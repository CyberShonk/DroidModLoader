package com.shonkware.droidmodloader.engine.model

data class GameProfile(
    val profileId: String,
    val profileName: String,
    val gameId: String,
    val gameDisplayName: String,
    val targetDataPath: String,
    val targetTreeUri: String?,
    val realDeployEnabled: Boolean,
    val iniPresetId: String? = null
)