package com.shonkware.droidmodloader.engine.overwrite

data class OverwriteEntry(
    val normalizedPath: String,
    val reason: String,
    val sizeBytes: Long?
)