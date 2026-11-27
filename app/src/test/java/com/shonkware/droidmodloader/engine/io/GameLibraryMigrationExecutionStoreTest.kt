package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalStorageException
import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.install.LibraryInstallReplacement
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import com.shonkware.droidmodloader.engine.model.ProfileOverwriteRecord
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GameLibraryMigrationExecutionStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `exact preparation upgrades durably without moving or publishing payload`() {
        val fixture = fixture()
        assertEquals(fixture.internal, fixture.paths.getManagedInternalDirectory())
        fixture.store.begin(fixture.record)
        assertEquals(fixture.record, fixture.store.load())
        fixture.store.confirmPersistence(fixture.record)
        assertEquals("payload", fixture.payload.readText())
        assertFalse(File(fixture.record.plan.installations.single().destinationPath).exists())
        assertFalse(fixture.paths.getGameLibraryCatalogueFile("game").exists())
    }

    @Test fun `changed preparation refuses upgrade without overwriting saved evidence`() {
        val fixture = fixture()
        val before = fixture.receipt.readBytes()
        fixture.payload.writeText("changed")
        assertThrows(Exception::class.java) { fixture.store.begin(fixture.record) }
        assertArrayEquals(before, fixture.receipt.readBytes())
    }

    @Test fun `entry intent must precede verification and phases cannot skip forward`() {
        val fixture = fixture()
        fixture.store.begin(fixture.record)
        val moving = fixture.record.copy(phase = MigrationExecutionPhase.MOVING)
        fixture.store.save(fixture.record, moving)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.save(moving, moving.copy(entries = moving.entries.map { it.copy(moveIntended = true, moveVerified = true) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.save(moving, moving.copy(phase = MigrationExecutionPhase.RELOADING))
        }
        val intent = moving.copy(entries = moving.entries.map { it.copy(moveIntended = true) })
        fixture.store.save(moving, intent)
        val moved = intent.copy(entries = intent.entries.map { it.copy(moveVerified = true) })
        fixture.store.save(intent, moved)
        assertThrows(IllegalArgumentException::class.java) { fixture.store.save(moved, intent) }
        val metadataIntent = moved.copy(entries = moved.entries.map { it.copy(metadataIntended = true) })
        fixture.store.save(moved, metadataIntent)
        val verified = metadataIntent.copy(entries = metadataIntent.entries.map { it.copy(metadataVerified = true) })
        fixture.store.save(metadataIntent, verified)
        val publishing = verified.copy(phase = MigrationExecutionPhase.PUBLISHING)
        fixture.store.save(verified, publishing)
        val reloading = publishing.copy(phase = MigrationExecutionPhase.RELOADING)
        fixture.store.save(publishing, reloading)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.save(reloading, reloading.copy(phase = MigrationExecutionPhase.COMPLETE, reloadedProfileIds = listOf("profile")))
        }
        val reloaded = reloading.copy(reloadedProfileIds = listOf("profile"))
        fixture.store.save(reloading, reloaded)
        val complete = reloaded.copy(phase = MigrationExecutionPhase.COMPLETE)
        fixture.store.save(reloaded, complete)
        assertThrows(IllegalArgumentException::class.java) { fixture.store.save(complete, reloaded) }
        assertEquals(complete, fixture.store.load())
    }

    @Test fun `wrong scope unsupported fields phases and incoherent progress are rejected`() {
        val fixture = fixture()
        val text = fixture.store.encode(fixture.record)
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("schemaVersion", 3) }, { it.put("phase", "UNKNOWN") }, { it.put("future", true) },
            { it.put("externalFilesDirectory", "/unrelated") },
            { it.put("phase", "COMPLETE") },
            { it.getJSONArray("entries").getJSONObject(0).put("moveVerified", true) },
            { it.getJSONArray("entries").getJSONObject(0).put("installationId", "foreign") },
            { it.getJSONArray("protectedRemainders").getJSONObject(0).put("profileId", "foreign") },
            { it.put("reloadedProfileIds", JSONArray().put("foreign")) },
            { it.put("entries", JSONArray()) }
        )
        for (mutate in mutations) {
            val json = JSONObject(text)
            mutate(json)
            assertThrows(Exception::class.java) { fixture.store.decode(json.toString()) }
        }
        assertEquals(fixture.record, fixture.store.decode(text))
    }

    @Test fun `foreign cleanup path or whole profile directory can never enter allowlist`() {
        val fixture = fixture()
        for (path in listOf("/foreign/archive.zip", fixture.internal.absolutePath + "/profiles/profile", fixture.external.absolutePath + "/state/profiles/profile/plugins.txt")) {
            val candidate = MigrationCleanupCandidate(path, MigrationCleanupKind.EMPTY_DIRECTORY, "identity", 0, null)
            assertThrows(Exception::class.java) { fixture.store.decode(fixture.store.encode(fixture.record.copy(cleanup = listOf(candidate)))) }
        }
    }

    @Test fun `cleanup cannot retire before completion or silently change ownership`() {
        val fixture = fixture()
        val obsolete = MigrationCleanupCandidate(File(fixture.internal, "profiles/profile/mods").absolutePath,
            MigrationCleanupKind.EMPTY_DIRECTORY, "identity", 0, null)
        val prepared = fixture.record.copy(cleanup = listOf(obsolete))
        fixture.store.begin(prepared)
        assertThrows(IllegalArgumentException::class.java) { fixture.store.save(prepared, prepared.copy(cleanup = listOf(obsolete.copy(retired = true)))) }
        assertThrows(IllegalArgumentException::class.java) { fixture.store.save(prepared, prepared.copy(cleanup = listOf(obsolete.copy(fileKey = "changed")))) }
        assertEquals(prepared, fixture.store.load())
    }

    @Test fun `uncertain promotion preserves strictly readable execution intent without claiming a move`() {
        val fixture = fixture()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                SystemCriticalMetadataFileOperations.promote(source, target)
                throw IOException("injected uncertainty")
            }
        }
        val store = GameLibraryMigrationExecutionStore(fixture.paths, "game", fixture.inventory, operations)
        assertThrows(CriticalStorageException::class.java) { store.begin(fixture.record) }
        assertEquals(fixture.record, fixture.store.load())
        fixture.store.ensurePreparedGuard(fixture.record)
        fixture.store.confirmPersistence(fixture.record)
        assertEquals("payload", fixture.payload.readText())
    }

    @Test fun `prepared receipt blocks shared reads writes and replacement recovery across profiles`() {
        val fixture = fixture()
        // Mere planning has not changed the old layout and is not an execution gate.
        GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "game")
        fixture.store.begin(fixture.record)
        val repository = GameModLibraryRepository(fixture.paths, "game")
        assertThrows(IllegalStateException::class.java) { repository.loadLibrary() }
        assertThrows(IllegalStateException::class.java) { repository.loadProfile("another-profile") }
        val candidate = repository.decodeLibrary(fixture.record.plan.candidateCatalogue)
        assertThrows(IllegalStateException::class.java) { repository.saveLibrary(candidate, null) }
        val replacement = LibraryInstallReplacement(fixture.paths, "game")
        assertThrows(IllegalStateException::class.java) { replacement.begin() }
        assertThrows(IllegalStateException::class.java) { replacement.recover() }
        assertThrows(IllegalStateException::class.java) { replacement.finish() }
        val overwrite = ProfileOverwriteRepository(fixture.paths, "game", "profile")
        assertThrows(IllegalStateException::class.java) { overwrite.loadVerifiedSource() }
        assertThrows(IllegalStateException::class.java) { overwrite.save(ProfileOverwriteRecord("game", "profile", 1, 1, emptyList()), null) }
        GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "independent-game")
        assertEquals("payload", fixture.payload.readText())
    }

    @Test fun `publication and actual shared reader reload use receipt-bound access until completion`() {
        val fixture = fixture()
        val publishing = reachPublishing(fixture)
        val repository = GameModLibraryRepository(fixture.paths, "game")
        val access = MigrationVerificationAccess.capture(fixture.paths, "game", publishing)
        val library = repository.decodeLibrary(publishing.plan.candidateCatalogue)
        repository.saveLibrary(library, null, access)
        assertThrows(IllegalStateException::class.java) { repository.loadLibrary() }
        val profile = publishing.plan.profiles.single()
        val membership = repository.decodeProfile(profile.profileId, profile.candidateMembership)
        val membershipDirectory = fixture.paths.getProfileMembershipFile("game", profile.profileId).parentFile!!
        membershipDirectory.mkdirs()
        SystemCriticalMetadataFileOperations.syncDirectory(membershipDirectory.parentFile!!)
        SystemCriticalMetadataFileOperations.syncDirectory(membershipDirectory)
        repository.saveProfile(profile.profileId, membership, null, access)
        val foreign = library.copy(entries = library.entries.map { it.copy(installedMetadata = it.installedMetadata.copy(sourceArchiveName = "foreign.zip")) })
        assertThrows(IllegalStateException::class.java) { repository.saveLibrary(foreign, 1, access) }
        assertThrows(IllegalStateException::class.java) { GameModLibraryRepository(fixture.paths, "another-game").loadLibrary(access) }
        val reloading = publishing.copy(phase = MigrationExecutionPhase.RELOADING)
        fixture.store.save(publishing, reloading)
        assertThrows(IllegalStateException::class.java) { repository.loadLibrary(access) }
        val reloadAccess = MigrationVerificationAccess.capture(fixture.paths, "game", reloading)
        assertThrows(IllegalStateException::class.java) { repository.saveProfile("profile", membership.copy(revision = 2), 1, reloadAccess) }
        val service = sourceService(fixture)
        assertThrows(IllegalStateException::class.java) { service.stagedEffectiveSources(fixture.paths, "game", "profile", { true }) }
        val content = service.stagedEffectiveSources(fixture.paths, "game", "profile", { true }, migrationAccess = reloadAccess)
        assertEquals("profile", content.profileId)
        assertTrue(content.paths.isEmpty()) // The captured legacy choice is disabled.
        val verified = reloading.copy(reloadedProfileIds = listOf("profile"))
        fixture.store.save(reloading, verified)
        assertThrows(IllegalStateException::class.java) { repository.loadLibrary(reloadAccess) }
        val complete = verified.copy(phase = MigrationExecutionPhase.COMPLETE)
        fixture.store.save(verified, complete)
        assertEquals(library, repository.loadLibrary())
        repository.saveProfile("profile", membership.copy(revision = 2, choices = membership.choices.map { it.copy(enabled = true) }), 1)
        assertTrue(service.stagedEffectiveSources(fixture.paths, "game", "profile", { true }).paths.isNotEmpty())
        assertEquals(complete, fixture.store.load())
    }

    private fun sourceService(fixture: MigrationExecutionTestFixture): ModLibraryService = fixture.sourceReader

    /** Receipt/access boundary fixture, not a substitute for execution-owner tests. */
    private fun reachPublishing(fixture: MigrationExecutionTestFixture): MigrationExecutionRecord {
        fixture.store.begin(fixture.record)
        var current = fixture.record
        fun advance(next: MigrationExecutionRecord) { fixture.store.save(current, next); current = next }
        advance(current.copy(phase = MigrationExecutionPhase.MOVING))
        advance(current.copy(entries = current.entries.map { it.copy(moveIntended = true) }))
        val installation = current.plan.installations.single()
        val destination = File(installation.destinationPath)
        destination.parentFile!!.mkdirs()
        CriticalDirectoryMove().moveToAbsent(File(installation.sourcePath), destination)
        advance(current.copy(entries = current.entries.map { it.copy(moveVerified = true) }))
        advance(current.copy(entries = current.entries.map { it.copy(metadataIntended = true) }))
        val repository = GameModLibraryRepository(fixture.paths, "game")
        val entry = repository.decodeLibrary(current.plan.candidateCatalogue).entries.single()
        CriticalMetadataFile(File(destination, LibraryInstallReplacement.ENTRY_RECORD))
            .replaceDurably(repository.encodeEntryRecord(entry), GameLibraryMigrationPlanRepository.MAX_BYTES)
        advance(current.copy(entries = current.entries.map { it.copy(metadataVerified = true) }))
        advance(current.copy(phase = MigrationExecutionPhase.PUBLISHING))
        return current
    }

    private fun fixture() = MigrationExecutionTestFixture(temporaryFolder)
}
