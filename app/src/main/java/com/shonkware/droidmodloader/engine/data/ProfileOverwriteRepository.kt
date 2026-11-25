package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.model.IndexedOverwriteFile
import com.shonkware.droidmodloader.engine.model.OverwriteBatchPhase
import com.shonkware.droidmodloader.engine.model.OverwriteCaptureReceipt
import com.shonkware.droidmodloader.engine.model.PendingOverwriteBatch
import com.shonkware.droidmodloader.engine.model.ProfileOverwriteFile
import com.shonkware.droidmodloader.engine.model.ProfileOverwriteRecord
import com.shonkware.droidmodloader.engine.model.VerifiedProfileOverwrite
import com.shonkware.droidmodloader.engine.rules.DeployFileClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal enum class OverwriteRecordFailure { INVALID, UNREADABLE, UNSUPPORTED }
internal class OverwriteRecordException(val failure: OverwriteRecordFailure, cause: Exception) :
    IOException("Overwrite record is ${failure.name.lowercase()}; preserve existing evidence", cause)

/** Staged private source owner, not a target scanner or capture/recovery executor.
 * Call under existing exclusive session admission, with captured game/profile IDs,
 * off the UI thread. Caller prepares persisted parents. Payload changes require a
 * pending write gate; a complete verified content revision is published independently
 * of game targets. No directory initialization, payload copy, deletion or adoption. */
