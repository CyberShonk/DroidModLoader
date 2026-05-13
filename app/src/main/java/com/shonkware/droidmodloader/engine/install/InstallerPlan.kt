package com.shonkware.droidmodloader.engine.install

data class InstallerPlan(
    val installerType: InstallerType,
    val displayName: String,
    val rootPath: String,
    val groups: List<InstallerGroup>,
    val warnings: List<String> = emptyList()
) {
    val requiresUserChoice: Boolean
        get() = installerType == InstallerType.BAIN || installerType == InstallerType.FOMOD

    val defaultSelectedOptionIds: Set<String>
        get() = groups
            .flatMap { it.options }
            .filter { it.required || it.selectedByDefault }
            .map { it.id }
            .toSet()
}