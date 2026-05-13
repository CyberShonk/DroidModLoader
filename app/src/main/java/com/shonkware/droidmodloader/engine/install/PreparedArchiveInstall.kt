package com.shonkware.droidmodloader.engine.install

data class PreparedArchiveInstall(
    val archivePath: String,
    val archiveName: String,
    val modName: String,
    val sessionRootPath: String,
    val extractedRootPath: String,
    val installRootPath: String,
    val plan: InstallerPlan
)