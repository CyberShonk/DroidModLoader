package com.shonkware.droidmodloader.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files

class ProfileStoragePathsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `migration inventory paths use explicit scopes without initializing or changing active context`() {
        val paths = ProfileStoragePaths(temporaryFolder.root, { "active" }, { "selected" })
        assertEquals(File(temporaryFolder.root, "profiles/Legacy_Profile"), paths.getLegacyProfileInternalDir("Legacy Profile"))
        assertNotEquals(paths.getGameLibraryMigrationFile("game"), paths.getGameLibraryMigrationFile("Game"))
        assertEquals(paths.getGameLibraryCatalogueFile("game").parentFile, paths.getGameLibraryMigrationFile("game").parentFile)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        val outside = temporaryFolder.newFolder("outside")
        Files.createSymbolicLink(File(temporaryFolder.root, "profiles").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { paths.getLegacyProfileInternalDir("Legacy Profile") }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun `staged Overwrite paths keep profile game and physical roles distinct without initialization`() {
        val paths = ProfileStoragePaths(temporaryFolder.root, { "ignored-active" }, { "ignored-game" })
        val data = com.shonkware.droidmodloader.engine.model.DeployScope.DATA
        val root = com.shonkware.droidmodloader.engine.model.DeployScope.GAME_ROOT
        assertNotEquals(paths.getProfileOverwriteOwnerFile("game", "A/B"), paths.getProfileOverwriteOwnerFile("game", "A_B"))
        assertNotEquals(paths.getProfileOverwriteOwnerFile("game", "A"), paths.getProfileOverwriteOwnerFile("Game", "A"))
        assertNotEquals(paths.getProfileOverwriteRoleDirectory("game", "A", data), paths.getProfileOverwriteRoleDirectory("game", "A", root))
        assertThrows(IOException::class.java) { paths.getProfileOverwriteRoleDirectory("game", "A",
            com.shonkware.droidmodloader.engine.model.DeployScope.PROFILE_ONLY) }
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        val directory = paths.getProfileOverwriteDirectory("game", "A").also { it.mkdirs() }
        val outside = temporaryFolder.newFolder("outside")
        Files.createSymbolicLink(File(directory, "data").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { paths.getProfileOverwriteRoleDirectory("game", "A", data) }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun `staged library paths preserve distinct opaque identities without creating directories`() {
        val paths = ProfileStoragePaths(temporaryFolder.root, { "active" }, { "selected" })
        assertNotEquals(paths.getProfileMembershipFile("game", "A/B"), paths.getProfileMembershipFile("game", "A_B"))
        assertNotEquals(paths.getProfileMembershipFile("game", "A"), paths.getProfileMembershipFile("game", "a"))
        assertNotEquals(paths.getGameLibraryCatalogueFile("game"), paths.getGameLibraryCatalogueFile("Game"))
        val entry = paths.getGameLibraryEntryDir("../game", "../entry")
        assertTrue(entry.toPath().startsWith(temporaryFolder.root.toPath()))
        assertEquals(entry, entry.canonicalFile)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `staged paths require explicit valid bounded UTF8 identities`() {
        val paths = ProfileStoragePaths(temporaryFolder.root, { null }, { "ignored" })
        for (identifier in listOf("", " ", "a\u0000b", "\uD800", "x".repeat(97), "é".repeat(49))) {
            val failureType = if (identifier == "\uD800") CharacterCodingException::class.java else IllegalArgumentException::class.java
            assertThrows(failureType) { paths.getGameLibraryCatalogueFile(identifier) }
            assertThrows(failureType) { paths.getProfileMembershipFile("game", identifier) }
        }
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `staged paths reject symlink ancestors instead of changing their owned root`() {
        val paths = ProfileStoragePaths(temporaryFolder.root, { "A" }, { "game" })
        val outside = temporaryFolder.newFolder("outside")
        val alias = File(temporaryFolder.root, "game_libraries")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { paths.getGameLibraryCatalogueFile("game") }
        assertTrue(outside.listFiles()!!.isEmpty())
    }


    @Test
    fun `active profile id is sanitized for storage`() {
        val paths = ProfileStoragePaths(
            filesDir = File("/internal"),
            activeProfileIdProvider = { "My Profile: TTW" },
            selectedGameIdProvider = { "fallout_nv" }
        )

        assertEquals("My_Profile_TTW", paths.getActiveProfileStorageKey())
    }

    @Test
    fun `missing active profile falls back to selected game`() {
        val paths = ProfileStoragePaths(
            filesDir = File("/internal"),
            activeProfileIdProvider = { null },
            selectedGameIdProvider = { "skyrim_le" }
        )

        assertEquals("unassigned_skyrim_le", paths.getActiveProfileStorageKey())
    }

    @Test
    fun `profile directories use the active storage key`() {
        val paths = ProfileStoragePaths(
            filesDir = File("/internal"),
            activeProfileIdProvider = { "Default Profile" },
            selectedGameIdProvider = { "skyrim_le" }
        )

        assertEquals(
            File("/internal/profiles/Default_Profile"),
            paths.getProfileInternalDir()
        )
        assertEquals(
            File("/external/state/profiles/Default_Profile"),
            paths.getProfileStateDir(File("/external"))
        )
    }
}
