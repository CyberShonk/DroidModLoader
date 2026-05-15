package com.shonkware.droidmodloader.engine.index

data class ModFileFolderSummary(
    val displayName: String,
    val normalizedPath: String,
    val isTopLevelFile: Boolean,
    val totalCount: Int,
    val winningCount: Int,
    val overwrittenCount: Int,
    val notDeployedCount: Int,
    val pluginCount: Int,
    val archiveCount: Int,
    val configCount: Int,
    val setupCount: Int,
    val documentationCount: Int,
    val optionalCount: Int,
    val ignoredCount: Int,
    val unknownCount: Int,
    val dominantStatus: ModFilePreviewStatus
)