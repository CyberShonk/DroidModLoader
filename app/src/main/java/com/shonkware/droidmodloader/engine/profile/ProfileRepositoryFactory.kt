package com.shonkware.droidmodloader.engine.profile

import java.io.File

internal class ProfileRepositoryFactory(
    private val externalFilesDirProvider: () -> File?,
    private val appendError: (String) -> Unit
) {
    fun create(): ProfileRepository? {
        val externalBaseDir = externalFilesDirProvider()
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val stateDir = File(externalBaseDir, "state")
        return ProfileRepository(
            profilesFile = File(stateDir, "profiles.json"),
            setupStateFile = File(stateDir, "app_setup.json")
        )
    }
}
