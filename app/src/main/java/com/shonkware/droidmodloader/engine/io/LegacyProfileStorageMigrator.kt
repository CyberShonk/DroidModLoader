package com.shonkware.droidmodloader.engine.io

import java.io.File

internal class LegacyProfileStorageMigrator(
    private val filesDir: File,
    private val externalFilesDirProvider: () -> File?,
    private val activeProfileIdProvider: () -> String?,
    private val profileStoragePaths: ProfileStoragePaths,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {

    fun migrateIfNeeded() {
        val externalBaseDir = externalFilesDirProvider()
        if (externalBaseDir == null) {
            appendError("Cannot migrate legacy state: external files directory is null.", null)
            return
        }

        val currentProfileId = activeProfileIdProvider()
        if (currentProfileId.isNullOrBlank()) {
            appendLog("Skipping legacy migration: no active profile.")
            return
        }

        val stateDir = File(externalBaseDir, "state")
        val migrationMarker = File(stateDir, "profile_storage_migration_v2.json")

        if (migrationMarker.exists()) {
            appendLog("Legacy profile storage migration already completed.")
            return
        }

        val legacyModsDir = File(filesDir, "mods")
        val legacyStateFile = File(stateDir, "installed_mods.json")
        val legacyPluginListFile = File(stateDir, "plugins.json")
        val legacyPluginsTxtFile = File(stateDir, "plugins.txt")
        val legacyLoadorderTxtFile = File(stateDir, "loadorder.txt")
        val legacyGameConfigFile = File(stateDir, "game_deployment_configs.json")

        val hasLegacyState =
            legacyModsDir.exists() ||
                legacyStateFile.exists() ||
                legacyPluginListFile.exists() ||
                legacyPluginsTxtFile.exists() ||
                legacyLoadorderTxtFile.exists() ||
                legacyGameConfigFile.exists()

        if (!hasLegacyState) {
            writeMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "no_legacy_state_found"
            )
            appendLog("No legacy global mod/plugin state found to migrate.")
            return
        }

        val profileInternalDir = profileStoragePaths.getProfileInternalDir()
        val profileModsDir = File(profileInternalDir, "mods")
        val profileStateDir = profileStoragePaths.getProfileStateDir(externalBaseDir)

        val profileAlreadyHasState =
            File(profileStateDir, "installed_mods.json").exists() ||
                File(profileStateDir, "plugins.json").exists() ||
                (profileModsDir.exists() && profileModsDir.listFiles()?.isNotEmpty() == true)

        if (profileAlreadyHasState) {
            writeMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "skipped_profile_already_has_state"
            )
            appendLog("Skipped legacy migration because active profile already has profile-scoped state.")
            return
        }

        try {
            profileInternalDir.mkdirs()
            profileModsDir.mkdirs()
            profileStateDir.mkdirs()

            if (legacyModsDir.exists()) {
                legacyModsDir.copyRecursively(
                    target = profileModsDir,
                    overwrite = false
                )
                appendLog("Copied legacy mods folder into active profile.")
            }

            copyLegacyFileIfExists(
                source = legacyStateFile,
                destination = File(profileStateDir, "installed_mods.json")
            )
            copyLegacyFileIfExists(
                source = legacyPluginListFile,
                destination = File(profileStateDir, "plugins.json")
            )
            copyLegacyFileIfExists(
                source = legacyPluginsTxtFile,
                destination = File(profileStateDir, "plugins.txt")
            )
            copyLegacyFileIfExists(
                source = legacyLoadorderTxtFile,
                destination = File(profileStateDir, "loadorder.txt")
            )
            copyLegacyFileIfExists(
                source = legacyGameConfigFile,
                destination = File(profileStateDir, "game_deployment_configs.json")
            )

            writeMigrationMarker(
                markerFile = migrationMarker,
                profileId = currentProfileId,
                status = "migrated_to_active_profile"
            )

            appendLog("Migrated legacy global mod/plugin state into active profile: $currentProfileId")
            updateLastOperationStatus("Legacy mod state migrated into active profile.")
        } catch (e: Exception) {
            appendError("Legacy profile migration failed: ${e.message}", e)
        }
    }

    private fun copyLegacyFileIfExists(source: File, destination: File) {
        if (!source.exists()) return

        destination.parentFile?.mkdirs()

        if (!destination.exists()) {
            source.copyTo(destination, overwrite = false)
            appendLog("Migrated legacy file: ${source.name}")
        }
    }

    private fun writeMigrationMarker(
        markerFile: File,
        profileId: String,
        status: String
    ) {
        markerFile.parentFile?.mkdirs()

        markerFile.writeText(
            """
        {
          "schemaVersion": 1,
          "profileId": "$profileId",
          "status": "$status",
          "createdAtEpochMillis": ${currentTimeMillis()}
        }
        """.trimIndent()
        )
    }
}
