package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

class ProfileOverwriteRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private val profileId = "A"
    private var selectedProfile = "B"
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { selectedProfile }, { "other-game" }) }
    private val repository by lazy { ProfileOverwriteRepository(paths, gameId, profileId) }
    private val owner get() = paths.getProfileOverwriteOwnerFile(gameId, profileId)
    private fun roleDirectory(role: DeployScope = DeployScope.DATA) = paths.getProfileOverwriteRoleDirectory(gameId, profileId, role)
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun emptyRecord() = ProfileOverwriteRecord(gameId, profileId, 1, 1, emptyList())
    private fun initialize(): ProfileOverwriteRecord {
        owner.parentFile!!.mkdirs(); roleDirectory().mkdir(); roleDirectory(DeployScope.GAME_ROOT).mkdir()
        return emptyRecord().also { repository.save(it, null) }
    }
    private fun pending(record: ProfileOverwriteRecord, phase: OverwriteBatchPhase = OverwriteBatchPhase.APPLYING) =
        record.copy(recordRevision = record.recordRevision + 1, pendingBatch = PendingOverwriteBatch("batch-one", phase))
            .also { repository.save(it, record.recordRevision) }
    private fun fileRecord(path: String, text: String, role: DeployScope = DeployScope.DATA): ProfileOverwriteFile {
        val file = File(roleDirectory(role), path); file.parentFile!!.mkdirs(); file.writeText(text)
        return ProfileOverwriteFile(role, path, hash(text), text.toByteArray().size.toLong())
    }
    private fun commit(record: ProfileOverwriteRecord, files: List<ProfileOverwriteFile>, receipts: List<OverwriteCaptureReceipt> = emptyList()) =
        record.copy(recordRevision = record.recordRevision + 1, contentRevision = record.contentRevision + 1,
            files = files, receipts = receipts, pendingBatch = null).also { repository.save(it, record.recordRevision) }
    private fun installed(): ProfileOverwriteRecord {
        val gated = pending(initialize())
        return commit(gated, listOf(fileRecord("SomeMod/Settings.ini", "first config")))
    }

    @Test fun `absence is verified fresh empty without creating storage or a saved record`() {
        assertNull(repository.loadRecord())
        val source = repository.loadVerifiedSource()
        assertNull(source.record); assertTrue(source.files.isEmpty()); assertEquals(0L, source.contentRevision)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `cancelled fresh and saved reads do not return a usable source`() {
        val controller = InstallCancellationController().also { it.cancel() }
        assertThrows(Exception::class.java) { repository.loadVerifiedSource(controller.signal) }
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        installed()
        val before = owner.readBytes()
        assertThrows(Exception::class.java) { repository.loadVerifiedSource(controller.signal) }
        assertArrayEquals(before, owner.readBytes())
    }

    @Test fun `initial save requires prepared persisted parent and cannot adopt unknown content`() {
        val failure = assertThrows(CriticalStorageException::class.java) { repository.save(emptyRecord(), null) }
        assertFalse(failure.mutationMayHaveOccurred)
        owner.parentFile!!.mkdirs(); roleDirectory().mkdir()
        File(roleDirectory(), "unknown.ini").writeText("keep these bytes")
        assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
        assertThrows(Exception::class.java) { repository.save(emptyRecord(), null) }
        assertThrows(Exception::class.java) { repository.save(emptyRecord().copy(files = listOf(
            ProfileOverwriteFile(DeployScope.DATA, "unknown.ini", hash("keep these bytes"), 16))), null) }
        assertNull(repository.loadRecord()); assertEquals("keep these bytes", File(roleDirectory(), "unknown.ini").readText())
    }

    @Test fun `saved empty differs from absent and original folder paths case role and hashes round trip`() {
        val initial = initialize()
        assertEquals(initial, repository.loadVerifiedSource().record)
        val gated = pending(initial)
        val files = listOf(fileRecord("Data/SomeMod/Settings.ini", "Data inside Data is literal"),
            fileRecord("ENBLocal.ini", "root config", DeployScope.GAME_ROOT))
        val committed = commit(gated, files)
        val source = repository.loadVerifiedSource()
        assertEquals(committed, source.record); assertEquals(2L, source.contentRevision)
        assertEquals(setOf("data/somemod/settings.ini", "enblocal.ini"), source.files.map { it.indexEntry.normalizedPath }.toSet())
        assertTrue(source.files.single { it.role == DeployScope.DATA }.sourceFilePath.endsWith("/data/Data/SomeMod/Settings.ini"))
    }

    @Test fun `all pending phases gate reads and do not advance committed content revision`() {
        var current = installed()
        OverwriteBatchPhase.entries.forEach { phase ->
            current = pending(current, phase)
            assertEquals(2L, repository.loadRecord()!!.contentRevision)
            assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
            current = current.copy(recordRevision = current.recordRevision + 1, pendingBatch = null)
                .also { repository.save(it, it.recordRevision - 1) }
            assertEquals(2L, repository.loadVerifiedSource().contentRevision)
        }
    }

    @Test fun `a second batch and cleanup-to-write transition cannot replace pending authority`() {
        val current = pending(installed(), OverwriteBatchPhase.CLEANUP)
        val before = owner.readBytes()
        assertThrows(Exception::class.java) { repository.save(current.copy(recordRevision = current.recordRevision + 1,
            pendingBatch = PendingOverwriteBatch("different", OverwriteBatchPhase.PREPARING)), current.recordRevision) }
        assertThrows(Exception::class.java) { repository.save(current.copy(recordRevision = current.recordRevision + 1,
            pendingBatch = current.pendingBatch!!.copy(phase = OverwriteBatchPhase.APPLYING)), current.recordRevision) }
        assertThrows(Exception::class.java) { repository.save(current.copy(recordRevision = current.recordRevision + 1,
            contentRevision = current.contentRevision + 1), current.recordRevision) }
        assertArrayEquals(before, owner.readBytes())
    }

    @Test fun `record publication cannot clear a write gate over mixed or unknown files`() {
        val current = pending(installed())
        File(roleDirectory(), "SomeMod/Settings.ini").writeText("changed bytes")
        val before = owner.readBytes()
        assertThrows(Exception::class.java) { repository.save(current.copy(recordRevision = current.recordRevision + 1,
            pendingBatch = null), current.recordRevision) }
        assertArrayEquals(before, owner.readBytes()); assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
    }

    @Test fun `same-size timestamp-preserving byte changes cannot reuse an old verified hash`() {
        installed()
        val file = File(roleDirectory(), "SomeMod/Settings.ini")
        val oldTime = Files.getLastModifiedTime(file.toPath())
        file.writeText("other config"); Files.setLastModifiedTime(file.toPath(), oldTime)
        assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
        assertEquals("other config", file.readText())
        file.writeText("first config"); Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(oldTime.toMillis() + 4000))
        assertEquals(1, repository.loadVerifiedSource().files.size) // Content, not time, is authority.
    }

    @Test fun `partial or failed index never becomes complete empty content`() {
        installed()
        val partial = ProfileOverwriteRepository(paths, gameId, profileId, indexFiles = { _, _ -> emptyList() })
        assertThrows(Exception::class.java) { partial.loadVerifiedSource() }
        val unreadable = ProfileOverwriteRepository(paths, gameId, profileId, indexFiles = { _, _ -> throw IOException("index read failed") })
        assertThrows(IOException::class.java) { unreadable.loadVerifiedSource() }
        assertEquals(1, repository.loadVerifiedSource().files.size)
    }

    @Test fun `source owner changed while hashing is stale not a completed old projection`() {
        val original = installed()
        var armed = true
        val stale = ProfileOverwriteRepository(paths, gameId, profileId, indexFiles = { directory, signal ->
            ModFileIndexService.indexVerifiedFiles(directory, signal).also {
                if (armed) { armed = false; pending(original) }
            }
        })
        assertThrows(Exception::class.java) { stale.loadVerifiedSource() }
        assertNotNull(repository.loadRecord()!!.pendingBatch)
    }

    @Test fun `receipts bind exact Data destination bytes and committed revision without target authority`() {
        val gated = pending(initialize())
        val file = fileRecord("Config.ini", "reviewed config")
        val receipt = OverwriteCaptureReceipt("batch-one", "target-generation", DeployScope.DATA, file.originalPath, file.hash, 2)
        val committed = commit(gated, listOf(file), listOf(receipt))
        assertEquals(committed, repository.loadRecord())
        val before = owner.readBytes()
        listOf(receipt.copy(hash = "0".repeat(64)), receipt.copy(contentRevision = 1),
            receipt.copy(role = DeployScope.GAME_ROOT), receipt.copy(originalPath = "config.INI")).forEach { invalid ->
            assertThrows(Exception::class.java) { repository.save(committed.copy(recordRevision = 4,
                receipts = listOf(invalid)), committed.recordRevision) }
        }
        assertArrayEquals(before, owner.readBytes())
    }

    @Test fun `unsafe aliased duplicate and reserved paths cannot enter source records`() {
        val gated = pending(initialize())
        val pathsToReject = listOf("../escape", "/absolute.ini", "C:/drive.ini", "a//b.ini", "a/./b.ini", "a\\b.ini",
            ".dml_mod.json", ".dml_library_entry.json", "_dml_backup/file", "DML Export/Current/config.ini",
            "DML - Invalidation.bsa", "plugins.txt", "loadorder.txt", "bad\u0000file", "a:" )
        val before = owner.readBytes()
        pathsToReject.forEach { path ->
            assertThrows(Exception::class.java) { repository.save(gated.copy(recordRevision = 3, contentRevision = 2,
                files = listOf(ProfileOverwriteFile(DeployScope.DATA, path, "0".repeat(64), 0)), pendingBatch = null), 2) }
        }
        listOf(listOf("A.ini", "a.INI"), listOf("Folder/a.ini", "folder/b.ini"), listOf("folder", "folder/a.ini"),
            listOf("caf\u00e9.ini", "cafe\u0301.ini")).forEach { names ->
            assertThrows(Exception::class.java) { repository.save(gated.copy(recordRevision = 3, contentRevision = 2,
                files = names.map { ProfileOverwriteFile(DeployScope.DATA, it, "0".repeat(64), 0) }, pendingBatch = null), 2) }
        }
        assertArrayEquals(before, owner.readBytes())
    }

    @Test fun `malformed unknown unsupported and oversized metadata are preserved not reset`() {
        initialize()
        val valid = owner.readText()
        val cases = listOf("", "broken", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.dropLast(1) + ",\"unknown\":true}", valid.dropLast(1) + ",\"profileId\":\"A\"}",
            valid.replace("\"recordRevision\":1", "\"recordRevision\":\"1\""))
        cases.forEach { text ->
            owner.writeText(text)
            assertThrows(OverwriteRecordException::class.java) { repository.loadRecord() }
            assertThrows(Exception::class.java) { repository.save(emptyRecord(), null) }
            assertEquals(text, owner.readText())
        }
        owner.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(OverwriteRecordFailure.UNREADABLE, assertThrows(OverwriteRecordException::class.java) { repository.loadRecord() }.failure)
        owner.outputStream().use { output -> repeat(ProfileOverwriteRepository.MAX_RECORD_BYTES / 1024 + 1) { output.write(ByteArray(1024)) } }
        assertThrows(OverwriteRecordException::class.java) { repository.loadRecord() }
        assertTrue(owner.length() > ProfileOverwriteRepository.MAX_RECORD_BYTES)
    }

    @Test fun `linked owner and payload cannot read or persist another directory`() {
        val current = installed()
        val outside = temporaryFolder.newFolder("outside"); val outsideFile = File(outside, "Keep.ini").also { it.writeText("outside bytes") }
        File(roleDirectory(), "SomeMod/Settings.ini").delete()
        Files.createSymbolicLink(File(roleDirectory(), "SomeMod/Settings.ini").toPath(), outsideFile.toPath())
        assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
        assertThrows(Exception::class.java) { repository.save(current.copy(recordRevision = current.recordRevision + 1,
            pendingBatch = PendingOverwriteBatch("linked", OverwriteBatchPhase.APPLYING)), current.recordRevision) }
        assertEquals("outside bytes", outsideFile.readText())
        owner.delete(); Files.createSymbolicLink(owner.toPath(), outsideFile.toPath())
        assertThrows(OverwriteRecordException::class.java) { repository.loadRecord() }
        assertEquals("outside bytes", outsideFile.readText())
    }

    @Test fun `before-promotion save failure retains old record and after-promotion uncertainty is reconciled by strict reread`() {
        val original = installed()
        var failAfter = false
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                if (!failAfter) throw IOException("before promotion")
                SystemCriticalMetadataFileOperations.promote(source, target)
                throw IOException("after promotion")
            }
        }
        val failing = ProfileOverwriteRepository(paths, gameId, profileId, operations)
        val current = original
        val nextPending = current.copy(recordRevision = current.recordRevision + 1, pendingBatch = PendingOverwriteBatch("failure", OverwriteBatchPhase.APPLYING))
        val oldBytes = owner.readBytes()
        assertTrue(assertThrows(CriticalStorageException::class.java) { failing.save(nextPending, current.recordRevision) }.mutationMayHaveOccurred)
        assertArrayEquals(oldBytes, owner.readBytes())
        assertEquals(current, repository.loadVerifiedSource().record)
        failAfter = true
        assertTrue(assertThrows(CriticalStorageException::class.java) { failing.save(nextPending, current.recordRevision) }.mutationMayHaveOccurred)
        assertEquals(nextPending, repository.loadRecord()); assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
    }

    @Test fun `committed owner barrier uncertainty is reread as verified new content not rolled back`() {
        val gated = pending(initialize())
        val file = fileRecord("Config.ini", "saved new bytes")
        val committed = gated.copy(recordRevision = 3, contentRevision = 2, files = listOf(file), pendingBatch = null)
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
                if (armed && directory == owner.parentFile && JSONObject(owner.readText()).let {
                    it.getLong("contentRevision") == 2L && it.isNull("pendingBatch")
                }) { armed = false; throw IOException("owner parent sync reported failure after commit") }
            }
        }
        val uncertain = ProfileOverwriteRepository(paths, gameId, profileId, operations)
        assertTrue(assertThrows(CriticalStorageException::class.java) { uncertain.save(committed, 2) }.mutationMayHaveOccurred)
        assertFalse(armed); assertEquals(committed, uncertain.loadVerifiedSource().record)
        assertEquals("saved new bytes", File(roleDirectory(), "Config.ini").readText())
    }

    @Test fun `payload persistence failure cannot publish a new content revision`() {
        val gated = pending(initialize())
        val file = fileRecord("Config.ini", "new private bytes")
        val before = owner.readBytes()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory == roleDirectory()) throw IOException("source directory barrier failed")
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
            }
        }
        val failing = ProfileOverwriteRepository(paths, gameId, profileId, operations)
        assertThrows(Exception::class.java) { failing.save(gated.copy(recordRevision = 3, contentRevision = 2,
            files = listOf(file), pendingBatch = null), 2) }
        assertArrayEquals(before, owner.readBytes()); assertThrows(Exception::class.java) { repository.loadVerifiedSource() }
        assertEquals("new private bytes", File(roleDirectory(), "Config.ini").readText())
    }

    @Test fun `cancellation and stale revisions cannot publish or retarget a source`() {
        val original = installed(); val before = owner.readBytes()
        val controller = InstallCancellationController().also { it.cancel() }
        val next = original.copy(recordRevision = original.recordRevision + 1,
            pendingBatch = PendingOverwriteBatch("cancel", OverwriteBatchPhase.APPLYING))
        assertThrows(Exception::class.java) { repository.save(next, original.recordRevision, controller.signal) }
        assertThrows(Exception::class.java) { repository.save(next, 0) }
        assertThrows(Exception::class.java) { repository.save(next.copy(profileId = "B"), original.recordRevision) }
        assertThrows(Exception::class.java) { repository.save(next.copy(gameId = "other"), original.recordRevision) }
        selectedProfile = "B"; selectedProfile = "A"
        assertArrayEquals(before, owner.readBytes()); assertEquals(profileId, repository.loadVerifiedSource().profileId)
    }
}
