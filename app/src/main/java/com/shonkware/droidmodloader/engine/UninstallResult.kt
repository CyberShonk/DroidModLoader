package com.shonkware.droidmodloader.engine

data class UninstallResult(
    val removed: Boolean,
    val removedModId: String,
    val deletedFileCount: Int
)
