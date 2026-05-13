package com.shonkware.droidmodloader.engine.model

data class PluginEntry(
    val normalizedPath: String,
    val pluginName: String,
    val sourceModId: String,
    val sourceModName: String,
    val enabled: Boolean,
    val priority: Int,
    val pluginType: String,

    val sourceType: String = "managed_mod",
    val locked: Boolean = false,
    val filePresentInDataFolder: Boolean = false,
)