internal class ProfileOverwriteRepository(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val profileId: String,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations,
    private val indexFiles: (File, InstallCancellationSignal) -> List<ModFileIndexEntry> = ModFileIndexService::indexVerifiedFiles
) {
    companion object {
        const val MAX_RECORD_BYTES = 8 * 1024 * 1024
        private const val MAX_ENTRIES = 200_000
        private val RECORD_KEYS = setOf("schemaVersion", "gameId", "profileId", "recordRevision", "contentRevision", "files", "receipts", "pendingBatch")
        private val FILE_KEYS = setOf("role", "originalPath", "hash", "sizeBytes")
        private val RECEIPT_KEYS = setOf("captureOperationId", "targetGenerationId", "role", "originalPath", "hash", "contentRevision")
        private val PENDING_KEYS = setOf("operationId", "phase")
        private val roles = listOf(DeployScope.DATA, DeployScope.GAME_ROOT)
    }
    private val classifier = DeployFileClassifier()
    private val ownerFile get() = paths.getProfileOverwriteOwnerFile(gameId, profileId)
    private fun metadata() = CriticalMetadataFile(ownerFile, operations)

    fun loadRecord(): ProfileOverwriteRecord? {
        val text = try { metadata().read(MAX_RECORD_BYTES) }
        catch (failure: Exception) { throw OverwriteRecordException(OverwriteRecordFailure.UNREADABLE, failure) }
        return text?.let(::decode)
    }

    /** An absent owner is distinct from saved empty. It is usable as fresh empty only
     * when the managed layout is absent/verified empty; no folder-content discovery. */
    fun loadVerifiedSource(signal: InstallCancellationSignal = InstallCancellationSignal.NONE): VerifiedProfileOverwrite {
        signal.throwIfCancellationRequested()
        val record = loadRecord()
        if (record == null) {
            verifyFreshEmpty(signal)
            check(loadRecord() == null) { "Overwrite owner changed while reading" }
            signal.throwIfCancellationRequested()
            return VerifiedProfileOverwrite(gameId, profileId, null, emptyList())
        }
        check(record.pendingBatch == null) { "Overwrite has pending private work; source is unavailable" }
        val indexed = verifyFiles(record, signal)
        operations.syncDirectory(ownerFile.parentFile!!)
        check(loadRecord() == record) { "Overwrite revision changed while indexing" }
        signal.throwIfCancellationRequested()
        return VerifiedProfileOverwrite(gameId, profileId, record, indexed)
    }

    /** Record-only transitions while pending never expose their temporarily mixed
     * directory. New content can commit only from a pending gate after full verification
     * and payload persistence. Receipts grant no game-target mutation authority. */
    fun save(record: ProfileOverwriteRecord, expectedRecordRevision: Long?,
             signal: InstallCancellationSignal = InstallCancellationSignal.NONE) {
        signal.throwIfCancellationRequested()
        validate(record)
        val current = loadRecord()
        check(current?.recordRevision == expectedRecordRevision && expectedRecordRevision != Long.MAX_VALUE &&
            record.recordRevision == Math.addExact(expectedRecordRevision ?: 0L, 1L)) { "Overwrite record revision changed" }
        if (current == null) {
            check(record.contentRevision == 1L && record.files.isEmpty() && record.receipts.isEmpty() && record.pendingBatch == null) {
                "An absent owner cannot adopt nonempty Overwrite content"
            }
            verifyFreshEmpty(signal)
        } else {
            if (current.pendingBatch != null && record.pendingBatch != null) {
                check(current.pendingBatch.operationId == record.pendingBatch.operationId) { "Another Overwrite batch is pending" }
                check(current.pendingBatch.phase != OverwriteBatchPhase.CLEANUP || record.pendingBatch.phase == OverwriteBatchPhase.CLEANUP) {
                    "Pending cleanup must complete before another write"
                }
            }
            val contentChanged = record.contentRevision != current.contentRevision
            if (contentChanged) {
                check(record.contentRevision == Math.addExact(current.contentRevision, 1L) &&
                    current.pendingBatch?.phase in setOf(OverwriteBatchPhase.PREPARING, OverwriteBatchPhase.APPLYING)) {
                    "A new content revision requires a pending private write"
                }
                check(record.pendingBatch == null || (record.pendingBatch.operationId == current.pendingBatch?.operationId &&
                    record.pendingBatch.phase == OverwriteBatchPhase.CLEANUP)) { "Invalid content commit phase" }
            } else check(record.files == current.files && record.receipts == current.receipts) {
                "Files and receipts changed without a content revision"
            }
            if (current.pendingBatch == null || contentChanged || record.pendingBatch == null) {
                val verified = if (contentChanged) record else current
                verifyFiles(verified, signal)
                roles.forEach { role ->
                    val directory = paths.getProfileOverwriteRoleDirectory(gameId, profileId, role)
                    if (directoryPresent(directory)) ModFileIndexService.persistVerifiedFiles(directory, signal, operations)
                }
                verifyFiles(verified, signal)
            }
        }
        val text = encode(record)
        signal.throwIfCancellationRequested()
        check(loadRecord() == current) { "Overwrite record changed before publication" }
        metadata().replaceDurably(text, MAX_RECORD_BYTES)
    }

    private fun verifyFreshEmpty(signal: InstallCancellationSignal) {
        verifyLayout(signal)
        roles.forEach { role ->
            signal.throwIfCancellationRequested()
            val directory = paths.getProfileOverwriteRoleDirectory(gameId, profileId, role)
            if (directoryPresent(directory)) Files.newDirectoryStream(directory.toPath()).use {
                check(!it.iterator().hasNext()) { "Unowned Overwrite content requires explicit adoption; preserve it" }
            }
        }
    }

    private fun verifyFiles(record: ProfileOverwriteRecord, signal: InstallCancellationSignal): List<IndexedOverwriteFile> {
        verifyLayout(signal)
        val indexed = roles.flatMap { role ->
            signal.throwIfCancellationRequested()
            val directory = paths.getProfileOverwriteRoleDirectory(gameId, profileId, role)
            if (!directoryPresent(directory)) emptyList() else indexFiles(directory, signal).map { entry ->
                check(entry.normalizedPath == ModFileIndexService.normalizedSourcePath(entry.originalPath))
                IndexedOverwriteFile(role, entry, File(directory, entry.originalPath).absolutePath)
            }
        }
        val actual = indexed.map { ProfileOverwriteFile(it.role, it.indexEntry.originalPath, it.indexEntry.hash, it.indexEntry.sizeBytes) }
        val expected = record.files.sortedBy(::fileKey)
        val observed = actual.sortedBy(::fileKey)
        check(observed == expected) {
            val firstDifference = (0 until maxOf(expected.size, observed.size)).first { expected.getOrNull(it) != observed.getOrNull(it) }
            // One bounded row per side, never an entire inventory or file contents.
            "Overwrite payload/index does not match its complete committed record: " +
                "expected ${expected.getOrNull(firstDifference)}; observed ${observed.getOrNull(firstDifference)}"
        }
        return indexed
    }

    private fun verifyLayout(signal: InstallCancellationSignal) {
        val directory = paths.getProfileOverwriteDirectory(gameId, profileId)
        if (!directoryPresent(directory)) return
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (child in children) {
                signal.throwIfCancellationRequested()
                check(++count <= MAX_ENTRIES) { "Overwrite metadata directory exceeds inspection bound" }
                val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(!attributes.isSymbolicLink) { "Overwrite layout contains a link" }
                val name = child.fileName.toString()
                when {
                    name == "data" || name == "game_root" -> check(attributes.isDirectory)
                    name == "owner.json" -> check(attributes.isRegularFile)
                    name.startsWith(".owner.json.") && name.endsWith(".tmp") ->
                        check(attributes.isRegularFile && attributes.size() <= MAX_RECORD_BYTES) // Never recovery authority.
                    else -> error("Unknown Overwrite layout evidence must be preserved")
                }
            }
        }
    }

    private fun directoryPresent(directory: File): Boolean = try {
        requireCriticalDirectory(directory); true
    } catch (_: NoSuchFileException) { false }

    private fun fileKey(file: ProfileOverwriteFile) = file.role.name + ":" + ModFileIndexService.normalizedSourcePath(file.originalPath)

    private fun validate(record: ProfileOverwriteRecord) {
        require(record.gameId == gameId && record.profileId == profileId) { "Wrong Overwrite game/profile" }
        paths.getProfileOverwriteOwnerFile(gameId, profileId)
        require(record.recordRevision > 0 && record.contentRevision > 0)
        require(record.files.size <= MAX_ENTRIES && record.receipts.size <= MAX_ENTRIES)
        val entries = hashMapOf<Pair<DeployScope, String>, Pair<String, Boolean>>()
        val files = hashMapOf<String, ProfileOverwriteFile>()
        record.files.forEach { file ->
            require(file.role in roles && file.sizeBytes >= 0)
            val normalized = ModFileIndexService.normalizedSourcePath(file.originalPath)
            require(!classifier.isReservedOverwritePath(normalized)) { "Reserved manager/helper/export path cannot enter Overwrite" }
            require(file.hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid source hash" }
            val rawParts = file.originalPath.split('/'); val normalizedParts = normalized.split('/')
            rawParts.indices.forEach { index ->
                val rawPrefix = rawParts.take(index + 1).joinToString("/")
                val key = file.role to normalizedParts.take(index + 1).joinToString("/")
                val value = rawPrefix to (index < rawParts.lastIndex)
                val previous = entries.putIfAbsent(key, value)
                require(previous == null || (previous == value && value.second)) { "Overwrite path/case/type collision" }
                require(entries.size <= MAX_ENTRIES) { "Overwrite tree exceeds inspection bound" }
            }
            require(files.put(fileKey(file), file) == null) { "Duplicate Overwrite file" }
        }
        val receiptKeys = hashSetOf<List<String>>()
        record.receipts.forEach { receipt ->
            opaqueIdentity(receipt.captureOperationId); opaqueIdentity(receipt.targetGenerationId)
            require(receipt.role == DeployScope.DATA) { "First capture receipts require the Data role" }
            require(receipt.contentRevision == record.contentRevision)
            val key = receipt.role.name + ":" + ModFileIndexService.normalizedSourcePath(receipt.originalPath)
            val destination = files[key]
            require(destination != null && destination.originalPath == receipt.originalPath && destination.hash == receipt.hash) {
                "Capture receipt does not match this complete source revision"
            }
            require(receiptKeys.add(listOf(receipt.captureOperationId, receipt.targetGenerationId, key))) { "Duplicate capture receipt" }
        }
        record.pendingBatch?.let { opaqueIdentity(it.operationId) }
    }

    private fun opaqueIdentity(value: String) {
        require(value.isNotBlank() && value.none { it.isISOControl() })
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= 96)
    }

    private fun decode(text: String): ProfileOverwriteRecord = try {
        val json = ModLibraryJsonReader.read(text)
        if (json.requiredInt("schemaVersion") != 1) throw UnsupportedRecord()
        keys(json, RECORD_KEYS)
        val fileRows = json.getJSONArray("files"); val receiptRows = json.getJSONArray("receipts")
        require(fileRows.length() <= MAX_ENTRIES && receiptRows.length() <= MAX_ENTRIES)
        val files = List(fileRows.length()) { index ->
            val row = fileRows.getJSONObject(index); keys(row, FILE_KEYS)
            ProfileOverwriteFile(role(row), row.requiredString("originalPath"), row.requiredString("hash"), row.requiredLong("sizeBytes"))
        }
        val receipts = List(receiptRows.length()) { index ->
            val row = receiptRows.getJSONObject(index); keys(row, RECEIPT_KEYS)
            OverwriteCaptureReceipt(row.requiredString("captureOperationId"), row.requiredString("targetGenerationId"),
                role(row), row.requiredString("originalPath"), row.requiredString("hash"), row.requiredLong("contentRevision"))
        }
        val pending = if (json.isNull("pendingBatch")) null else json.getJSONObject("pendingBatch").let { row ->
            keys(row, PENDING_KEYS)
            PendingOverwriteBatch(row.requiredString("operationId"), OverwriteBatchPhase.entries.firstOrNull {
                it.name == row.requiredString("phase")
            } ?: throw UnsupportedRecord())
        }
        ProfileOverwriteRecord(json.requiredString("gameId"), json.requiredString("profileId"), json.requiredLong("recordRevision"),
            json.requiredLong("contentRevision"), files, receipts, pending).also(::validate)
    } catch (failure: Exception) {
        throw OverwriteRecordException(if (failure is UnsupportedRecord) OverwriteRecordFailure.UNSUPPORTED else OverwriteRecordFailure.INVALID, failure)
    }

    private fun role(row: JSONObject): DeployScope = DeployScope.entries.firstOrNull { it.name == row.requiredString("role") }
        ?: throw UnsupportedRecord()
    private class UnsupportedRecord : IOException("Unknown Overwrite version, fields or phase")
    private fun keys(json: JSONObject, expected: Set<String>) {
        val actual = json.keys().asSequence().toSet()
        if ((actual - expected).isNotEmpty()) throw UnsupportedRecord()
        require(actual == expected) { "Missing Overwrite record fields" }
    }

    private fun encode(record: ProfileOverwriteRecord): String {
        var rowBytes = 0L
        fun boundedRow(row: JSONObject): JSONObject {
            rowBytes = Math.addExact(rowBytes, row.toString().toByteArray(Charsets.UTF_8).size.toLong() + 1)
            require(rowBytes <= MAX_RECORD_BYTES) { "Overwrite record exceeds byte limit" }
            return row
        }
        return JSONObject().apply {
            put("schemaVersion", 1); put("gameId", record.gameId); put("profileId", record.profileId)
            put("recordRevision", record.recordRevision); put("contentRevision", record.contentRevision)
            put("files", JSONArray().apply { record.files.forEach { file -> put(boundedRow(JSONObject().apply {
                put("role", file.role.name); put("originalPath", file.originalPath); put("hash", file.hash); put("sizeBytes", file.sizeBytes)
            })) } })
            put("receipts", JSONArray().apply { record.receipts.forEach { receipt -> put(boundedRow(JSONObject().apply {
                put("captureOperationId", receipt.captureOperationId); put("targetGenerationId", receipt.targetGenerationId)
                put("role", receipt.role.name); put("originalPath", receipt.originalPath); put("hash", receipt.hash)
                put("contentRevision", receipt.contentRevision)
            })) } })
            put("pendingBatch", record.pendingBatch?.let { batch -> JSONObject().apply {
                put("operationId", batch.operationId); put("phase", batch.phase.name)
            } } ?: JSONObject.NULL)
        }.toString()
    }
}
