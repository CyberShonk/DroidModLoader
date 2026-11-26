package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalStorageException
import com.shonkware.droidmodloader.engine.data.InstalledModRecordRepository
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.OverwriteBatchPhase
import com.shonkware.droidmodloader.engine.model.PendingOverwriteBatch
import com.shonkware.droidmodloader.engine.model.ProfileOverwriteRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GameLibraryMigrationInventoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "skyrim"

    @Test fun `two profiles preserve identical variants disabled choices priority and source facts without moving`() {
        val fixture = fixture("one", "two")
        fixture.install("one", enabled = false, priority = 70)
        fixture.install("two", enabled = true, priority = 20)
        val before = fixture.protectedDigests()
        val plan = fixture.inventory.prepare()
        assertEquals(2, plan.installations.size)
        assertEquals(2, plan.installations.map { it.installationId }.distinct().size)
        assertEquals(File(plan.installations[0].sourcePath, "payload.ini").readText(),
            File(plan.installations[1].sourcePath, "payload.ini").readText())
        // Installed metadata contains each original path, so full-tree hashes need
        // not match even when the actual mod content is identical.
        assertEquals(listOf(false, true), plan.profiles.map { JSONObject(it.candidateMembership).getJSONArray("choices").getJSONObject(0).getBoolean("enabled") })
        assertEquals(listOf(70, 20), plan.profiles.map { JSONObject(it.candidateMembership).getJSONArray("choices").getJSONObject(0).getInt("priority") })
        val catalogue = JSONObject(plan.candidateCatalogue).getJSONArray("entries")
        repeat(catalogue.length()) { index ->
            val metadata = catalogue.getJSONObject(index).getJSONObject("installedMetadata")
            assertEquals("original.7z", metadata.getString("sourceArchiveName"))
            assertEquals("https://example.invalid/source", metadata.getString("sourceUrl"))
            assertEquals("version", metadata.getString("version"))
            assertEquals(123L, metadata.getLong("nexusFileId"))
            assertFalse(File(plan.installations[index].destinationPath).exists())
        }
        fixture.inventory.requireMatching(plan)
        assertEquals(before, fixture.protectedDigests())
        assertFalse(fixture.paths.getGameLibraryCatalogueFile(gameId).exists())
    }

    @Test fun `logical profile without materialized folders receives explicit empty candidate only`() {
        val fixture = fixture("empty")
        val plan = fixture.inventory.prepare()
        assertTrue(plan.installations.isEmpty())
        assertNull(plan.profiles.single().legacyState)
        assertEquals(0, JSONObject(plan.profiles.single().candidateMembership).getJSONArray("choices").length())
        assertFalse(fixture.paths.getLegacyProfileInternalDir("empty").exists())
    }

    @Test fun `different variants never merge and legacy raw type stays in mapping`() {
        val fixture = fixture("one", "two")
        fixture.install("one")
        fixture.install("two", bytes = "other")
        val plan = fixture.inventory.prepare()
        assertNotEquals(plan.installations[0].tree, plan.installations[1].tree)
        assertEquals(2, plan.installations.map { it.installationId }.distinct().size)
        assertEquals("ARCHIVE", JSONArray(plan.profiles[0].legacyState).getJSONObject(0).getString("modType"))
    }

    @Test fun `sanitized collisions and duplicate logical identities block`() {
        val collided = fixture("a/b", "a?b")
        assertThrows(Exception::class.java) { collided.inventory.prepare() }
        val duplicated = fixture("one", "one")
        assertThrows(Exception::class.java) { duplicated.inventory.prepare() }
    }

    @Test fun `missing blank corrupt duplicate-field and unknown profile evidence does not become fresh`() {
        for (text in listOf<String?>(null, "", "[", "[{\"profileId\":\"one\",\"profileId\":\"two\"}]", "[{\"future\":true}]")) {
            val fixture = fixture("one")
            if (text == null) fixture.profilesFile.delete() else fixture.profilesFile.writeText(text)
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
        }
    }

    @Test fun `orphan profile missing payload and unknown mods recovery evidence stay untouched`() {
        val orphan = fixture("one")
        val unknown = File(orphan.internal, "profiles/orphan/mods/evidence").apply { parentFile.mkdirs(); writeText("protected") }
        assertThrows(Exception::class.java) { orphan.inventory.prepare() }
        assertEquals("protected", unknown.readText())
        val missing = fixture("one")
        missing.install("one")
        File(missing.internal, "profiles/one/mods/mod/payload.ini").delete()
        File(missing.internal, "profiles/one/mods/mod/.dml_mod.json").delete()
        File(missing.internal, "profiles/one/mods/mod").delete()
        assertThrows(Exception::class.java) { missing.inventory.prepare() }
        val recovery = fixture("one")
        recovery.install("one")
        val evidence = File(recovery.internal, "profiles/one/mods/_dml_transaction_pending.properties").apply { writeText("pending") }
        assertThrows(Exception::class.java) { recovery.inventory.prepare() }
        assertEquals("pending", evidence.readText())
    }

    @Test fun `missing blank malformed unsupported and type-coerced mod state blocks over payload`() {
        val invalidStates = listOf<String?>(null, "", "[", "[]", "[{\"future\":true}]")
        for (state in invalidStates) {
            val fixture = fixture("one")
            fixture.install("one")
            if (state == null) fixture.state("one").delete() else fixture.state("one").writeText(state)
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
            assertEquals("same", File(fixture.internal, "profiles/one/mods/mod/payload.ini").readText())
        }
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("enabled", "false") }, { it.put("priority", 0) }, { it.put("modType", "FUTURE") },
            { it.put("installPath", "/outside") }, { it.put("future", true) }
        )) {
            val fixture = fixture("one")
            fixture.install("one")
            val state = JSONArray(fixture.state("one").readText())
            change(state.getJSONObject(0))
            fixture.state("one").writeText(state.toString())
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
        }
    }

    @Test fun `pending installer global migration shared evidence and links block without recovery execution`() {
        for (path in listOf("profiles/one/temp/extracted/file", "mods/global/file")) {
            val fixture = fixture("one")
            File(fixture.internal, path).apply { parentFile.mkdirs(); writeText("pending") }
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
        }
        val shared = fixture("one")
        shared.paths.getGameLibraryInstallTransactionDir(gameId).mkdirs()
        assertThrows(Exception::class.java) { shared.inventory.prepare() }
        val linked = fixture("one")
        linked.install("one")
        val protected = temporaryFolder.newFile().apply { writeText("outside") }
        Files.createSymbolicLink(File(linked.internal, "profiles/one/mods/mod/link").toPath(), protected.toPath())
        assertThrows(Exception::class.java) { linked.inventory.prepare() }
        assertEquals("outside", protected.readText())
    }

    @Test fun `completed legacy copy sources are preserved not adopted or cleaned`() {
        val fixture = fixture("one")
        fixture.install("one")
        File(fixture.internal, "mods/old-copy/payload").apply { parentFile.mkdirs(); writeText("retained") }
        File(fixture.external, "state/profile_storage_migration_v2.json").writeText(JSONObject()
            .put("schemaVersion", 1).put("profileId", "one").put("status", "migrated_to_active_profile")
            .put("createdAtEpochMillis", 1).toString())
        val before = fixture.protectedDigests()
        assertEquals(1, fixture.inventory.prepare().installations.size)
        assertEquals(before, fixture.protectedDigests())
    }

    @Test fun `save round trip and duplicate retry publish only the planned record`() {
        val fixture = fixture("one", "two")
        fixture.install("one")
        fixture.install("two")
        val before = fixture.protectedDigests()
        val plan = fixture.inventory.prepare()
        val repository = fixture.repository()
        assertNull(repository.load())
        repository.save(plan)
        assertEquals(plan, repository.load())
        val bytes = fixture.paths.getGameLibraryMigrationFile(gameId).readBytes()
        repository.save(plan)
        assertArrayEquals(bytes, fixture.paths.getGameLibraryMigrationFile(gameId).readBytes())
        assertEquals(before, fixture.protectedDigests())
        assertFalse(fixture.paths.getGameLibraryCatalogueFile(gameId).exists())
        assertFalse(fixture.paths.getProfileMembershipFile(gameId, "one").exists())
    }

    @Test fun `changed input after inventory prevents mapping save and keeps existing evidence`() {
        val fixture = fixture("one")
        fixture.install("one")
        val plan = fixture.inventory.prepare()
        val repository = fixture.repository()
        File(fixture.internal, "profiles/one/mods/mod/payload.ini").writeText("changed")
        assertThrows(Exception::class.java) { repository.save(plan) }
        assertNull(repository.load())
        val current = fixture.inventory.prepare()
        repository.save(current)
        val saved = fixture.paths.getGameLibraryMigrationFile(gameId).readBytes()
        fixture.state("one").appendText(" ")
        assertThrows(Exception::class.java) { repository.save(fixture.inventory.prepare()) }
        assertArrayEquals(saved, fixture.paths.getGameLibraryMigrationFile(gameId).readBytes())
    }

    @Test fun `unsupported corrupt duplicate and oversized saved mapping cannot be replaced`() {
        for (text in listOf("", "{", "{\"schemaVersion\":2}", "{\"schemaVersion\":1,\"schemaVersion\":1}", " ".repeat(8 * 1024 * 1024 + 1))) {
            val fixture = fixture("one")
            val plan = fixture.inventory.prepare()
            val repository = fixture.repository()
            val record = fixture.paths.getGameLibraryMigrationFile(gameId).apply { writeText(text) }
            assertThrows(Exception::class.java) { repository.load() }
            assertThrows(Exception::class.java) { repository.save(plan) }
            assertEquals(text, record.readText())
        }
    }

    @Test fun `mapping save failures distinguish prepromotion and uncertain promotion without payload mutation`() {
        for (afterPromotion in listOf(false, true)) {
            val fixture = fixture("one")
            fixture.install("one")
            val plan = fixture.inventory.prepare()
            val before = fixture.protectedDigests()
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun promote(source: File, target: File) {
                    if (afterPromotion) SystemCriticalMetadataFileOperations.promote(source, target)
                    throw IOException("injected promotion failure")
                }
            }
            val repository = fixture.repository(operations)
            val failure = assertThrows(CriticalStorageException::class.java) { repository.save(plan) }
            assertTrue(failure.mutationMayHaveOccurred)
            assertEquals(if (afterPromotion) plan else null, repository.load())
            assertEquals(before, fixture.protectedDigests())
        }
    }

    @Test fun `record validation rejects altered scope choices source facts and paths`() {
        val fixture = fixture("one")
        fixture.install("one")
        val plan = fixture.inventory.prepare()
        val repository = fixture.repository()
        val text = repository.encode(plan)
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("phase", "COMPLETED") },
            { it.put("gameId", "other") },
            { it.put("future", true) },
            { it.getJSONArray("inputs").getJSONObject(0).put("path", "/outside/evidence") },
            { it.getJSONArray("installations").getJSONObject(0).put("sourcePath", "/outside/payload") },
            { it.getJSONArray("installations").getJSONObject(0).put("destinationPath", "/outside/payload") },
            { it.getJSONArray("installations").getJSONObject(0).put("sha256", "not-a-hash") },
            { it.getJSONArray("installations").getJSONObject(0).put("entryCount", 200_001) },
            { record ->
                val profile = record.getJSONArray("profiles").getJSONObject(0)
                val membership = JSONObject(profile.getString("candidateMembership"))
                membership.getJSONArray("choices").getJSONObject(0).put("enabled", false)
                profile.put("candidateMembership", membership.toString())
            },
            { record ->
                val catalogue = JSONObject(record.getString("candidateCatalogue"))
                catalogue.getJSONArray("entries").getJSONObject(0).getJSONObject("installedMetadata").put("sourceArchiveName", "invented.7z")
                record.put("candidateCatalogue", catalogue.toString())
            }
        )
        for (mutate in mutations) {
            val record = JSONObject(text)
            mutate(record)
            assertThrows(Exception::class.java) { repository.decode(record.toString()) }
        }
        assertEquals(plan, repository.decode(text))
    }

    @Test fun `identical retry reestablishes final mapping directory persistence`() {
        val fixture = fixture("one")
        val plan = fixture.inventory.prepare()
        fixture.repository().save(plan)
        var barriers = 0
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                barriers++
                throw IOException("injected retry barrier failure")
            }
        }
        assertThrows(CriticalStorageException::class.java) { fixture.repository(operations).save(plan) }
        assertEquals(1, barriers)
        assertEquals(plan, fixture.repository().load())
        fixture.repository().save(plan)
    }

    @Test fun `initial persistence barrier failure never promotes mapping`() {
        val fixture = fixture("one")
        val plan = fixture.inventory.prepare()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) { throw IOException("injected initial barrier failure") }
        }
        val repository = fixture.repository(operations)
        val failure = assertThrows(CriticalStorageException::class.java) { repository.save(plan) }
        assertFalse(failure.mutationMayHaveOccurred)
        assertNull(repository.load())
    }

    @Test fun `measurable metadata shortage prevents initial mapping write`() {
        val fixture = fixture("one")
        val plan = fixture.inventory.prepare()
        fixture.repository() // Caller-prepared parent only.
        val repository = GameLibraryMigrationPlanRepository(fixture.paths, gameId, fixture.inventory, availableBytes = { 0L })
        assertThrows(IOException::class.java) { repository.save(plan) }
        assertNull(repository.load())
    }

    @Test fun `source archive game targets and unknown protected profile files are unchanged`() {
        val fixture = fixture("one")
        fixture.install("one")
        val archive = File(fixture.external, "downloads/archive_library/original.7z").apply { parentFile.mkdirs(); writeText("archive") }
        val target = temporaryFolder.newFolder("game-target")
        File(target, "game-file").writeText("protected game bytes")
        val unknown = File(fixture.state("one").parentFile, "unknown-recovery.evidence").apply { writeText("preserved") }
        val targetBefore = MigrationTreeVerifier().capture(target)
        val plan = fixture.inventory.prepare()
        fixture.repository().save(plan)
        assertEquals("archive", archive.readText())
        assertEquals("preserved", unknown.readText())
        assertEquals(targetBefore, MigrationTreeVerifier().capture(target))
        assertFalse(plan.inputs.any { File(it.path).toPath().startsWith(target.toPath()) || it.path == archive.absolutePath })
    }

    @Test fun `malformed metadata invalid UTF8 and unsupported source fields remain blocking evidence`() {
        for (text in listOf("", "{", "{\"future\":true}")) {
            val fixture = fixture("one")
            fixture.install("one")
            val metadata = File(fixture.internal, "profiles/one/mods/mod/.dml_mod.json").apply { writeText(text) }
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
            assertEquals(text, metadata.readText())
        }
        val invalidUtf8 = fixture("one")
        invalidUtf8.profilesFile.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { invalidUtf8.inventory.prepare() }
    }

    @Test fun `unrelated games unresolved install state does not become selected games data`() {
        val fixture = fixture("one", "other")
        val profiles = JSONArray(fixture.profilesFile.readText())
        profiles.getJSONObject(1).put("gameId", "different-game")
        fixture.profilesFile.writeText(profiles.toString())
        fixture.install("one")
        File(fixture.internal, "profiles/other/mods/_dml_transaction_pending.properties").apply { parentFile.mkdirs(); writeText("pending") }
        File(fixture.external, "state/profiles/other/installed_mods.json").apply { parentFile.mkdirs(); writeText("corrupt") }
        val plan = fixture.inventory.prepare()
        assertEquals(listOf("one"), plan.profiles.map { it.profileId })
        assertEquals(1, plan.installations.size)
    }

    @Test fun `combined mapping budget and encoded record limit refuse oversized plans before persistence`() {
        val fixture = fixture("one")
        fixture.state("one").apply { parentFile.mkdirs(); writeText("[]" + " ".repeat(4 * 1024 * 1024)) }
        assertThrows(IOException::class.java) { fixture.inventory.prepare() }
        assertFalse(fixture.paths.getGameLibraryMigrationFile(gameId).exists())
        fixture.state("one").writeText("[]")
        val plan = fixture.inventory.prepare()
        val oversized = plan.copy(profiles = plan.profiles.map { it.copy(legacyState = " ".repeat(8 * 1024 * 1024) + "[]") })
        val repository = fixture.repository()
        assertThrows(IllegalArgumentException::class.java) { repository.save(oversized) }
        assertNull(repository.load())
    }

    @Test fun `absent older source fields remain absent facts rather than inferred identities`() {
        val fixture = fixture("one")
        fixture.install("one")
        val metadataFile = File(fixture.internal, "profiles/one/mods/mod/.dml_mod.json")
        val metadata = JSONObject(metadataFile.readText())
        metadata.remove("sourceArchiveName")
        metadata.remove("version")
        metadataFile.writeText(metadata.toString())
        val plan = fixture.inventory.prepare()
        val translated = JSONObject(plan.candidateCatalogue).getJSONArray("entries").getJSONObject(0).getJSONObject("installedMetadata")
        assertTrue(translated.isNull("sourceArchiveName"))
        assertTrue(translated.isNull("version"))
        assertFalse(JSONObject(plan.installations.single().legacyMetadata).has("version"))
        fixture.repository().save(plan)
    }

    @Test fun `pending Overwrite phases and unowned content remain blocked without changing owner evidence`() {
        for (phase in OverwriteBatchPhase.entries) {
            val fixture = fixture("one")
            val ownerFile = fixture.paths.getProfileOverwriteOwnerFile(gameId, "one").apply { parentFile.mkdirs() }
            val repository = ProfileOverwriteRepository(fixture.paths, gameId, "one")
            val empty = ProfileOverwriteRecord(gameId, "one", 1, 1, emptyList())
            repository.save(empty, null)
            val plan = fixture.inventory.prepare()
            fixture.repository().save(plan)
            val pending = empty.copy(recordRevision = 2, pendingBatch = PendingOverwriteBatch("pending", phase))
            repository.save(pending, 1)
            val bytes = ownerFile.readBytes()
            assertThrows(Exception::class.java) { fixture.inventory.prepare() }
            assertArrayEquals(bytes, ownerFile.readBytes())
        }
        val fixture = fixture("one")
        File(fixture.paths.getProfileOverwriteDirectory(gameId, "one"), "data/unowned.ini").apply { parentFile.mkdirs(); writeText("protected") }
        assertThrows(Exception::class.java) { fixture.inventory.prepare() }
    }

    @Test fun `verification limit fails before writing a mapping`() {
        val fixture = fixture("one")
        fixture.install("one")
        val limited = GameLibraryMigrationInventory(fixture.internal, fixture.external, gameId, MigrationTreeVerifier(maximumEntries = 1))
        assertThrows(Exception::class.java) { limited.prepare() }
        assertFalse(fixture.paths.getGameLibraryMigrationFile(gameId).exists())
    }

    private fun fixture(vararg profileIds: String): Fixture {
        val internal = temporaryFolder.newFolder()
        val external = temporaryFolder.newFolder()
        val profilesFile = File(external, "state/profiles.json").apply {
            parentFile.mkdirs()
            writeText(JSONArray(profileIds.map { id -> JSONObject().put("profileId", id).put("profileName", id)
                .put("gameId", gameId).put("gameDisplayName", "Skyrim") }).toString())
        }
        return Fixture(internal, external, profilesFile)
    }

    private inner class Fixture(val internal: File, val external: File, val profilesFile: File) {
        val paths = ProfileStoragePaths(internal, { null }, { gameId })
        val inventory = GameLibraryMigrationInventory(internal, external, gameId)
        fun state(profileId: String) = File(external, "state/profiles/$profileId/installed_mods.json")
        fun install(profileId: String, enabled: Boolean = true, priority: Int = 10, bytes: String = "same") {
            val mod = File(internal, "profiles/$profileId/mods/mod").apply { mkdirs() }
            File(mod, "payload.ini").writeText(bytes)
            InstalledModRecordRepository().saveRecord(mod, InstalledModRecord("mod", "Example", mod.absolutePath,
                "archive", "original.7z", 100, "skyrim", 1, 123, "source", "version", "https://example.invalid/source"))
            state(profileId).apply { parentFile.mkdirs(); writeText(JSONArray().put(JSONObject().put("id", "mod")
                .put("name", "Example").put("installPath", mod.absolutePath).put("enabled", enabled).put("priority", priority)
                .put("modType", "ARCHIVE")).toString()) }
            File(state(profileId).parentFile, "plugins.txt").writeText("preserved plugin choices")
            File(state(profileId).parentFile, "deployment_manifest.json").writeText("protected target evidence")
        }
        fun repository(operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations): GameLibraryMigrationPlanRepository {
            paths.getGameLibraryMigrationFile(gameId).parentFile!!.mkdirs()
            return GameLibraryMigrationPlanRepository(paths, gameId, inventory, operations)
        }
        fun protectedDigests(): List<MigrationTreeDigest?> = listOf(File(internal, "profiles"), File(internal, "mods"), external)
            .map { if (it.exists()) MigrationTreeVerifier().capture(it) else null }
    }
}
