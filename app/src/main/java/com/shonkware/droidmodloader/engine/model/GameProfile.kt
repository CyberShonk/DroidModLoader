package com.shonkware.droidmodloader.engine.model

data class GameProfile(
    val profileId: String,
    val profileName: String,
    val gameId: String,
    val gameDisplayName: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetRootPath: String = "",
    val dataPathReselectionRequired: Boolean = false,
    val rootPathReselectionRequired: Boolean = false,
    val iniPresetId: String? = null,
    @Deprecated("Legacy SAF migration field; remove after direct-storage migration")
    val targetTreeUri: String? = null,
    @Deprecated("Legacy SAF migration field; remove after direct-storage migration")
    val targetRootTreeUri: String? = null
)
