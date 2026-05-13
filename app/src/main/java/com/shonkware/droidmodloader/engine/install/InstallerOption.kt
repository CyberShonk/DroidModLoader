package com.shonkware.droidmodloader.engine.install

data class InstallerOption(
    val id: String,
    val name: String,
    val description: String = "",
    val sourcePath: String,
    val destinationPath: String = "",
    val required: Boolean = false,
    val selectedByDefault: Boolean = false
)