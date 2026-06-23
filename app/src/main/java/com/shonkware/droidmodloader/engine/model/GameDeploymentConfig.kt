package com.shonkware.droidmodloader.engine.model

data class GameDeploymentConfig(
    val gameId: String,
    val displayName: String,
    val targetDataPath: String,
    val realDeployEnabled: Boolean,
    val targetRootPath: String = "",
    val dataPathReselectionRequired: Boolean = false,
    val rootPathReselectionRequired: Boolean = false
)
