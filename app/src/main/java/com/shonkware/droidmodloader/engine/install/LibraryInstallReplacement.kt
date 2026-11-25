package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.GameLibraryEntry
import com.shonkware.droidmodloader.engine.model.GameModLibrary
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Private shared installation/removal transaction. Call under the retained session's exclusive
 * admission, off the UI thread. No active reader or legacy replacement uses this path.
 * An unresolved record gates the whole game's staged view and conflicting writers.
 * A failed barrier is never treated as proof that a move or commit did not happen. */
internal class LibraryInstallReplacement(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val metadataOperations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations,
    private val directoryMove: CriticalDirectoryMove = CriticalDirectoryMove(),
    private val directoryOperations: InstalledModDirectoryOperations = DefaultInstalledModDirectoryOperations
) {
    companion object {
        const val ENTRY_RECORD = ".dml_library_entry.json"
        private const val MAX_BYTES = GameModLibraryRepository.MAX_RECORD_BYTES
        private val CONTROL_KEYS = setOf("schemaVersion", "operationId", "installationId", "phase", "oldCatalogueHash",
            "newCatalogueHash", "oldPayloadHash", "newPayloadHash")
    }

    private val repository = GameModLibraryRepository(paths, gameId, metadataOperations)
    private val transactionDirectory get() = paths.getGameLibraryInstallTransactionDir(gameId)
    private val catalogueFile get() = paths.getGameLibraryCatalogueFile(gameId)
    private fun ownedFile(name: String) = File(requireCriticalDirectory(transactionDirectory), name)
    private fun record(name: String) = CriticalMetadataFile(ownedFile(name), metadataOperations)

    enum class Operation { INSTALL, UNINSTALL }

    private data class Intent(
        val operationId: String,
        val installationId: String,
        val phase: String,
        val oldCatalogueHash: String?,
        val newCatalogueHash: String,
        val oldPayloadHash: String?,
        val newPayloadHash: String?,
        val operation: Operation = Operation.INSTALL
    )

    private var ownsUnpublishedDirectory = false

    /** The caller explicitly prepares/persists the game and mods parents. Exclusive
     * creation prevents a duplicate request from overwriting another operation. */
    fun begin(): File {
        val directory = transactionDirectory
        val parent = requireCriticalDirectory(directory.parentFile!!)
        metadataOperations.syncDirectory(parent)
        Files.createDirectory(directory.toPath())
        metadataOperations.syncDirectory(parent)
        ownsUnpublishedDirectory = true
        return File(directory, "stage")
    }

    /** Only the same live owner may abandon work before publication was attempted.
     * A missing intent after restart is not permission to delete unexplained files. */
    fun discardUnpublishedStage() {
        check(ownsUnpublishedDirectory) { "Unpublished staging ownership is unavailable" }
        val directory = requireCriticalDirectory(transactionDirectory)
        val children = Files.newDirectoryStream(directory.toPath()).use { it.map { path -> path.toFile() }.toList() }
        check(children.all { it.name == "stage" }) { "Installation publication evidence must be preserved" }
        val stage = File(directory, "stage")
        if (present(stage)) {
            ManualLayoutInventory.capture(directory, stage)
            check(directoryOperations.deleteRecursively(stage) && !present(stage)) { "Unpublished staging cleanup is pending" }
        }
        metadataOperations.syncDirectory(directory)
        Files.delete(directory.toPath())
        metadataOperations.syncDirectory(directory.parentFile!!)
        ownsUnpublishedDirectory = false
    }

    fun publish(
        operationId: String,
        oldLibrary: GameModLibrary?,
        newLibrary: GameModLibrary,
        entry: GameLibraryEntry,
        signal: InstallCancellationSignal
    ) {
        ownsUnpublishedDirectory = false
        require(operationId.isNotBlank() && operationId.length <= 96)
        val oldText = CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES)
        check(oldText?.let(repository::decodeLibrary) == oldLibrary) { "Library changed before installation" }
        val newText = repository.encodeLibrary(newLibrary)
        check(repository.decodeLibrary(newText) == newLibrary)
        check(newLibrary.revision == Math.addExact(oldLibrary?.revision ?: 0L, 1L))
        val oldEntry = oldLibrary?.entries?.singleOrNull { it.installationId == entry.installationId }
        check(entry.payloadRevision == Math.addExact(oldEntry?.payloadRevision ?: 0L, 1L))
        check(newLibrary.entries == (oldLibrary?.entries.orEmpty().filterNot { it.installationId == entry.installationId } + entry))
        val finalDirectory = paths.getGameLibraryEntryDir(gameId, entry.installationId)
        val oldFingerprint = if (oldEntry != null) {
            verifyEntry(finalDirectory, oldEntry)
            fingerprint(finalDirectory, signal)
        } else {
            check(!present(finalDirectory)) { "An unrecorded installation already occupies this path" }
            null
        }
        val stage = ownedFile("stage")
        val entryFile = File(stage, ENTRY_RECORD)
        val inherited = CriticalMetadataFile(entryFile, metadataOperations).read(MAX_BYTES)
        check(inherited == null || (oldEntry != null && inherited == entryText(oldEntry))) {
            "Archive content conflicts with the installed metadata record"
        }
        CriticalMetadataFile(entryFile, metadataOperations).replaceDurably(entryText(entry), MAX_BYTES)
        persistTree(stage, signal)
        val newFingerprint = fingerprint(stage, signal)
        persistCatalogueSnapshots(oldText, newText)
        val intent = Intent(operationId, entry.installationId, "PREPARED", oldText?.let(::textHash),
            textHash(newText), oldFingerprint, newFingerprint)
        saveIntent(intent) // Durable intent precedes the first payload move.
        signal.throwIfCancellationRequested()
        if (oldEntry != null) directoryMove.moveToAbsent(finalDirectory, ownedFile("backup"))
        directoryMove.moveToAbsent(stage, finalDirectory)
        signal.throwIfCancellationRequested()
        repository.saveLibrary(newLibrary, oldLibrary?.revision)
        verifyPublished(intent, newLibrary)
        // No cancellation point after publication: the committed result wins.
        saveIntent(intent.copy(phase = "COMMITTED"))
    }

    /** Removal shares the same exclusive private transaction path as installation.
     * Quarantine is reversible until the durable catalogue-removal commit, not user Undo. */
    fun publishRemoval(
        operationId: String,
        oldLibrary: GameModLibrary,
        installationId: String,
        signal: InstallCancellationSignal,
        validateBeforeCommit: () -> Unit = {}
    ) {
        ownsUnpublishedDirectory = false
        require(operationId.isNotBlank() && operationId.length <= 96)
        val oldText = CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES)
            ?: error("Library disappeared before uninstall")
        check(repository.decodeLibrary(oldText) == oldLibrary) { "Library changed before uninstall" }
        val entry = oldLibrary.entries.single { it.installationId == installationId }
        val finalDirectory = paths.getGameLibraryEntryDir(gameId, installationId)
        verifyEntry(finalDirectory, entry)
        val oldHash = fingerprint(finalDirectory, signal)
        val newLibrary = oldLibrary.copy(revision = Math.addExact(oldLibrary.revision, 1L),
            entries = oldLibrary.entries.filterNot { it.installationId == installationId })
        val newText = repository.encodeLibrary(newLibrary)
        check(repository.decodeLibrary(newText) == newLibrary)
        persistCatalogueSnapshots(oldText, newText)
        val intent = Intent(operationId, installationId, "PREPARED", textHash(oldText), textHash(newText),
            oldHash, null, Operation.UNINSTALL)
        saveIntent(intent)
        validateBeforeCommit()
        signal.throwIfCancellationRequested()
        directoryMove.moveToAbsent(finalDirectory, ownedFile("quarantine"))
        validateBeforeCommit()
        signal.throwIfCancellationRequested()
        repository.saveLibrary(newLibrary, oldLibrary.revision)
        verifyPublished(intent, newLibrary)
        saveIntent(intent.copy(phase = "COMMITTED"))
    }

    /** Explicitly retire verified terminal private evidence. Failure blocks the next
     * mutation; it never rolls back a committed catalogue or changes a profile. */
    fun finish(expectedOperation: Operation? = null) {
        val intent = loadIntent()
        check(expectedOperation == null || intent.operation == expectedOperation) { "A different library operation needs recovery" }
        check(intent.phase == "COMMITTED" || intent.phase == "ROLLED_BACK") { "Recover the library operation first" }
        // Reconcile a committed-marker promotion that may have reported a failed barrier
        // before deleting retained old bytes. A reread alone is not persistence proof.
        metadataOperations.syncDirectory(transactionDirectory)
        if (intent.phase == "COMMITTED") verifyPublished(intent, readSnapshot("new.json", intent.newCatalogueHash))
        else recover()
        val expectedFiles = setOf("control.json", "old.json", "new.json", "stage", "backup", "quarantine")
        val children = Files.newDirectoryStream(transactionDirectory.toPath()).use { it.map { path -> path.toFile() }.toList() }
        check(children.all { it.name in expectedFiles }) { "Unknown library operation evidence must be preserved" }
        val payloads = if (intent.operation == Operation.UNINSTALL) {
            mapOf("stage" to null, "backup" to null, "quarantine" to intent.oldPayloadHash)
        } else mapOf("stage" to intent.newPayloadHash, "quarantine" to intent.newPayloadHash,
            "backup" to intent.oldPayloadHash)
        payloads.forEach { (name, hash) ->
            val directory = ownedFile(name)
            if (present(directory)) check(hash != null && fingerprint(directory) == hash) { "Private payload evidence changed" }
        }
        if (intent.oldCatalogueHash == null) check(!present(ownedFile("old.json")))
        else readSnapshot("old.json", intent.oldCatalogueHash)
        readSnapshot("new.json", intent.newCatalogueHash)
        // No recursive delete can follow a link: complete inventories were checked above,
        // and admission excludes writers of these private parents throughout cleanup.
        payloads.keys.forEach { name ->
            val directory = ownedFile(name)
            if (present(directory)) check(directoryOperations.deleteRecursively(directory) && !present(directory)) { "Library cleanup is pending" }
        }
        listOf("old.json", "new.json", "control.json").forEach { name -> Files.deleteIfExists(ownedFile(name).toPath()) }
        metadataOperations.syncDirectory(transactionDirectory)
        Files.delete(transactionDirectory.toPath())
        metadataOperations.syncDirectory(transactionDirectory.parentFile!!)
    }

    /** Strict publication gate. Catalogue presence alone is never availability. */
    fun loadPublishedLibrary(): GameModLibrary? {
        if (present(transactionDirectory)) {
            val intent = loadIntent()
            check(intent.phase == "COMMITTED") { "A library operation needs recovery before the library can be read" }
            metadataOperations.syncDirectory(transactionDirectory)
            val library = readSnapshot("new.json", intent.newCatalogueHash)
            verifyPublished(intent, library)
            return library
        }
        return repository.loadLibrary()?.also { library ->
            library.entries.forEach { verifyEntry(paths.getGameLibraryEntryDir(gameId, it.installationId), it) }
        }
    }

    /** Before commit restore the exact old private state, retaining displaced bytes
     * during uncertain rollback. Repeated recovery is idempotent. After commit preserve
     * the recorded install/removal outcome; never replay profile membership. */
    fun recover(expectedOperation: Operation? = null): Boolean {
        if (!present(transactionDirectory)) return false
        val intent = loadIntent() // Missing/corrupt/unknown evidence is blocked, not adopted.
        check(expectedOperation == null || intent.operation == expectedOperation) { "A different library operation needs recovery" }
        val newLibrary = readSnapshot("new.json", intent.newCatalogueHash)
        if (intent.phase == "COMMITTED") {
            metadataOperations.syncDirectory(transactionDirectory)
            verifyPublished(intent, newLibrary)
            return true
        }
        if (intent.operation == Operation.UNINSTALL) return recoverRemoval(intent, newLibrary)
        val oldText = readOldCatalogueText(intent)
        val oldLibrary = oldText?.let(repository::decodeLibrary)
        val newEntry = newLibrary.entries.single { it.installationId == intent.installationId }
        check(newLibrary.revision == Math.addExact(oldLibrary?.revision ?: 0L, 1L))
        val oldEntry = oldLibrary?.entries?.singleOrNull { it.installationId == intent.installationId }
        check((oldEntry == null) == (intent.oldPayloadHash == null))
        check(newEntry.payloadRevision == Math.addExact(oldEntry?.payloadRevision ?: 0L, 1L))
        check(newLibrary.entries == oldLibrary?.entries.orEmpty().filterNot { it.installationId == intent.installationId } + newEntry)
        val currentText = CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES)
        check(currentText == oldText || currentText == record("new.json").read(MAX_BYTES)) {
            "Catalogue no longer matches either recorded installation state"
        }
        val finalDirectory = paths.getGameLibraryEntryDir(gameId, intent.installationId)
        val stage = ownedFile("stage")
        val backup = ownedFile("backup")
        val quarantine = ownedFile("quarantine")
        val finalHash = if (present(finalDirectory)) fingerprint(finalDirectory) else null
        val stageHash = if (present(stage)) fingerprint(stage) else null
        val backupHash = if (present(backup)) fingerprint(backup) else null
        val quarantineHash = if (present(quarantine)) fingerprint(quarantine) else null
        check(stageHash == null || stageHash == intent.newPayloadHash)
        check(backupHash == null || backupHash == intent.oldPayloadHash)
        check(quarantineHash == null || quarantineHash == intent.newPayloadHash)
        check(listOfNotNull(stageHash, quarantineHash, finalHash?.takeIf { it == intent.newPayloadHash }).size == 1) {
            "New installation evidence is missing or duplicated"
        }
        if (oldEntry != null) {
            check(listOfNotNull(backupHash, finalHash?.takeIf { it == intent.oldPayloadHash }).size == 1) {
                "Previous installation evidence is missing or duplicated"
            }
        } else check(backupHash == null)
        check(finalHash == null || finalHash == intent.newPayloadHash || finalHash == intent.oldPayloadHash)
        if (finalHash == intent.newPayloadHash) directoryMove.moveToAbsent(finalDirectory, quarantine)
        if (oldEntry != null && !present(finalDirectory)) directoryMove.moveToAbsent(backup, finalDirectory)
        if (oldEntry != null) {
            verifyEntry(finalDirectory, oldEntry)
            check(fingerprint(finalDirectory) == intent.oldPayloadHash)
        } else check(!present(finalDirectory))
        restoreCatalogue(oldText)
        saveIntent(intent.copy(phase = "ROLLED_BACK"))
        return false
    }

    private fun recoverRemoval(intent: Intent, newLibrary: GameModLibrary): Boolean {
        val oldText = checkNotNull(readOldCatalogueText(intent))
        val oldLibrary = repository.decodeLibrary(oldText)
        val oldEntry = oldLibrary.entries.single { it.installationId == intent.installationId }
        check(newLibrary == oldLibrary.copy(revision = Math.addExact(oldLibrary.revision, 1L),
            entries = oldLibrary.entries.filterNot { it.installationId == intent.installationId }))
        val currentText = CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES)
        check(currentText == oldText || currentText == record("new.json").read(MAX_BYTES)) {
            "Catalogue no longer matches either recorded uninstall state"
        }
        check(!present(ownedFile("stage")) && !present(ownedFile("backup"))) { "Uninstall evidence is contradictory" }
        val finalDirectory = paths.getGameLibraryEntryDir(gameId, intent.installationId)
        val quarantine = ownedFile("quarantine")
        val finalHash = if (present(finalDirectory)) fingerprint(finalDirectory) else null
        val quarantineHash = if (present(quarantine)) fingerprint(quarantine) else null
        check(listOfNotNull(finalHash, quarantineHash).singleOrNull() == intent.oldPayloadHash) {
            "Previous installation is missing, changed or duplicated"
        }
        if (finalHash == null) directoryMove.moveToAbsent(quarantine, finalDirectory)
        verifyEntry(finalDirectory, oldEntry)
        check(fingerprint(finalDirectory) == intent.oldPayloadHash)
        restoreCatalogue(oldText)
        saveIntent(intent.copy(phase = "ROLLED_BACK"))
        return false
    }

    private fun persistCatalogueSnapshots(oldText: String?, newText: String) {
        oldText?.let { record("old.json").replaceDurably(it, MAX_BYTES) }
        record("new.json").replaceDurably(newText, MAX_BYTES)
    }

    private fun readOldCatalogueText(intent: Intent): String? = intent.oldCatalogueHash?.let { hash ->
        record("old.json").read(MAX_BYTES)?.also { check(textHash(it) == hash) }
            ?: error("Old catalogue evidence is missing")
    }

    private fun restoreCatalogue(oldText: String?) {
        if (oldText == null) {
            if (present(catalogueFile)) {
                metadataOperations.syncDirectory(catalogueFile.parentFile!!)
                Files.delete(catalogueFile.toPath())
                metadataOperations.syncDirectory(catalogueFile.parentFile!!)
            }
        } else CriticalMetadataFile(catalogueFile, metadataOperations).replaceDurably(oldText, MAX_BYTES)
        check(CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES) == oldText)
    }

    private fun verifyPublished(intent: Intent, library: GameModLibrary) {
        check(CriticalMetadataFile(catalogueFile, metadataOperations).read(MAX_BYTES)?.let(::textHash) == intent.newCatalogueHash)
        if (intent.operation == Operation.UNINSTALL) {
            check(library.entries.none { it.installationId == intent.installationId })
            check(!present(paths.getGameLibraryEntryDir(gameId, intent.installationId))) { "Uninstalled identity has unexpected payload" }
            return // Quarantine may be partially cleaned after commit; it cannot restore availability.
        }
        val entry = library.entries.single { it.installationId == intent.installationId }
        val finalDirectory = paths.getGameLibraryEntryDir(gameId, entry.installationId)
        verifyEntry(finalDirectory, entry)
        check(fingerprint(finalDirectory) == intent.newPayloadHash) { "Installed payload changed" }
    }

    private fun verifyEntry(directory: File, entry: GameLibraryEntry) {
        requireCriticalDirectory(directory)
        check(CriticalMetadataFile(File(directory, ENTRY_RECORD), metadataOperations).read(MAX_BYTES) == entryText(entry)) {
            "Payload metadata does not match the library entry"
        }
    }

    private fun entryText(entry: GameLibraryEntry) = repository.encodeLibrary(GameModLibrary(gameId, entry.payloadRevision, listOf(entry)))

    private fun readSnapshot(name: String, hash: String): GameModLibrary {
        val text = record(name).read(MAX_BYTES) ?: error("Installation catalogue evidence is missing")
        check(textHash(text) == hash) { "Installation catalogue evidence changed" }
        return repository.decodeLibrary(text)
    }

    private fun saveIntent(intent: Intent) {
        val json = JSONObject().apply {
            put("schemaVersion", 2); put("operation", intent.operation.name)
            put("operationId", intent.operationId); put("installationId", intent.installationId)
            put("phase", intent.phase); put("oldCatalogueHash", intent.oldCatalogueHash ?: JSONObject.NULL)
            put("newCatalogueHash", intent.newCatalogueHash); put("oldPayloadHash", intent.oldPayloadHash ?: JSONObject.NULL)
            put("newPayloadHash", intent.newPayloadHash ?: JSONObject.NULL)
        }
        record("control.json").replaceDurably(json.toString(), MAX_BYTES)
    }

    private fun loadIntent(): Intent {
        val json = ModLibraryJsonReader.read(record("control.json").read(MAX_BYTES) ?: error("Installation intent is missing"))
        val version = json.requiredInt("schemaVersion")
        check(version == 1 || version == 2) { "Unsupported shared transaction version" }
        check(json.keys().asSequence().toSet() == if (version == 1) CONTROL_KEYS else CONTROL_KEYS + "operation")
        fun string(key: String) = json.requiredString(key)
        fun hash(key: String): String? = if (json.isNull(key)) null else string(key).also {
            check(it.matches(Regex("[0-9a-f]{64}")))
        }
        val operation = if (version == 1) Operation.INSTALL else Operation.valueOf(string("operation"))
        val intent = Intent(string("operationId"), string("installationId"), string("phase"), hash("oldCatalogueHash"),
            checkNotNull(hash("newCatalogueHash")), hash("oldPayloadHash"), hash("newPayloadHash"), operation)
        check((operation == Operation.INSTALL) == (intent.newPayloadHash != null))
        if (operation == Operation.UNINSTALL) check(intent.oldCatalogueHash != null && intent.oldPayloadHash != null)
        check(intent.operationId.isNotBlank() && intent.operationId.length <= 96)
        check(intent.phase in setOf("PREPARED", "COMMITTED", "ROLLED_BACK"))
        paths.getGameLibraryEntryDir(gameId, intent.installationId)
        return intent
    }

    private fun persistTree(directory: File, signal: InstallCancellationSignal) {
        val inventory = ManualLayoutInventory.capture(directory.parentFile!!, directory, signal)
        inventory.entries.filterNot { it.directory }.forEach { entry ->
            signal.throwIfCancellationRequested()
            FileChannel.open(File(directory, entry.path).toPath(), WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        }
        inventory.entries.filter { it.directory }.sortedByDescending { it.path.length }.forEach {
            metadataOperations.syncDirectory(File(directory, it.path))
        }
        metadataOperations.syncDirectory(directory)
    }

    private fun fingerprint(directory: File, signal: InstallCancellationSignal = InstallCancellationSignal.NONE): String {
        val inventory = ManualLayoutInventory.capture(directory.parentFile!!, directory, signal)
        val digest = MessageDigest.getInstance("SHA-256")
        inventory.entries.forEach { entry ->
            signal.throwIfCancellationRequested()
            digest.update((if (entry.directory) "D" else "F").toByteArray())
            digest.update(entry.path.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            digest.update(entry.sizeBytes.toString().toByteArray()); digest.update(0.toByte())
            if (!entry.directory) Files.newInputStream(File(directory, entry.path).toPath(), NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = entry.sizeBytes
                while (remaining > 0) {
                    signal.throwIfCancellationRequested()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "Installed file changed while hashing" }
                    digest.update(buffer, 0, count); remaining -= count
                }
                check(input.read() == -1) { "Installed file grew while hashing" }
            }
        }
        inventory.requireCurrent(signal)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun textHash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun present(file: File): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile))
        check(file.absoluteFile == file.canonicalFile)
        true
    } catch (_: NoSuchFileException) { false }
}
