package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.GameLibraryEntry
import com.shonkware.droidmodloader.engine.model.GameModLibrary
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.ProfileModChoice
import com.shonkware.droidmodloader.engine.model.ProfileModMembership
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GameModLibraryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private var activeProfile = "A"
    private var selectedGame = gameId
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { activeProfile }, { selectedGame }) }
    private val repository by lazy { GameModLibraryRepository(paths, gameId) }

    @Test fun `absent records do not create directories or discover payload folders`() {
        assertNull(repository.loadLibrary())
        assertNull(repository.loadProfile("A"))
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        paths.getGameLibraryEntryDir(gameId, "unknown").mkdirs()
        assertNull(repository.loadLibrary())
        assertNull(repository.loadProfile("A"))
        assertFalse(File(temporaryFolder.root, "profile_memberships").exists())
    }

    @Test fun `saves require explicitly prepared parents and do not initialize a layout`() {
        val library = GameModLibrary(gameId, 1, emptyList())
        val libraryFailure = assertThrows(CriticalStorageException::class.java) { repository.saveLibrary(library, null) }
        val profileFailure = assertThrows(CriticalStorageException::class.java) { repository.saveProfile("A", membership("A"), null) }
        assertFalse(libraryFailure.mutationMayHaveOccurred)
        assertFalse(profileFailure.mutationMayHaveOccurred)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `two profiles share installations but keep opposite choices and priorities`() {
        val library = GameModLibrary(gameId, 1, listOf(entry("first"), entry("second")))
        prepareLibrary()
        repository.saveLibrary(library, null)
        val firstProfile = membership("A", ProfileModChoice("first", true, 10), ProfileModChoice("second", false, 20))
        val secondProfile = membership("B", ProfileModChoice("first", false, 20), ProfileModChoice("second", true, 10))
        prepareProfile("A"); prepareProfile("B")
        repository.saveProfile("A", firstProfile, null)
        repository.saveProfile("B", secondProfile, null)
        assertEquals(library, repository.loadLibrary())
        assertEquals(firstProfile, repository.loadProfile("A"))
        assertEquals(secondProfile, repository.loadProfile("B"))
        val firstView = repository.joinProfileRecords("A", library, firstProfile)
        val secondView = repository.joinProfileRecords("B", library, secondProfile)
        assertEquals(listOf("first", "second"), firstView.map { it.choice.installationId })
        assertEquals(listOf("second", "first"), secondView.map { it.choice.installationId })
        assertEquals(firstView[0].expectedInstallDirectory, secondView[1].expectedInstallDirectory)
        assertTrue(firstView[0].choice.enabled)
        assertFalse(secondView[1].choice.enabled)
        assertFalse(firstView[0].expectedInstallDirectory!!.exists()) // No payload creation/availability claim.
    }

    @Test fun `source metadata round trips escaped Unicode and explicit unknown facts without defaults`() {
        prepareLibrary()
        val original = entry("first")
        val metadata = original.installedMetadata.copy(
            displayName = "Quoted \"name\" \\ Unicode é\n\t",
            sourceArchiveName = null, nexusGameDomain = null, nexusModId = null,
            nexusFileId = null, nexusFileName = null, version = null, sourceUrl = null
        )
        val library = GameModLibrary(gameId, 1, listOf(original.copy(installedMetadata = metadata)))
        repository.saveLibrary(library, null)
        assertEquals(library, repository.loadLibrary())
    }

    @Test fun `explicit empty records remain authoritative over a populated library`() {
        prepareLibrary(); prepareProfile("A")
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(library, null)
        val empty = membership("A")
        repository.saveProfile("A", empty, null)
        assertEquals(empty, repository.loadProfile("A"))
        assertTrue(repository.joinProfileRecords("A", library, empty).isEmpty())
        assertEquals(1, repository.loadLibrary()!!.entries.size)
        repository.saveLibrary(GameModLibrary(gameId, 2, emptyList()), 1)
        assertNotNull(repository.loadLibrary())
        assertTrue(repository.loadLibrary()!!.entries.isEmpty())
    }

    @Test fun `same name variants stay separate and missing identities never match by name`() {
        val first = entry("first")
        val second = entry("second")
        assertEquals(first.installedMetadata.displayName, second.installedMetadata.displayName)
        val library = GameModLibrary(gameId, 1, listOf(first, second))
        val profile = membership("A", ProfileModChoice("missing", true, 1), ProfileModChoice("second", false, 2))
        val resolved = repository.joinProfileRecords("A", library, profile)
        assertNull(resolved[0].libraryEntry)
        assertNull(resolved[0].expectedInstallDirectory)
        assertTrue(resolved[0].choice.enabled) // Preserve the choice, not an invented replacement.
        assertEquals(second, resolved[1].libraryEntry)
        assertNotEquals(paths.getGameLibraryEntryDir(gameId, "first"), resolved[1].expectedInstallDirectory)
    }

    @Test fun `shared revision changes do not rewrite profile choices or source facts`() {
        prepareLibrary(); prepareProfile("A")
        val first = entry("first")
        val library = GameModLibrary(gameId, 1, listOf(first))
        repository.saveLibrary(library, null)
        val profile = membership("A", ProfileModChoice("first", false, 7))
        repository.saveProfile("A", profile, null)
        val originalChoices = paths.getProfileMembershipFile(gameId, "A").readBytes()
        val replacement = library.copy(revision = 2, entries = listOf(first.copy(payloadRevision = 2)))
        repository.saveLibrary(replacement, 1)
        assertEquals(first.installedMetadata, repository.loadLibrary()!!.entries.single().installedMetadata)
        assertArrayEquals(originalChoices, paths.getProfileMembershipFile(gameId, "A").readBytes())
        assertEquals(2L, repository.joinProfileRecords("A", repository.loadLibrary()!!, profile).single().libraryEntry!!.payloadRevision)
    }

    @Test fun `captured game and explicit profile survive mutable A B A selection changes`() {
        prepareLibrary(); prepareProfile("A"); prepareProfile("B")
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(library, null)
        val first = membership("A", ProfileModChoice("first", true, 1))
        repository.saveProfile("A", first, null)
        activeProfile = "B"; selectedGame = "skyrim_le"
        val second = membership("B")
        repository.saveProfile("B", second, null)
        assertEquals(first, repository.loadProfile("A"))
        assertEquals(library, repository.loadLibrary())
        activeProfile = "A"; selectedGame = gameId
        assertEquals(second, repository.loadProfile("B"))
        assertNull(GameModLibraryRepository(paths, "skyrim_le").loadLibrary())
    }

    @Test fun `wrong scopes duplicate identities priorities and forged paths cannot be saved`() {
        prepareLibrary(); prepareProfile("A")
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(library, null)
        val entry = library.entries.single()
        val invalidLibraries = listOf(
            library.copy(gameId = "skyrim_le"),
            library.copy(entries = listOf(entry, entry)),
            library.copy(entries = listOf(entry.copy(payloadRevision = 0))),
            library.copy(entries = listOf(entry.copy(installedMetadata = entry.installedMetadata.copy(installPath = temporaryFolder.root.path)))),
            library.copy(entries = listOf(entry.copy(installedMetadata = entry.installedMetadata.copy(modId = "different"))))
        )
        val old = paths.getGameLibraryCatalogueFile(gameId).readBytes()
        for (invalid in invalidLibraries) {
            recordFailure(ModLibraryRecordFailure.INVALID) { repository.saveLibrary(invalid, 1) }
            assertArrayEquals(old, paths.getGameLibraryCatalogueFile(gameId).readBytes())
        }
        val profile = membership("A", ProfileModChoice("first", true, 1))
        repository.saveProfile("A", profile, null)
        val invalidProfiles = listOf(
            profile.copy(gameId = "skyrim_le"), profile.copy(profileId = "B"),
            profile.copy(choices = listOf(profile.choices.single(), profile.choices.single())),
            profile.copy(choices = listOf(ProfileModChoice("first", true, 0))),
            profile.copy(choices = listOf(ProfileModChoice("first", true, 1), ProfileModChoice("second", true, 1)))
        )
        val oldProfile = paths.getProfileMembershipFile(gameId, "A").readBytes()
        for (invalid in invalidProfiles) {
            recordFailure(ModLibraryRecordFailure.INVALID) { repository.saveProfile("A", invalid, 1) }
            assertArrayEquals(oldProfile, paths.getProfileMembershipFile(gameId, "A").readBytes())
        }
    }

    @Test fun `unknown versions and fields preserve complete original evidence`() {
        prepareLibrary(); prepareProfile("A")
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        val profile = membership("A", ProfileModChoice("first", true, 1))
        repository.saveLibrary(library, null); repository.saveProfile("A", profile, null)
        val libraryFile = paths.getGameLibraryCatalogueFile(gameId)
        val profileFile = paths.getProfileMembershipFile(gameId, "A")
        val originalLibrary = libraryFile.readText(); val originalProfile = profileFile.readText()
        val unsupportedLibraries = listOf(
            JSONObject(originalLibrary).put("schemaVersion", 2),
            JSONObject(originalLibrary).put("future", "keep"),
            JSONObject(originalLibrary).put("future", 1.25),
            JSONObject(originalLibrary).put("schemaVersion", 2).put("future", 1.25),
            JSONObject(originalLibrary).apply { getJSONArray("entries").getJSONObject(0).put("future", 1) },
            JSONObject(originalLibrary).apply { getJSONArray("entries").getJSONObject(0).getJSONObject("installedMetadata").put("future", 1) }
        )
        for (json in unsupportedLibraries) {
            libraryFile.writeText(json.toString())
            val evidence = libraryFile.readBytes()
            recordFailure(ModLibraryRecordFailure.UNSUPPORTED) { repository.loadLibrary() }
            recordFailure(ModLibraryRecordFailure.UNSUPPORTED) { repository.saveLibrary(library.copy(revision = 2), 1) }
            assertArrayEquals(evidence, libraryFile.readBytes())
        }
        libraryFile.writeText(originalLibrary)
        val unsupportedProfiles = listOf(
            JSONObject(originalProfile).put("schemaVersion", 2),
            JSONObject(originalProfile).put("future", true),
            JSONObject(originalProfile).apply { getJSONArray("choices").getJSONObject(0).put("installPath", "/outside") }
        )
        for (json in unsupportedProfiles) {
            profileFile.writeText(json.toString())
            val evidence = profileFile.readBytes()
            recordFailure(ModLibraryRecordFailure.UNSUPPORTED) { repository.loadProfile("A") }
            recordFailure(ModLibraryRecordFailure.UNSUPPORTED) { repository.saveProfile("A", profile.copy(revision = 2), 1) }
            assertArrayEquals(evidence, profileFile.readBytes())
        }
    }

    @Test fun `malformed duplicate deep trailing and coerced records never become empty lists`() {
        prepareProfile("A")
        val profile = membership("A", ProfileModChoice("first", true, 1))
        repository.saveProfile("A", profile, null)
        val file = paths.getProfileMembershipFile(gameId, "A")
        val original = file.readText()
        val invalid = listOf(
            "", "[]", original.dropLast(1), original + "{}", original + "\u0000{}", original + "\u0000",
            original.replaceFirst("\"gameId\":", "\"gameId\":\"wrong\",\"gameId\":"),
            JSONObject(original).put("revision", "1").toString(),
            original.replaceFirst("\"revision\":1", "\"revision\":1.0"),
            JSONObject(original).apply { getJSONArray("choices").getJSONObject(0).put("enabled", "true") }.toString(),
            JSONObject(original).apply { getJSONArray("choices").getJSONObject(0).put("priority", 1.5) }.toString(),
            "{\"schemaVersion\":1,\"extra\":[[[[[[[[[[0]]]]]]]]]]}",
            "{schemaVersion:1}", "{\"schemaVersion\":1,}",
            original.replaceFirst("\"enabled\":", "\"enabled\":false,\"enabled\":"),
            original.replaceFirst("\"profileId\":\"A\"", "\"profileId\":\"A\n\""),
            original.replaceFirst("\"profileId\":\"A\"", "\"profileId\":\"\\x\"")
        )
        for (text in invalid) {
            file.writeText(text)
            recordFailure(ModLibraryRecordFailure.INVALID) { repository.loadProfile("A") }
            recordFailure(ModLibraryRecordFailure.INVALID) { repository.saveProfile("A", profile.copy(revision = 2), 1) }
            assertEquals(text, file.readText())
        }
    }

    @Test fun `unreadable oversized and symbolic link records are not absent`() {
        prepareLibrary()
        val file = paths.getGameLibraryCatalogueFile(gameId)
        file.writeBytes(ByteArray(GameModLibraryRepository.MAX_RECORD_BYTES + 1) { 32 })
        recordFailure(ModLibraryRecordFailure.UNREADABLE) { repository.loadLibrary() }
        assertEquals(GameModLibraryRepository.MAX_RECORD_BYTES.toLong() + 1, file.length())
        file.delete(); file.mkdir()
        recordFailure(ModLibraryRecordFailure.UNREADABLE) { repository.loadLibrary() }
        file.delete()
        val target = temporaryFolder.newFile("other.json").apply { writeText("sentinel") }
        Files.createSymbolicLink(file.toPath(), target.toPath())
        recordFailure(ModLibraryRecordFailure.UNREADABLE) { repository.loadLibrary() }
        target.delete()
        recordFailure(ModLibraryRecordFailure.UNREADABLE) { repository.loadLibrary() }
        assertTrue(Files.isSymbolicLink(file.toPath()))
    }

    @Test fun `payload path aliases are refused even when catalogue bytes are unchanged`() {
        prepareLibrary()
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(library, null)
        val file = paths.getGameLibraryCatalogueFile(gameId)
        val old = file.readBytes()
        val payload = paths.getGameLibraryEntryDir(gameId, "first")
        payload.parentFile!!.mkdirs()
        Files.createSymbolicLink(payload.toPath(), temporaryFolder.newFolder().toPath())
        recordFailure(ModLibraryRecordFailure.INVALID) { repository.loadLibrary() }
        assertArrayEquals(old, file.readBytes())
    }

    @Test fun `stale saves and duplicate initial saves do not overwrite newer records`() {
        prepareLibrary(); prepareProfile("A")
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        val profile = membership("A", ProfileModChoice("first", true, 1))
        repository.saveLibrary(library, null); repository.saveProfile("A", profile, null)
        assertThrows(IOException::class.java) { repository.saveLibrary(library, null) }
        assertThrows(IOException::class.java) { repository.saveProfile("A", profile, null) }
        repository.saveProfile("A", profile.copy(revision = 2, choices = emptyList()), 1)
        assertThrows(IOException::class.java) { repository.saveProfile("A", profile.copy(revision = 2), 1) }
        assertThrows(IOException::class.java) { repository.saveLibrary(library.copy(revision = 3), 1) }
        assertEquals(1L, repository.loadLibrary()!!.revision)
        assertEquals(emptyList<ProfileModChoice>(), repository.loadProfile("A")!!.choices)
    }

    @Test fun `failed first or replacement record writes preserve the old authoritative state`() {
        for (existing in listOf(false, true)) {
            val root = temporaryFolder.newFolder()
            val isolatedPaths = ProfileStoragePaths(root, { "A" }, { gameId })
            val good = GameModLibraryRepository(isolatedPaths, gameId)
            val library = GameModLibrary(gameId, 1, emptyList())
            val profile = membership("A")
            isolatedPaths.getGameLibraryCatalogueFile(gameId).parentFile!!.mkdirs()
            isolatedPaths.getProfileMembershipFile(gameId, "A").parentFile!!.mkdirs()
            if (existing) { good.saveLibrary(library, null); good.saveProfile("A", profile, null) }
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun writeAndSync(file: File, bytes: ByteArray) { file.writeText("partial"); throw IOException("Write failed") }
                override fun promote(source: File, target: File) = throw AssertionError("Must not promote")
            }
            val failed = GameModLibraryRepository(isolatedPaths, gameId, operations)
            val revision = if (existing) 2L else 1L
            assertThrows(CriticalStorageException::class.java) { failed.saveLibrary(library.copy(revision = revision), if (existing) 1 else null) }
            assertThrows(CriticalStorageException::class.java) { failed.saveProfile("A", profile.copy(revision = revision), if (existing) 1 else null) }
            assertEquals(if (existing) library else null, good.loadLibrary())
            assertEquals(if (existing) profile else null, good.loadProfile("A"))
        }
    }

    @Test fun `uncertain record promotion is not retried rolled back or hidden as an empty record`() {
        prepareLibrary()
        val original = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(original, null)
        var promotions = 0
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                promotions++
                SystemCriticalMetadataFileOperations.promote(source, target)
                throw IOException("Rename completed before failure was reported")
            }
        }
        val failed = GameModLibraryRepository(paths, gameId, operations)
        val replacement = original.copy(revision = 2)
        val failure = assertThrows(CriticalStorageException::class.java) { failed.saveLibrary(replacement, 1) }
        assertTrue(failure.mutationMayHaveOccurred)
        assertEquals(1, promotions)
        assertEquals(replacement, repository.loadLibrary())
        assertThrows(IOException::class.java) { failed.saveLibrary(replacement, 1) }
        assertEquals(1, promotions)
    }

    @Test fun `denied reads cannot be resaved and oversized new records are refused`() {
        prepareLibrary()
        val library = GameModLibrary(gameId, 1, listOf(entry("first")))
        repository.saveLibrary(library, null)
        val file = paths.getGameLibraryCatalogueFile(gameId)
        val old = file.readBytes()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun readBounded(file: File, maxBytes: Int): ByteArray = throw IOException("Read denied")
        }
        recordFailure(ModLibraryRecordFailure.UNREADABLE) {
            GameModLibraryRepository(paths, gameId, operations).saveLibrary(library.copy(revision = 2), 1)
        }
        val metadata = library.entries.single().installedMetadata.copy(sourceArchiveName = "x".repeat(GameModLibraryRepository.MAX_RECORD_BYTES))
        recordFailure(ModLibraryRecordFailure.INVALID) {
            repository.saveLibrary(library.copy(revision = 2, entries = listOf(library.entries.single().copy(installedMetadata = metadata))), 1)
        }
        assertArrayEquals(old, file.readBytes())
    }

    private fun entry(installationId: String): GameLibraryEntry = GameLibraryEntry(
        installationId, 1,
        InstalledModRecord(
            installationId, "Same display name", paths.getGameLibraryEntryDir(gameId, installationId).absolutePath,
            "archive", "original.zip", 42, "newvegas", 123, 456, "original.zip", "1.0", "https://example.org/source"
        )
    )

    private fun membership(profileId: String, vararg choices: ProfileModChoice) =
        ProfileModMembership(gameId, profileId, 1, choices.toList())

    private fun prepareLibrary() { paths.getGameLibraryCatalogueFile(gameId).parentFile!!.mkdirs() }
    private fun prepareProfile(profileId: String) { paths.getProfileMembershipFile(gameId, profileId).parentFile!!.mkdirs() }

    private fun recordFailure(expected: ModLibraryRecordFailure, action: () -> Unit) {
        val exception = assertThrows(ModLibraryRecordException::class.java, action)
        assertEquals(expected, exception.failure)
    }
}
