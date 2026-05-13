package com.shonkware.droidmodloader.engine.install

enum class InstallerGroupType {
    SELECT_ANY,
    SELECT_EXACTLY_ONE,
    SELECT_AT_MOST_ONE,
    SELECT_AT_LEAST_ONE
}

data class InstallerGroup(
    val id: String,
    val name: String,
    val type: InstallerGroupType,
    val options: List<InstallerOption>
)