package com.shonkware.droidmodloader.engine.model

data class GameDeploymentConfig(
    val gameId: String,
    val displayName: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetRootPath: String = "",
    val dataPathReselectionRequired: Boolean = false,
    val rootPathReselectionRequired: Boolean = false,
    @Deprecated("Legacy SAF migration field; remove after direct-storage migration")
    val targetTreeUri: String? = null,
    @Deprecated("Legacy SAF migration field; remove after direct-storage migration")
    val targetRootTreeUri: String? = null
)
