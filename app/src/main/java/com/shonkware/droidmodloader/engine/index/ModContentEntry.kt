package com.shonkware.droidmodloader.engine.index

data class ModContentEntry(
    val originalPath: String,
    val normalizedPath: String,
    val category: ModContentCategory,
    val reason: String,
    val isDeployable: Boolean,
    val isOptionalCandidate: Boolean = false
)