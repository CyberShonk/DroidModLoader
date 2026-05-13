package com.shonkware.droidmodloader.engine.index

data class ModContentIndex(
    val modId: String,
    val modName: String,
    val entries: List<ModContentEntry>
) {
    val deployableFiles: List<ModContentEntry>
        get() = entries.filter { it.isDeployable }

    val plugins: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.PLUGIN }

    val archives: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.ARCHIVE }

    val configs: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.CONFIG }

    val setupOnlyFiles: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.SETUP_ONLY }

    val documentationFiles: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.DOCUMENTATION }

    val optionalModules: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.OPTIONAL_MODULE || it.isOptionalCandidate }

    val ignoredFiles: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.IGNORED }

    val unknownFiles: List<ModContentEntry>
        get() = entries.filter { it.category == ModContentCategory.UNKNOWN }
}