package com.shonkware.droidmodloader.engine.deploy.journal

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalStorageException
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TargetJournalStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val digest = "a".repeat(64)

    @Test fun `registration binds both roles without any completed owner or game mutation`() {
        val fixture = fixture()
        val gameBefore = fixture.gameSnapshot()
        val association = fixture.store().registerAssociation("register-a") { true }
        assertEquals(TargetAssociationState.BOUND, association.state)
        assertEquals(2L, association.journal!!.revision)
        assertNull(association.journal.completed)
        assertNull(fixture.store().readCompletedMetadata())
        assertEquals(gameBefore, fixture.gameSnapshot())
        assertEquals(2, fixture.bindings().count { it.exists() })
        assertTrue(fixture.operations.writes.all { it.toPath().startsWith(fixture.internal.toPath()) })
        val before = snapshot()
        fixture.store().registerAssociation("register-again") { true }
        assertEquals(before, snapshot())
    }

    @Test fun `reads of absent association create no directories or file ownership`() {
        val fixture = fixture(prepareParents = false)
        val before = snapshot()
        assertEquals(TargetAssociationState.MISSING, fixture.store().inspectAssociation().state)
        assertNull(fixture.store().readCompletedMetadata())
        assertEquals(before, snapshot())
    }

    @Test fun `profile and UI game changes cannot move destination journal or create another owner`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val original = fixture.journal
        fixture.profile = "profile-b"; fixture.selectedGame = "other-ui-game"
        assertEquals(original, fixture.paths.getDeploymentTargetJournalFile(fixture.identity.mode, fixture.identity.rootDirectory))
        assertEquals(TargetAssociationState.BOUND, fixture.store().inspectAssociation().state)
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) { fixture.store(fixture.identity.copy(gameId = "ttw")).registerAssociation("foreign") { true } }
        assertEquals(before, snapshot())
    }

    @Test fun `new Data pairing cannot evade an existing Root binding`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val changed = fixture.identity.copy(dataDirectory = File(fixture.game, "OtherData").absolutePath)
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) { fixture.store(changed).registerAssociation("other") { true } }
        assertEquals(before, snapshot())
    }

    @Test fun `role lookup keys exclude role game and profile but simulation is separate`() {
        val fixture = fixture()
        val physical = fixture.paths.getDeploymentRoleBindingFile(TargetStorageMode.PHYSICAL, fixture.identity.dataDirectory)
        fixture.profile = "another"; fixture.selectedGame = "another"
        assertEquals(physical, fixture.paths.getDeploymentRoleBindingFile(TargetStorageMode.PHYSICAL, fixture.identity.dataDirectory))
        assertNotEquals(physical, fixture.paths.getDeploymentRoleBindingFile(TargetStorageMode.SIMULATED, fixture.identity.dataDirectory))
        assertThrows(IllegalArgumentException::class.java) { fixture.identity.copy(dataDirectory = fixture.identity.rootDirectory).validate() }
    }

    @Test fun `qualified canonical alias selects the same metadata while unnormalized text is rejected`() {
        val fixture = fixture()
        val alias = File(temporary.root, "game-alias")
        Files.createSymbolicLink(alias.toPath(), fixture.game.toPath())
        assertEquals(fixture.journal, fixture.paths.getDeploymentTargetJournalFile(TargetStorageMode.PHYSICAL, alias.canonicalPath))
        assertThrows(IllegalArgumentException::class.java) {
            fixture.paths.getDeploymentTargetJournalFile(TargetStorageMode.PHYSICAL, fixture.identity.rootDirectory + "/../Game")
        }
    }

    @Test fun `repository cannot be pointed at an unrelated journal for staged access`() {
        val fixture = fixture()
        assertThrows(IllegalStateException::class.java) {
            DeploymentJournalRepository(File(fixture.internal, "wrong.json")).stagedTargetStorage(fixture.paths, fixture.identity)
        }
    }

    @Test fun `first binding interruption retains registration intent and same operation resumes only metadata`() {
        val fixture = fixture()
        val dataBinding = fixture.binding(GameTargetType.DATA)
        val interrupted = object : MetadataOperations(fixture.internal) {
            override fun promote(source: File, target: File) {
                super.promote(source, target)
                if (target == dataBinding) throw IOException("fixture after first binding promotion")
            }
        }
        val failure = assertThrows(CriticalStorageException::class.java) {
            fixture.store(operations = interrupted).registerAssociation("register-a") { true }
        }
        assertTrue(failure.mutationMayHaveOccurred)
        assertEquals(TargetAssociationState.REGISTRATION_PENDING, fixture.store().inspectAssociation().state)
        assertTrue(dataBinding.exists())
        assertFalse(fixture.binding(GameTargetType.GAME_ROOT).exists())
        assertThrows(IllegalStateException::class.java) { fixture.store().registerAssociation("register-b") { true } }
        assertEquals(TargetAssociationState.BOUND, fixture.store().registerAssociation("register-a") { true }.state)
        assertNull(fixture.store().inspectAssociation().journal!!.completed)
    }

    @Test fun `uncertain first journal save is reread without inventing rollback or file ownership`() {
        val fixture = fixture()
        val interrupted = object : MetadataOperations(fixture.internal) {
            override fun promote(source: File, target: File) {
                super.promote(source, target)
                if (target == fixture.journal) throw IOException("fixture after journal promotion")
            }
        }
        assertTrue(assertThrows(CriticalStorageException::class.java) {
            fixture.store(operations = interrupted).registerAssociation("register") { true }
        }.mutationMayHaveOccurred)
        assertTrue(fixture.bindings().none { it.exists() })
        assertEquals(TargetAssociationState.REGISTRATION_PENDING, fixture.store().inspectAssociation().state)
        fixture.store().registerAssociation("register") { true }
        assertNull(fixture.store().readCompletedMetadata())
    }

    @Test fun `uncertain final registration save retains bound metadata and retry only confirms it`() {
        val fixture = fixture()
        var journalPromotions = 0
        val interrupted = object : MetadataOperations(fixture.internal) {
            override fun promote(source: File, target: File) {
                super.promote(source, target)
                if (target == fixture.journal && ++journalPromotions == 2) throw IOException("fixture after BOUND promotion")
            }
        }
        val gameBefore = fixture.gameSnapshot()
        assertTrue(assertThrows(CriticalStorageException::class.java) {
            fixture.store(operations = interrupted).registerAssociation("register") { true }
        }.mutationMayHaveOccurred)
        assertEquals(TargetAssociationState.BOUND, fixture.store().inspectAssociation().state)
        assertNull(fixture.store().readCompletedMetadata())
        val before = snapshot()
        fixture.store().registerAssociation("register") { true }
        assertEquals(before, snapshot())
        assertTrue(fixture.operations.promotions.isEmpty())
        assertEquals(gameBefore, fixture.gameSnapshot())
    }

    @Test fun `unsupported directory persistence fails before promotion and preserves game bytes`() {
        val fixture = fixture()
        val before = snapshot()
        val unsupported = object : MetadataOperations(fixture.internal) {
            override fun syncDirectory(directory: File): Unit = throw IOException("fixture unsupported barrier")
        }
        val failure = assertThrows(CriticalStorageException::class.java) {
            fixture.store(operations = unsupported).registerAssociation("register") { true }
        }
        assertFalse(failure.mutationMayHaveOccurred)
        assertEquals(before, snapshot())
        assertEquals(TargetAssociationState.MISSING, fixture.store().inspectAssociation().state)
    }

    @Test fun `low and unknown metadata capacity refuse registration without records`() {
        val fixture = fixture()
        val before = snapshot()
        assertThrows(IOException::class.java) { fixture.store(capacity = 0).registerAssociation("register") { true } }
        assertThrows(IllegalStateException::class.java) { fixture.store(capacity = null).registerAssociation("register") { true } }
        assertEquals(before, snapshot())
    }

    @Test fun `A B A context generation change stops remaining bindings`() {
        val fixture = fixture()
        var generation = 0
        val interrupted = object : MetadataOperations(fixture.internal) {
            override fun promote(source: File, target: File) {
                super.promote(source, target)
                if (target == fixture.binding(GameTargetType.DATA)) generation += 2
            }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.store(operations = interrupted).registerAssociation("register") { generation == 0 }
        }
        assertEquals(TargetAssociationState.REGISTRATION_PENDING, fixture.store().inspectAssociation().state)
        assertFalse(fixture.binding(GameTargetType.GAME_ROOT).exists())
    }

    @Test fun `missing journal or required binding after registration is not repaired on read or retry`() {
        for (removeJournal in listOf(false, true)) {
            val fixture = fixture()
            fixture.store().registerAssociation("register") { true }
            if (removeJournal) fixture.journal.delete() else fixture.binding(GameTargetType.DATA).delete()
            val before = snapshot()
            assertThrows(IllegalStateException::class.java) { fixture.store().inspectAssociation() }
            assertThrows(IllegalStateException::class.java) { fixture.store().registerAssociation("register") { true } }
            assertEquals(before, snapshot())
        }
    }

    @Test fun `corrupt unsupported duplicate or foreign bindings and journals never resave`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val journal = fixture.journal.readText()
        for (text in listOf("", "{", JSONObject(journal).put("schemaVersion", 4).toString(),
            journal.replace("\"revision\":", "\"revision\":2,\"revision\":"), JSONObject(journal).put("future", true).toString())) {
            fixture.journal.writeText(text)
            val before = snapshot()
            assertThrows(Exception::class.java) { fixture.store().registerAssociation("again") { true } }
            assertEquals(before, snapshot())
        }
        fixture.journal.writeText(journal)
        val binding = fixture.binding(GameTargetType.DATA)
        val original = binding.readText()
        for (edit in listOf<(JSONObject) -> Unit>({ it.put("role", "GAME_ROOT") }, { it.put("journalPath", "../../foreign.json") },
            { it.put("schemaVersion", 9) }, { it.put("directory", "/different/Data") })) {
            binding.writeText(JSONObject(original).also(edit).toString())
            val before = snapshot()
            assertThrows(Exception::class.java) { fixture.store().inspectAssociation() }
            assertEquals(before, snapshot())
        }
    }

    @Test fun `fresh journal absence beside unknown evidence cannot initialize history`() {
        val fixture = fixture()
        File(checkNotNull(fixture.journal.parentFile), "unknown.json").writeText("preserve")
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) { fixture.store().registerAssociation("register") { true } }
        assertEquals(before, snapshot())
    }

    @Test fun `candidate preparation never selects candidate and same request does not recopy`() {
        val fixture = fixture()
        val completed = fixture.seedCompleted()
        val oldGame = fixture.gameSnapshot()
        val texts = texts(fixture.identity, "candidate")
        val revision = fixture.store().inspectAssociation().journal!!.revision
        val candidate = fixture.store().prepareMetadata("prepare-b", revision, "candidate", "profile-b", digest, texts) { true }
        val association = fixture.store().inspectAssociation()
        assertEquals(TargetAssociationState.PREPARATION_PENDING, association.state)
        assertEquals(completed, association.journal!!.completed)
        assertEquals(candidate, association.journal.pending!!.candidate)
        assertEquals(completed, fixture.store().readCompletedMetadata()!!.reference)
        val before = snapshot()
        val promotions = fixture.operations.promotions.size
        for (capacity in listOf(0L, null)) {
            assertEquals(candidate, fixture.store(capacity = capacity).prepareMetadata("prepare-b", revision, "candidate", "profile-b", digest, texts) { true })
        }
        assertEquals(before, snapshot())
        assertEquals(promotions, fixture.operations.promotions.size)
        assertEquals(oldGame, fixture.gameSnapshot())
    }

    @Test fun `conflicting preparation or in place completed replacement cannot mutate current metadata`() {
        val fixture = fixture()
        fixture.seedCompleted()
        val revision = fixture.store().inspectAssociation().journal!!.revision
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("prepare", revision, "completed", "b", digest, texts(fixture.identity, "completed")) { true }
        }
        assertEquals(before, snapshot())
        val texts = texts(fixture.identity, "candidate")
        fixture.store().prepareMetadata("prepare", revision, "candidate", "b", digest, texts) { true }
        val prepared = snapshot()
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("different", revision + 1, "other", "b", digest, texts(fixture.identity, "other")) { true }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("prepare", revision, "candidate", "b", "b".repeat(64), texts) { true }
        }
        assertEquals(prepared, snapshot())
    }

    @Test fun `preparation interruption before first candidate preserves completed generation`() {
        val fixture = fixture()
        val completed = fixture.seedCompleted()
        val revision = fixture.store().inspectAssociation().journal!!.revision
        val interrupted = object : MetadataOperations(fixture.internal) {
            override fun writeAndSync(file: File, bytes: ByteArray) {
                if (checkNotNull(file.parentFile).name == fixture.paths.getDeploymentGenerationDirectory(fixture.identity.mode, fixture.identity.rootDirectory, "candidate").name)
                    throw IOException("fixture candidate write failure")
                super.writeAndSync(file, bytes)
            }
        }
        val texts = texts(fixture.identity, "candidate")
        assertThrows(CriticalStorageException::class.java) {
            fixture.store(operations = interrupted).prepareMetadata("prepare", revision, "candidate", "b", digest, texts) { true }
        }
        assertEquals(completed, fixture.store().inspectAssociation().journal!!.completed)
        fixture.store().prepareMetadata("prepare", revision, "candidate", "b", digest, texts) { true }
        assertEquals(completed, fixture.store().readCompletedMetadata()!!.reference)
    }

    @Test fun `conflicting retained candidate file and unreferenced generation are preserved`() {
        val fixture = fixture()
        fixture.seedCompleted()
        val revision = fixture.store().inspectAssociation().journal!!.revision
        val texts = texts(fixture.identity, "candidate")
        fixture.store().prepareMetadata("prepare", revision, "candidate", "b", digest, texts) { true }
        fixture.generationFile("candidate", TargetMetadataKind.ROOT_MANIFEST).writeText("foreign bytes")
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("prepare", revision, "candidate", "b", digest, texts) { true }
        }
        assertEquals(before, snapshot())
        val stray = fixture.paths.getDeploymentGenerationDirectory(fixture.identity.mode, fixture.identity.rootDirectory, "stray").apply { mkdirs() }
        File(stray, "evidence").writeText("keep")
        val retained = snapshot()
        assertThrows(IllegalStateException::class.java) { fixture.store().inspectAssociation() }
        assertEquals(retained, snapshot())
    }

    @Test fun `completed metadata checks all hashes counts roles and destination without payload reads`() {
        val fixture = fixture()
        val completed = fixture.seedCompleted()
        val before = snapshot()
        assertEquals(completed, fixture.store().readCompletedMetadata()!!.reference)
        assertEquals(before, snapshot())
        val file = fixture.generationFile("completed", TargetMetadataKind.DATA_INVENTORY)
        file.appendText(" ")
        assertThrows(IllegalStateException::class.java) { fixture.store().readCompletedMetadata() }
        assertEquals(TargetAssociationState.BOUND, fixture.store().inspectAssociation().state)
    }

    @Test fun `completed metadata read rejects changed journal after pinned files were read`() {
        val fixture = fixture()
        fixture.seedCompleted()
        var changed = false
        val race = object : MetadataOperations(fixture.internal) {
            override fun readBounded(file: File, maxBytes: Int): ByteArray {
                if (!changed && file == fixture.generationFile("completed", TargetMetadataKind.DATA_MANIFEST)) {
                    changed = true
                    val record = TargetJournalCodec.decode(fixture.journal.readText())
                    fixture.journal.writeText(TargetJournalCodec.encode(record.copy(revision = record.revision + 1)))
                }
                return super.readBounded(file, maxBytes)
            }
        }
        val gameBefore = fixture.gameSnapshot()
        assertThrows(IllegalStateException::class.java) { fixture.store(operations = race).readCompletedMetadata() }
        assertEquals(gameBefore, fixture.gameSnapshot())
        assertTrue(race.promotions.isEmpty())
    }

    @Test fun `valid hashes cannot bless wrong scope role type or colliding inventory paths`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val good = texts(fixture.identity, "candidate")
        val invalid = listOf(
            good.copy(dataManifest = JSONObject(good.dataManifest).put("kind", "ROOT_MANIFEST").toString()),
            good.copy(dataManifest = JSONObject(good.dataManifest).put("destinationDigest", "b".repeat(64)).toString()),
            good.copy(dataInventory = JSONObject(good.dataInventory).put("paths", JSONArray().put(1)).toString()),
            good.copy(dataInventory = JSONObject(good.dataInventory).put("paths", JSONArray().put("A.ini").put("a.ini")).toString())
        )
        for (bad in invalid) {
            val before = snapshot()
            assertThrows(IllegalArgumentException::class.java) {
                fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, bad) { true }
            }
            assertEquals(before, snapshot())
        }
        val traversal = good.copy(dataInventory = JSONObject(good.dataInventory).put("paths", JSONArray().put("../outside")).toString())
        val before = snapshot()
        assertThrows(IOException::class.java) {
            fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, traversal) { true }
        }
        assertEquals(before, snapshot())
    }

    @Test fun `original backup pointers need explicit managed scope and hashes but are never opened`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val base = temporary.newFolder("external-backups")
        val backupDirectory = File(base, "old-profile/data").apply { mkdirs() }
        val original = File(backupDirectory, "settings.ini").apply { writeText("original bytes not read by store") }
        val common = JSONObject(texts(fixture.identity, "candidate").dataManifest)
        common.getJSONArray("entries").getJSONObject(0).put("hadPreExistingTargetFile", true).put("backupFilePath", original.absolutePath)
        common.put("backupHashes", JSONArray().put(JSONObject().put("normalizedPath", "settings.ini").put("hash", digest)))
        val candidate = texts(fixture.identity, "candidate").copy(dataManifest = common.toString())
        val before = snapshot()
        assertThrows(Exception::class.java) {
            fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, candidate) { true }
        }
        assertEquals(before, snapshot())
        val scope = TargetBackupScope(base, listOf(backupDirectory), emptyList())
        fixture.store(backupScope = scope).prepareMetadata("prepare", 2, "candidate", "b", digest, candidate) { true }
        assertEquals("original bytes not read by store", original.readText())
        assertTrue(fixture.operations.reads.none { it.toPath().startsWith(base.toPath()) })
    }

    @Test fun `missing backup proof is not retroactively invented from an existing file`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val base = temporary.newFolder("backup-scope")
        val directory = File(base, "data").apply { mkdirs() }
        val backup = File(directory, "settings.ini").apply { writeText("unknown original") }
        val good = texts(fixture.identity, "candidate")
        val manifest = JSONObject(good.dataManifest)
        manifest.getJSONArray("entries").getJSONObject(0).put("hadPreExistingTargetFile", true).put("backupFilePath", backup.absolutePath)
        val before = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store(backupScope = TargetBackupScope(base, listOf(directory), emptyList()))
                .prepareMetadata("prepare", 2, "candidate", "b", digest, good.copy(dataManifest = manifest.toString())) { true }
        }
        assertEquals(before, snapshot())
    }

    @Test fun `legacy mutation API refuses v3 instead of clearing new destination evidence`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val before = snapshot()
        val old = DeploymentJournalRecord(schemaVersion = 2, operationId = "old", gameId = fixture.identity.gameId, profileId = "a",
            status = DeploymentJournalStatus.STARTED, startedAtEpochMillis = 1, completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(0, 0, 0, 0, 0, true, 0, 0), resultSummary = null, failureMessage = null)
        assertThrows(IOException::class.java) { DeploymentJournalRepository(fixture.journal).saveStarted(old) }
        assertEquals(before, snapshot())
    }

    @Test fun `stale revisions context and unsupported physical operation kinds cannot write`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val before = snapshot()
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("prepare", 1, "candidate", "b", digest, texts(fixture.identity, "candidate")) { true }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, texts(fixture.identity, "candidate")) { false }
        }
        assertEquals(before, snapshot())
        val json = JSONObject(fixture.journal.readText()).put("pending", JSONObject().put("operationId", "payload")
            .put("kind", "STARTED").put("candidate", JSONObject.NULL))
        fixture.journal.writeText(json.toString())
        val unsupported = snapshot()
        assertThrows(IllegalArgumentException::class.java) { fixture.store().inspectAssociation() }
        assertEquals(unsupported, snapshot())
    }

    @Test fun `binding and journal caps preserve oversized evidence without fallback`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val original = fixture.journal.readText()
        fixture.journal.writeText(original + " ".repeat(TargetJournalCodec.JOURNAL_BYTES - original.toByteArray().size))
        assertEquals(TargetAssociationState.BOUND, fixture.store().inspectAssociation().state)
        fixture.journal.appendText(" ")
        assertThrows(IOException::class.java) { fixture.store().inspectAssociation() }
        fixture.journal.writeText(original)
        val binding = fixture.binding(GameTargetType.DATA)
        val text = binding.readText()
        binding.writeText(text + " ".repeat(TargetJournalCodec.BINDING_BYTES - text.toByteArray().size))
        assertEquals(TargetAssociationState.BOUND, fixture.store().inspectAssociation().state)
        binding.appendText(" ")
        assertThrows(IOException::class.java) { fixture.store().inspectAssociation() }
    }

    @Test fun `linked internal storage and invalid reference bounds cannot be used`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val binding = fixture.binding(GameTargetType.DATA)
        val other = File(temporary.root, "foreign-record").apply { writeText(binding.readText()) }
        binding.delete(); Files.createSymbolicLink(binding.toPath(), other.toPath())
        assertThrows(IOException::class.java) { fixture.store().inspectAssociation() }
        val pin = TargetMetadataPin(digest, TargetMetadataKind.DATA_MANIFEST.maximumBytes.toLong() + 1, 0)
        val reference = TargetGenerationReference("x", "a", digest, pin, pin, pin)
        assertThrows(IllegalArgumentException::class.java) { TargetJournalCodec.validate(reference) }
    }

    @Test fun `backup scope cannot authorize internal metadata or source directories`() {
        val fixture = fixture()
        val before = snapshot()
        val scope = TargetBackupScope(fixture.internal, listOf(fixture.journal.parentFile!!), emptyList())
        assertThrows(IllegalArgumentException::class.java) { fixture.store(backupScope = scope) }
        assertEquals(before, snapshot())
    }

    @Test fun `Data and Root metadata cannot overlap or omit managed paths from inventory`() {
        val fixture = fixture()
        fixture.store().registerAssociation("register") { true }
        val good = texts(fixture.identity, "candidate")
        for (path in listOf("Data/settings.ini", "data/settings.ini")) {
            val root = JSONObject(good.rootManifest).put("entries", JSONArray().put(
                JSONObject(JSONObject(good.dataManifest).getJSONArray("entries").getJSONObject(0).toString()).put("normalizedPath", path)))
            val before = snapshot()
            assertThrows(IllegalArgumentException::class.java) {
                fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, good.copy(rootManifest = root.toString())) { true }
            }
            assertEquals(before, snapshot())
        }
        val incomplete = good.copy(dataInventory = JSONObject(good.dataInventory).put("paths", JSONArray()).toString())
        val before = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store().prepareMetadata("prepare", 2, "candidate", "b", digest, incomplete) { true }
        }
        assertEquals(before, snapshot())

        val historical = this.fixture()
        val reference = historical.seedCompleted()
        val inventory = historical.generationFile("completed", TargetMetadataKind.DATA_INVENTORY)
        inventory.writeText(JSONObject(inventory.readText()).put("paths", JSONArray()).toString())
        val bytes = inventory.readBytes()
        val repinned = reference.copy(dataInventory = TargetMetadataPin(TargetJournalCodec.hash(bytes), bytes.size.toLong(), 0))
        val record = historical.store().inspectAssociation().journal!!
        historical.journal.writeText(TargetJournalCodec.encode(record.copy(completed = repinned)))
        val retained = snapshot()
        assertThrows(IllegalArgumentException::class.java) { historical.store().readCompletedMetadata() }
        assertEquals(retained, snapshot())
    }

    @Test fun `logical destination digest has stable UTF8 length framed vectors independent of JSON formatting`() {
        val ascii = TargetDestinationIdentity(TargetStorageMode.PHYSICAL, "fallout_nv", "/games/NV/Data", "/games/NV")
        assertEquals("750d41b617a1c3a070739e88a1c256e7fdb4627451d2baa460847b3d391f5ba8", ascii.digest())
        val unicode = ascii.copy(dataDirectory = "/games/caf\u00e9/Data", rootDirectory = "/games/caf\u00e9")
        assertEquals("4b0b6fbab42a0ff7201ea48a237fa846b6b0ebaa236cab58054fbfaae805daa3", unicode.digest())
    }

    @Test fun `UTF8 metadata encoding enforces exact byte caps without replacing malformed text`() {
        val text = "\u00e9".repeat(100)
        assertEquals(200, TargetJournalCodec.bytes(text, 200).size)
        assertThrows(IllegalArgumentException::class.java) { TargetJournalCodec.bytes(text, 199) }
        assertEquals(4, TargetJournalCodec.bytes("\ud83d\ude00", 4).size)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { TargetJournalCodec.bytes("\ud800", 4) }
        assertEquals(0, TargetJournalCodec.bytes("", 1).size)
    }

    private fun texts(identity: TargetDestinationIdentity, generation: String): TargetMetadataTexts {
        fun base(kind: TargetMetadataKind) = JSONObject().put("schemaVersion", 1).put("destinationDigest", identity.digest())
            .put("generationId", generation).put("kind", kind.name)
        val entry = JSONObject().put("normalizedPath", "settings.ini").put("winningModId", "mod-a").put("winningModName", "Mod A")
            .put("sourceFilePath", "/historical/source-not-opened").put("hash", digest).put("hadPreExistingTargetFile", false)
            .put("backupFilePath", JSONObject.NULL).put("backupCreatedAtEpochMillis", JSONObject.NULL)
        return TargetMetadataTexts(base(TargetMetadataKind.DATA_MANIFEST).put("entries", JSONArray().put(entry)).put("backupHashes", JSONArray()).toString(),
            base(TargetMetadataKind.ROOT_MANIFEST).put("entries", JSONArray()).put("backupHashes", JSONArray()).toString(),
            base(TargetMetadataKind.DATA_INVENTORY).put("paths", JSONArray().put("settings.ini")).toString())
    }

    private fun snapshot(): Map<String, String> = temporary.root.walkTopDown().associate { file ->
        file.relativeTo(temporary.root).path to when {
            Files.isSymbolicLink(file.toPath()) -> "link:${Files.readSymbolicLink(file.toPath())}"
            file.isFile -> TargetJournalCodec.hash(file.readBytes())
            else -> "directory"
        }
    }

    private open class MetadataOperations(private val internal: File) : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
        val reads = mutableListOf<File>(); val writes = mutableListOf<File>(); val promotions = mutableListOf<File>()
        override fun read(file: File): ByteArray = throw AssertionError("Unbounded metadata read")
        override fun readBounded(file: File, maxBytes: Int): ByteArray {
            check(file.toPath().startsWith(internal.toPath())) { "Target/backup payload opened" }
            reads.add(file)
            return SystemCriticalMetadataFileOperations.readBounded(file, maxBytes)
        }
        override fun writeAndSync(file: File, bytes: ByteArray) {
            check(file.toPath().startsWith(internal.toPath())) { "Write outside internal metadata" }
            writes.add(file); SystemCriticalMetadataFileOperations.writeAndSync(file, bytes)
        }
        override fun promote(source: File, target: File) {
            check(target.toPath().startsWith(internal.toPath()))
            promotions.add(target); SystemCriticalMetadataFileOperations.promote(source, target)
        }
    }

    private inner class Fixture(val internal: File, val game: File, val identity: TargetDestinationIdentity, prepareParents: Boolean) {
        var profile = "a"; var selectedGame = identity.gameId
        val paths = ProfileStoragePaths(internal, { profile }, { selectedGame })
        val journal get() = paths.getDeploymentTargetJournalFile(identity.mode, identity.rootDirectory)
        val operations = MetadataOperations(internal)
        init {
            if (prepareParents) {
                journal.parentFile!!.mkdirs()
                binding(GameTargetType.DATA).parentFile!!.mkdirs()
            }
        }
        fun binding(role: GameTargetType) = paths.getDeploymentRoleBindingFile(identity.mode, identity.directory(role))
        fun bindings() = listOf(binding(GameTargetType.DATA), binding(GameTargetType.GAME_ROOT))
        fun generationFile(generation: String, kind: TargetMetadataKind) = paths.getDeploymentGenerationFile(identity.mode, identity.rootDirectory, generation, kind)
        fun store(identity: TargetDestinationIdentity = this.identity, operations: CriticalMetadataFileOperations = this.operations,
                  capacity: Long? = Long.MAX_VALUE, backupScope: TargetBackupScope? = null): TargetJournalStorage =
            DeploymentJournalRepository(paths.getDeploymentTargetJournalFile(identity.mode, identity.rootDirectory))
                .stagedTargetStorage(paths, identity, operations, { capacity }, backupScope)
        fun gameSnapshot() = game.walkTopDown().associate { it.relativeTo(game).path to if (it.isFile) it.readText() else "directory" }
        fun seedCompleted(): TargetGenerationReference {
            store().registerAssociation("register") { true }
            val record = store().inspectAssociation().journal!!
            val content = texts(identity, "completed")
            fun pin(kind: TargetMetadataKind): TargetMetadataPin {
                val text = content.text(kind)
                val json = JSONObject(text)
                return TargetMetadataPin(TargetJournalCodec.hash(TargetJournalCodec.bytes(text)), TargetJournalCodec.bytes(text).size.toLong(),
                    json.getJSONArray(if (kind == TargetMetadataKind.DATA_INVENTORY) "paths" else "entries").length())
            }
            val reference = TargetGenerationReference("completed", "profile-a", digest, pin(TargetMetadataKind.DATA_MANIFEST),
                pin(TargetMetadataKind.ROOT_MANIFEST), pin(TargetMetadataKind.DATA_INVENTORY))
            for (kind in TargetMetadataKind.entries) generationFile("completed", kind).apply { parentFile!!.mkdirs(); writeText(content.text(kind)) }
            // Deliberately seeded historical fixture, not a production completion API.
            journal.writeText(TargetJournalCodec.encode(record.copy(revision = 3, completed = reference)))
            return reference
        }
    }

    private fun fixture(prepareParents: Boolean = true): Fixture {
        val internal = temporary.newFolder()
        val game = temporary.newFolder()
        File(game, "Data").mkdirs()
        File(game, "Data/settings.ini").writeText("actual game bytes intentionally differ from recorded metadata")
        return Fixture(internal, game, TargetDestinationIdentity(TargetStorageMode.PHYSICAL, "fallout_nv",
            File(game, "Data").absolutePath, game.absolutePath), prepareParents)
    }
}
