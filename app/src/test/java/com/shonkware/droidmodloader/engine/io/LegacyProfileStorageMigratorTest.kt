package com.shonkware.droidmodloader.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyProfileStorageMigratorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `no legacy state writes completed marker`() {
        val filesDir = temporaryFolder.newFolder("internal")
        val externalDir = temporaryFolder.newFolder("external")
        val logs = mutableListOf<String>()

        createMigrator(
            filesDir = filesDir,
            externalDir = externalDir,
            logs = logs
        ).migrateIfNeeded()

        val marker = File(externalDir, "state/profile_storage_migration_v2.json")
        assertTrue(marker.exists())
        assertTrue(marker.readText().contains("\"profileId\": \"profile_one\""))
        assertTrue(marker.readText().contains("\"status\": \"no_legacy_state_found\""))
        assertTrue(marker.readText().contains("\"createdAtEpochMillis\": 1234"))
        assertTrue(logs.contains("No legacy global mod/plugin state found to migrate."))
    }

    @Test
    fun `legacy mods and state files are copied into active profile`() {
        val filesDir = temporaryFolder.newFolder("internal")
        val externalDir = temporaryFolder.newFolder("external")
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()

        File(filesDir, "mods/ExampleMod/meshes/example.nif").apply {
            parentFile?.mkdirs()
            writeText("mesh")
        }
        File(externalDir, "state/installed_mods.json").apply {
            parentFile?.mkdirs()
            writeText("mods")
        }
        File(externalDir, "state/plugins.json").writeText("plugins")

        createMigrator(
            filesDir = filesDir,
            externalDir = externalDir,
            logs = logs,
            statuses = statuses
        ).migrateIfNeeded()

        assertEquals(
            "mesh",
            File(filesDir, "profiles/profile_one/mods/ExampleMod/meshes/example.nif").readText()
        )
        assertEquals(
            "mods",
            File(externalDir, "state/profiles/profile_one/installed_mods.json").readText()
        )
        assertEquals(
            "plugins",
            File(externalDir, "state/profiles/profile_one/plugins.json").readText()
        )
        assertTrue(
            File(externalDir, "state/profile_storage_migration_v2.json")
                .readText()
                .contains("\"status\": \"migrated_to_active_profile\"")
        )
        assertTrue(logs.contains("Copied legacy mods folder into active profile."))
        assertEquals(listOf("Legacy mod state migrated into active profile."), statuses)
    }

    @Test
    fun `existing profile state prevents legacy copy`() {
        val filesDir = temporaryFolder.newFolder("internal")
        val externalDir = temporaryFolder.newFolder("external")
        val logs = mutableListOf<String>()

        File(externalDir, "state/installed_mods.json").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }
        val existingProfileState = File(
            externalDir,
            "state/profiles/profile_one/installed_mods.json"
        ).apply {
            parentFile?.mkdirs()
            writeText("existing")
        }

        createMigrator(
            filesDir = filesDir,
            externalDir = externalDir,
            logs = logs
        ).migrateIfNeeded()

        assertEquals("existing", existingProfileState.readText())
        assertTrue(
            File(externalDir, "state/profile_storage_migration_v2.json")
                .readText()
                .contains("\"status\": \"skipped_profile_already_has_state\"")
        )
        assertTrue(
            logs.contains(
                "Skipped legacy migration because active profile already has profile-scoped state."
            )
        )
    }

    private fun createMigrator(
        filesDir: File,
        externalDir: File?,
        logs: MutableList<String>,
        statuses: MutableList<String> = mutableListOf()
    ): LegacyProfileStorageMigrator {
        val paths = ProfileStoragePaths(
            filesDir = filesDir,
            activeProfileIdProvider = { "profile_one" },
            selectedGameIdProvider = { "skyrim_le" }
        )

        return LegacyProfileStorageMigrator(
            filesDir = filesDir,
            externalFilesDirProvider = { externalDir },
            activeProfileIdProvider = { "profile_one" },
            profileStoragePaths = paths,
            appendLog = { message -> logs += message },
            appendError = { message, throwable ->
                throw AssertionError("Unexpected migration error: $message", throwable)
            },
            updateLastOperationStatus = { status -> statuses += status },
            currentTimeMillis = { 1234L }
        )
    }
}
