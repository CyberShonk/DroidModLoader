package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.baseline.DataBaselineFileRecord
import com.shonkware.droidmodloader.engine.baseline.DataBaselineRepository
import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.GameDeploymentConfigRepository
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceRead
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceState
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.LegacyDeploymentEvidenceSnapshot
import com.shonkware.droidmodloader.engine.deploy.LegacyProfileEvidence
import com.shonkware.droidmodloader.engine.deploy.LegacyProfileEvidenceScope
import com.shonkware.droidmodloader.engine.deploy.LegacyTargetEvidenceScope
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalResultSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalTargetState
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LegacyDeploymentEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recordedHash = "a".repeat(64)

    @Test fun `valid supported evidence is historical and inspection opens metadata only`() {
        val fixture = fixture()
        val profile = inspect(fixture).profiles.single()
        assertEquals(DeploymentEvidenceState.VALID, profile.dataManifest.state)
        assertEquals(DeploymentEvidenceState.VALID_EMPTY, profile.rootManifest.state)
        assertEquals(DeploymentEvidenceState.VALID, profile.configuration.state)
        assertEquals(DeploymentEvidenceState.VALID, profile.journal.state)
        assertEquals(DeploymentJournalStatus.COMPLETED, profile.journal.value!!.status)
        assertEquals(DeploymentEvidenceState.VALID, profile.baseline.state)
        assertFalse(File(profile.dataManifest.value!!.single().sourceFilePath).exists())
    }

    @Test fun `absent saved empty and blank manifest remain different without initialization`() {
        val fixture = fixture()
        fixture.scope.data.manifestFile.delete()
        assertEquals(DeploymentEvidenceState.MISSING, inspect(fixture).profiles.single().dataManifest.state)
        fixture.scope.data.manifestFile.writeText("[]")
        assertEquals(DeploymentEvidenceState.VALID_EMPTY, inspect(fixture).profiles.single().dataManifest.state)
        fixture.scope.data.manifestFile.writeText(" \n")
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `all repositories preserve absence versus blank instead of creating defaults`() {
        val fixture = fixture()
        fixture.metadataFiles.forEach { it.delete() }
        val missing = inspect(fixture).profiles.single()
        assertEquals(listOf(DeploymentEvidenceState.MISSING, DeploymentEvidenceState.MISSING,
            DeploymentEvidenceState.MISSING, DeploymentEvidenceState.MISSING, DeploymentEvidenceState.MISSING), states(missing))
        fixture.metadataFiles.forEach { it.writeText("") }
        assertTrue(states(inspect(fixture).profiles.single()).all { it == DeploymentEvidenceState.INVALID })
    }

    @Test fun `legacy optional manifest fields remain incomplete and source associations unchanged`() {
        val fixture = fixture()
        fixture.editManifest { remove("hadPreExistingTargetFile"); remove("winningModName"); remove("backupFilePath"); remove("backupCreatedAtEpochMillis") }
        val evidence = inspect(fixture).profiles.single().dataManifest
        assertEquals(DeploymentEvidenceState.INCOMPLETE, evidence.state)
        assertEquals("mod-a", evidence.value!!.single().winningModName)
        assertTrue(evidence.value.single().sourceFilePath.endsWith("unavailable-source"))
    }

    @Test fun `duplicate keys duplicate rows type coercion and malformed JSON are invalid`() {
        val fixture = fixture()
        val original = fixture.scope.data.manifestFile.readText()
        val invalid = listOf("[", original.replace("\"hash\":", "\"hash\":\"${recordedHash}\",\"hash\":"),
            JSONArray().put(JSONArray(original).getJSONObject(0)).put(JSONArray(original).getJSONObject(0)).toString(),
            original.replace(recordedHash, "short"), original.replace("false", "\"false\""), original + " trailing",
            original.replace("\"mod-a\"", "9"))
        invalid.forEach { text ->
            fixture.scope.data.manifestFile.writeText(text)
            assertEquals(text, DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        }
    }

    @Test fun `unknown fields and versions stay unsupported not valid empty`() {
        val fixture = fixture()
        fixture.editManifest { put("future", JSONObject().put("extra", 1)) }
        fixture.editJournal { put("schemaVersion", 3) }
        fixture.editBaseline { put("future", true) }
        fixture.editConfig { put("future", true) }
        val result = inspect(fixture).profiles.single()
        assertEquals(DeploymentEvidenceState.UNSUPPORTED, result.dataManifest.state)
        assertEquals(DeploymentEvidenceState.UNSUPPORTED, result.journal.state)
        assertEquals(DeploymentEvidenceState.UNSUPPORTED, result.baseline.state)
        assertEquals(DeploymentEvidenceState.UNSUPPORTED, result.configuration.state)
    }

    @Test fun `unsafe case Unicode duplicate and file directory paths cannot be admitted`() {
        val fixture = fixture()
        val first = JSONArray(fixture.scope.data.manifestFile.readText()).getJSONObject(0)
        listOf("../outside", "/absolute", "a\\b", "a:b", "a/./b").forEach { path ->
            fixture.scope.data.manifestFile.writeText(JSONArray().put(JSONObject(first.toString()).put("normalizedPath", path)).toString())
            assertEquals(path, DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        }
        listOf("Settings.ini" to "settings.ini", "caf\u00e9.ini" to "cafe\u0301.ini", "folder" to "folder/file.ini").forEach { (left, right) ->
            fixture.scope.data.manifestFile.writeText(JSONArray().put(JSONObject(first.toString()).put("normalizedPath", left))
                .put(JSONObject(first.toString()).put("normalizedPath", right)).toString())
            assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        }
    }

    @Test fun `stored foreign backup pointers and contradictory backup facts are never followed`() {
        val fixture = fixture()
        val outside = File(temporary.root, "user-original").apply { writeText("keep original") }
        fixture.editManifest { put("hadPreExistingTargetFile", true); put("backupFilePath", outside.absolutePath) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        fixture.editManifest { put("backupFilePath", File(fixture.scope.data.backupDirectory, "wrong.ini").absolutePath) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        fixture.editManifest { put("backupFilePath", File(fixture.scope.data.backupDirectory, "settings.ini").absolutePath); put("hadPreExistingTargetFile", false) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        assertEquals("keep original", outside.readText())
    }

    @Test fun `backup locator alias is rejected without opening its payload`() {
        val fixture = fixture()
        val link = File(fixture.scope.data.backupDirectory, "settings.ini")
        link.delete()
        Files.createSymbolicLink(link.toPath(), File(fixture.root, "game/Data/settings.ini").toPath())
        fixture.editManifest { put("hadPreExistingTargetFile", true); put("backupFilePath", link.absolutePath) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `missing original backup facts stay incomplete not permission to remove output`() {
        val fixture = fixture()
        fixture.editManifest { put("hadPreExistingTargetFile", true) }
        assertEquals(DeploymentEvidenceState.INCOMPLETE, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `v1 journal remains historical with no invented target ownership`() {
        val fixture = fixture()
        fixture.editJournal { put("schemaVersion", 1) }
        assertEquals(DeploymentEvidenceState.INCOMPLETE, inspect(fixture).profiles.single().journal.state)
        fixture.editJournal { remove("schemaVersion"); remove("dataTarget"); remove("rootTarget") }
        val result = inspect(fixture).profiles.single().journal
        assertEquals(DeploymentEvidenceState.INCOMPLETE, result.state)
        assertEquals(1, result.value!!.schemaVersion)
        assertNull(result.value.dataTarget)
        assertNull(result.value.rootTarget)
    }

    @Test fun `unfinished failed and reviewed journals remain blocking in active owner`() {
        val fixture = fixture()
        for (status in listOf(DeploymentJournalStatus.STARTED, DeploymentJournalStatus.FAILED, DeploymentJournalStatus.REVIEWED)) {
            fixture.editJournal {
                put("status", status.name); put("resultSummary", JSONObject.NULL)
                put("completedAtEpochMillis", if (status == DeploymentJournalStatus.STARTED) JSONObject.NULL else 2L)
            }
            val result = inspect(fixture).profiles.single().journal
            assertEquals(DeploymentEvidenceState.VALID, result.state)
            assertEquals(status, result.value!!.status)
            val repository = DeploymentJournalRepository(fixture.scope.journalFile)
            val next = result.value.copy(status = DeploymentJournalStatus.STARTED, completedAtEpochMillis = null)
            assertThrows(IllegalStateException::class.java) { repository.saveStarted(next) }
        }
    }

    @Test fun `foreign journal game profile role and target pair are conflicting`() {
        val fixture = fixture()
        val original = fixture.scope.journalFile.readText()
        listOf<(JSONObject) -> Unit>(
            { it.put("gameId", "other-game") }, { it.put("profileId", "other-profile") },
            { it.getJSONObject("dataTarget").put("targetType", "GAME_ROOT") },
            { it.getJSONObject("dataTarget").put("target", "/another/Data") },
            { it.getJSONObject("rootTarget").put("identityKey", "foreign") }
        ).forEach { edit ->
            fixture.scope.journalFile.writeText(JSONObject(original).also(edit).toString())
            assertEquals(DeploymentEvidenceState.CONFLICTING, inspect(fixture).profiles.single().journal.state)
        }
    }

    @Test fun `invalid summary and nested duplicate fields do not bypass strict journal reader`() {
        val fixture = fixture()
        fixture.editJournal { getJSONObject("planSummary").put("totalOperationCount", 9) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().journal.state)
        fixture.editJournal { getJSONObject("planSummary").put("totalOperationCount", 0) }
        fixture.scope.journalFile.writeText(fixture.scope.journalFile.readText().replace("\"dataOperationCount\":", "\"dataOperationCount\":0,\"dataOperationCount\":"))
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().journal.state)
    }

    @Test fun `baseline cannot become a complete content inventory or hide foreign identity`() {
        val fixture = fixture()
        fixture.editBaseline { getJSONArray("files").getJSONObject(0).remove("sizeBytes") }
        assertEquals(DeploymentEvidenceState.INCOMPLETE, inspect(fixture).profiles.single().baseline.state)
        fixture.editBaseline { put("gameId", "foreign") }
        assertEquals(DeploymentEvidenceState.CONFLICTING, inspect(fixture).profiles.single().baseline.state)
    }

    @Test fun `config duplicates wrong types legacy locators and target mismatch are distinct`() {
        val fixture = fixture()
        val original = fixture.scope.configFile.readText()
        fixture.scope.configFile.writeText(JSONArray().put(JSONArray(original).getJSONObject(0)).put(JSONArray(original).getJSONObject(0)).toString())
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().configuration.state)
        fixture.scope.configFile.writeText(original)
        fixture.editConfig { put("realDeployEnabled", "true") }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().configuration.state)
        fixture.scope.configFile.writeText(original)
        fixture.editConfig { put("targetTreeUri", "content://old") }
        assertEquals(DeploymentEvidenceState.INCOMPLETE, inspect(fixture).profiles.single().configuration.state)
        fixture.scope.configFile.writeText(original)
        fixture.editConfig { put("targetDataPath", "/foreign/Data") }
        assertEquals(DeploymentEvidenceState.CONFLICTING, inspect(fixture).profiles.single().configuration.state)
    }

    @Test fun `two profiles and cross game claims overlap without choosing a winner`() {
        val first = fixture("profile-a")
        val second = fixture("profile-b", first.root, "ttw")
        val snapshot = inspect(first, second)
        assertFalse(snapshot.stale)
        assertEquals(setOf(first.scope.data.identity.target, first.scope.root.identity.target), snapshot.conflictingDestinations)
        assertEquals(2, snapshot.profiles.size)
    }

    @Test fun `separate destinations retain separate evidence and simulation is explicit`() {
        val first = fixture("profile-a")
        val second = fixture("profile-b")
        assertTrue(inspect(first, second).conflictingDestinations.isEmpty())
        val data = first.scope.data.copy(identity = first.scope.data.identity.copy(mode = "simulated"))
        val root = first.scope.root.copy(identity = first.scope.root.identity.copy(mode = "root_simulated", target = File(first.root, "game/GameRoot").absolutePath))
        val simulated = first.scope.copy(data = data, root = root)
        assertFalse(DeploymentService.captureLegacyEvidence(listOf(simulated)).stale)
        // The physical records are not silently rebound to simulation.
        assertEquals(DeploymentEvidenceState.CONFLICTING, DeploymentService.captureLegacyEvidence(listOf(simulated)).profiles.single().journal.state)
    }

    @Test fun `unreadable and invalid UTF8 metadata do not become absent or empty`() {
        val fixture = fixture()
        val denied = object : ReadOnlyOperations(fixture.metadataFiles) {
            override fun readBounded(file: File, maxBytes: Int): ByteArray {
                if (file == fixture.scope.data.manifestFile) throw IOException("fixture access denied")
                return super.readBounded(file, maxBytes)
            }
        }
        assertEquals(DeploymentEvidenceState.UNREADABLE, inspect(fixture, operations = denied).profiles.single().dataManifest.state)
        fixture.scope.data.manifestFile.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `multi record capture drops all returned evidence when bytes change or disappear`() {
        val fixture = fixture()
        for (remove in listOf(false, true)) {
            fixture.scope.data.manifestFile.writeText("[]")
            var reads = 0
            val race = object : ReadOnlyOperations(fixture.metadataFiles) {
                override fun readBounded(file: File, maxBytes: Int): ByteArray {
                    if (file == fixture.scope.data.manifestFile && ++reads == 2) {
                        if (remove) file.delete() else file.writeText(" [ ] ")
                    }
                    return super.readBounded(file, maxBytes)
                }
            }
            val snapshot = DeploymentService.captureLegacyEvidence(listOf(fixture.scope), race)
            assertTrue(snapshot.stale)
            assertTrue(snapshot.profiles.isEmpty())
        }
    }

    @Test fun `missing metadata appearing at final recheck invalidates snapshot`() {
        val fixture = fixture()
        fixture.scope.data.manifestFile.delete()
        var reads = 0
        val race = object : ReadOnlyOperations(fixture.metadataFiles) {
            override fun readBounded(file: File, maxBytes: Int): ByteArray {
                if (file == fixture.scope.data.manifestFile && ++reads == 2) file.writeText("[]")
                return super.readBounded(file, maxBytes)
            }
        }
        assertTrue(DeploymentService.captureLegacyEvidence(listOf(fixture.scope), race).stale)
    }

    @Test fun `bounded record and row limits accept cap and refuse cap plus one`() {
        val fixture = fixture()
        val bytes = DeploymentEvidenceRead.MAX_RECORD_BYTES
        fixture.scope.data.manifestFile.writeText("[]" + " ".repeat(bytes - 2))
        assertEquals(DeploymentEvidenceState.VALID_EMPTY, inspect(fixture).profiles.single().dataManifest.state)
        fixture.scope.data.manifestFile.appendText(" ")
        assertEquals(DeploymentEvidenceState.UNREADABLE, inspect(fixture).profiles.single().dataManifest.state)
        val read = DeploymentEvidenceRead()
        read.rows(JSONArray(List(DeploymentEvidenceRead.MAX_MANIFEST_ROWS) { 0 }), DeploymentEvidenceRead.MAX_MANIFEST_ROWS)
        assertThrows(IllegalArgumentException::class.java) {
            DeploymentEvidenceRead().rows(JSONArray(List(DeploymentEvidenceRead.MAX_MANIFEST_ROWS + 1) { 0 }), DeploymentEvidenceRead.MAX_MANIFEST_ROWS)
        }
        assertThrows(IllegalArgumentException::class.java) { DeploymentService.captureLegacyEvidence(List(201) { fixture.scope }) }
    }

    @Test fun `contradictory captured locations fail before any metadata or payload read`() {
        val fixture = fixture()
        val scope = fixture.scope
        val invalid = listOf(
            scope.copy(root = scope.root.copy(manifestFile = scope.data.manifestFile)),
            scope.copy(root = scope.root.copy(backupDirectory = scope.data.backupDirectory)),
            scope.copy(journalFile = File(scope.data.backupDirectory, "journal.json")),
            scope.copy(configFile = File(fixture.root, "outside.json")),
            scope.copy(data = scope.data.copy(identity = scope.data.identity.copy(target = File(scope.metadataDirectory, "Data").absolutePath)),
                root = scope.root.copy(identity = scope.root.identity.copy(target = scope.metadataDirectory.absolutePath)))
        )
        val before = snapshot()
        val noReads = object : ReadOnlyOperations(emptyList()) {
            override fun readBounded(file: File, maxBytes: Int): ByteArray = throw AssertionError("Invalid scope was read")
        }
        invalid.forEach { captured ->
            assertThrows(IllegalArgumentException::class.java) { DeploymentService.captureLegacyEvidence(listOf(captured), noReads) }
        }
        assertEquals(before, snapshot())
    }

    @Test fun `caller changing supplied scope list cannot retarget the captured operation`() {
        val first = fixture("profile-a")
        val second = fixture("profile-b")
        val scopes = mutableListOf(first.scope)
        val operations = object : ReadOnlyOperations(first.metadataFiles + second.metadataFiles) {
            override fun readBounded(file: File, maxBytes: Int): ByteArray {
                scopes[0] = second.scope
                return super.readBounded(file, maxBytes)
            }
        }
        val before = snapshot()
        val result = DeploymentService.captureLegacyEvidence(scopes, operations)
        assertEquals(before, snapshot())
        assertEquals(first.scope, result.profiles.single().scope)
        assertTrue(result.conflictingDestinations.isEmpty())
    }

    @Test fun `linked metadata is invalid without following foreign content`() {
        val fixture = fixture()
        val foreign = File(temporary.root, "foreign-manifest").apply { writeText("[]") }
        fixture.scope.data.manifestFile.delete()
        Files.createSymbolicLink(fixture.scope.data.manifestFile.toPath(), foreign.toPath())
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `blank backup locator and timestamp without a locator are invalid`() {
        val fixture = fixture()
        fixture.editManifest { put("backupFilePath", " ") }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
        fixture.editManifest { put("backupFilePath", JSONObject.NULL); put("backupCreatedAtEpochMillis", 1) }
        assertEquals(DeploymentEvidenceState.INVALID, inspect(fixture).profiles.single().dataManifest.state)
    }

    @Test fun `aggregate byte budget cannot read the fifth record after four full records`() {
        val fixture = fixture()
        for (file in fixture.metadataFiles.take(4)) {
            val text = file.readText()
            file.writeText(text + " ".repeat(DeploymentEvidenceRead.MAX_RECORD_BYTES - text.toByteArray(Charsets.UTF_8).size))
        }
        val result = inspect(fixture).profiles.single()
        assertEquals(DeploymentEvidenceState.VALID, result.configuration.state)
        assertEquals(DeploymentEvidenceState.VALID, result.journal.state)
        assertEquals(DeploymentEvidenceState.VALID, result.dataManifest.state)
        assertEquals(DeploymentEvidenceState.VALID_EMPTY, result.rootManifest.state)
        assertEquals(DeploymentEvidenceState.UNREADABLE, result.baseline.state)
    }

    @Test fun `aggregate row and record counts stop at their exact ceilings`() {
        val read = DeploymentEvidenceRead()
        read.rows(JSONArray(List(100_001) { 0 }))
        read.rows(JSONArray(List(99_999) { 0 }))
        assertThrows(IllegalArgumentException::class.java) { read.rows(JSONArray().put(0)) }
        val fixture = fixture()
        val before = snapshot()
        val records = DeploymentEvidenceRead(ReadOnlyOperations(fixture.metadataFiles))
        val repository = DeploymentManifestRepository(fixture.scope.root.manifestFile)
        repeat(DeploymentEvidenceRead.MAX_RECORDS) {
            assertEquals(DeploymentEvidenceState.VALID_EMPTY,
                repository.readStagedEvidence(records, fixture.scope.metadataDirectory, fixture.scope.root).state)
        }
        assertThrows(IllegalStateException::class.java) {
            repository.readStagedEvidence(records, fixture.scope.metadataDirectory, fixture.scope.root)
        }
        assertEquals(before, snapshot())
    }

    @Test fun `changing a shared record between reads is stale even if original bytes reappear`() {
        val fixture = fixture()
        val read = DeploymentEvidenceRead(ReadOnlyOperations(fixture.metadataFiles))
        val repository = DeploymentManifestRepository(fixture.scope.root.manifestFile)
        repository.readStagedEvidence(read, fixture.scope.metadataDirectory, fixture.scope.root)
        fixture.scope.root.manifestFile.writeText(" [ ] ")
        repository.readStagedEvidence(read, fixture.scope.metadataDirectory, fixture.scope.root)
        fixture.scope.root.manifestFile.writeText("[]")
        assertFalse(read.isCurrent())
    }

    private fun states(profile: LegacyProfileEvidence) = listOf(profile.configuration.state, profile.journal.state,
        profile.dataManifest.state, profile.rootManifest.state, profile.baseline.state)

    private fun inspect(vararg fixtures: Fixture, operations: CriticalMetadataFileOperations = ReadOnlyOperations(fixtures.flatMap { it.metadataFiles })): LegacyDeploymentEvidenceSnapshot {
        val before = snapshot()
        val result = DeploymentService.captureLegacyEvidence(fixtures.map { it.scope }, operations)
        assertEquals("Inspection must not mutate metadata, payloads, backups or directory entries", before, snapshot())
        assertFalse(result.stale)
        return result
    }

    private fun snapshot(): Map<String, String> = temporary.root.walkTopDown().associate { file ->
        val contents = when {
            Files.isSymbolicLink(file.toPath()) -> "link:${Files.readSymbolicLink(file.toPath())}"
            file.isFile -> {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            else -> "directory"
        }
        file.relativeTo(temporary.root).path to contents
    }

    private open class ReadOnlyOperations(files: List<File>) : CriticalMetadataFileOperations {
        private val allowed = files.toSet()
        override fun read(file: File): ByteArray = error("Unbounded read is forbidden")
        override fun readBounded(file: File, maxBytes: Int): ByteArray {
            check(file in allowed) { "Payload or foreign metadata opened: $file" }
            return SystemCriticalMetadataFileOperations.readBounded(file, maxBytes)
        }
        override fun writeAndSync(file: File, bytes: ByteArray): Unit = error("Read-only inspection wrote a file")
        override fun promote(source: File, target: File): Unit = error("Read-only inspection promoted a file")
        override fun syncDirectory(directory: File): Unit = error("Read-only inspection persisted a directory")
    }

    private data class Fixture(val root: File, val scope: LegacyProfileEvidenceScope) {
        val metadataFiles get() = listOf(scope.configFile, scope.journalFile, scope.data.manifestFile, scope.root.manifestFile, scope.data.baselineFile!!)
        fun editManifest(edit: JSONObject.() -> Unit) {
            val rows = JSONArray(scope.data.manifestFile.readText()); rows.getJSONObject(0).edit(); scope.data.manifestFile.writeText(rows.toString())
        }
        fun editConfig(edit: JSONObject.() -> Unit) {
            val rows = JSONArray(scope.configFile.readText()); rows.getJSONObject(0).edit(); scope.configFile.writeText(rows.toString())
        }
        fun editJournal(edit: JSONObject.() -> Unit) { scope.journalFile.writeText(JSONObject(scope.journalFile.readText()).apply(edit).toString()) }
        fun editBaseline(edit: JSONObject.() -> Unit) {
            val file = checkNotNull(scope.data.baselineFile)
            file.writeText(JSONObject(file.readText()).apply(edit).toString())
        }
    }

    private fun fixture(profile: String = "profile-a", sharedRoot: File? = null, gameId: String = "fallout_nv"): Fixture {
        val root = sharedRoot ?: temporary.newFolder()
        val metadata = File(root, "state/$profile").apply { mkdirs() }
        val gameRoot = File(root, "game").apply { mkdirs() }
        val dataRoot = File(gameRoot, "Data").apply { mkdirs() }
        File(dataRoot, "settings.ini").writeText("changed game bytes")
        val data = LegacyTargetEvidenceScope(DeploymentTargetIdentity(gameId, "real_path", dataRoot.absolutePath), GameTargetType.DATA,
            File(metadata, "data.json"), File(metadata, "backups/data"), File(metadata, "baseline.json"))
        val rootScope = LegacyTargetEvidenceScope(DeploymentTargetIdentity(gameId, "root_real_path", gameRoot.absolutePath), GameTargetType.GAME_ROOT,
            File(metadata, "root.json"), File(metadata, "backups/root"))
        data.backupDirectory.mkdirs(); rootScope.backupDirectory.mkdirs()
        File(data.backupDirectory, "settings.ini").writeText("original unmanaged bytes")
        val scope = LegacyProfileEvidenceScope(gameId, profile, metadata, File(metadata, "config.json"), File(metadata, "journal.json"), data, rootScope)
        GameDeploymentConfigRepository(scope.configFile).save(listOf(GameDeploymentConfig(gameId, "Fixture game", dataRoot.absolutePath, true, gameRoot.absolutePath)))
        DeploymentManifestRepository(data.manifestFile).save(listOf(DeploymentRecord("settings.ini", "mod-a", File(root, "unavailable-source").absolutePath,
            "Fixture mod", recordedHash)))
        DeploymentManifestRepository(rootScope.manifestFile).save(emptyList())
        DataBaselineRepository(data.baselineFile!!).save(DataBaselineSnapshot(gameId, 1, dataRoot.absolutePath,
            listOf(DataBaselineFileRecord("unmanaged.ini", 4, 1))))
        fun targetState(target: LegacyTargetEvidenceScope) = DeploymentJournalTargetState(target.targetType.name, gameId,
            target.identity.mode, target.identity.target, target.identity.stableKey(), target.manifestFile.absolutePath,
            target.baselineFile?.absolutePath, target.backupDirectory.absolutePath)
        val record = DeploymentJournalRecord(operationId = "fixture-operation", gameId = gameId, profileId = profile,
            status = DeploymentJournalStatus.STARTED, startedAtEpochMillis = 1, completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(0, 0, 0, 0, 0, true, 0, 0), resultSummary = null, failureMessage = null,
            dataTarget = targetState(data), rootTarget = targetState(rootScope))
        DeploymentJournalRepository(scope.journalFile).apply {
            saveStarted(record)
            markCompleted(record, DeploymentJournalResultSummary(0, 0, 0, 0, 0, 0, 1))
        }
        return Fixture(root, scope)
    }
}
