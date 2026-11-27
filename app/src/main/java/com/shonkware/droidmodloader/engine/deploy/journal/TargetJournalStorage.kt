package com.shonkware.droidmodloader.engine.deploy.journal

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceRead
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceState
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import java.text.Normalizer
import java.util.Locale
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Caller-authorized app-managed backup locations, not paths adopted from journal text. */
internal data class TargetBackupScope(val managedBase: File, val dataDirectories: List<File>, val rootDirectories: List<File>)

/** Implementation of the existing journal owner's opt-in destination API.
 * Requires exclusive session admission across private parents and captured context,
 * off the UI thread. No physical alias probe, target payload access, adoption,
 * completion publisher, replay, cleanup or active legacy/factory switch lives here. */
internal class TargetJournalStorage internal constructor(
    private val paths: ProfileStoragePaths,
    private val destination: TargetDestinationIdentity,
    private val operations: CriticalMetadataFileOperations,
    private val availableBytes: (File) -> Long?,
    backupScope: TargetBackupScope?
) {
    private val backups = backupScope?.copy(dataDirectories = backupScope.dataDirectories.toList(), rootDirectories = backupScope.rootDirectories.toList())
    private val ownerDirectory get() = paths.getDeploymentTargetDirectory(destination.mode, destination.rootDirectory)
    private val journalFile get() = paths.getDeploymentTargetJournalFile(destination.mode, destination.rootDirectory)
    private fun bindingFile(role: GameTargetType) = paths.getDeploymentRoleBindingFile(destination.mode, destination.directory(role))
    private fun generationDirectory(id: String) = paths.getDeploymentGenerationDirectory(destination.mode, destination.rootDirectory, id)
    private fun generationFile(id: String, kind: TargetMetadataKind) = paths.getDeploymentGenerationFile(destination.mode, destination.rootDirectory, id, kind)
    private fun metadata(file: File) = CriticalMetadataFile(file, operations)
    private val roles = listOf(GameTargetType.DATA, GameTargetType.GAME_ROOT)

    init {
        destination.validate()
        val internal = paths.getManagedInternalDirectory().toPath()
        for (directory in listOf(destination.dataDirectory, destination.rootDirectory)) {
            val target = File(directory).toPath()
            require(!target.startsWith(internal) && !internal.startsWith(target)) { "Destination overlaps managed internal storage" }
        }
        backups?.let { scope ->
            requireCriticalDirectory(scope.managedBase)
            require(!scope.managedBase.toPath().startsWith(internal) && !internal.startsWith(scope.managedBase.toPath())) {
                "Backup base overlaps internal metadata/source storage"
            }
            val all = scope.dataDirectories + scope.rootDirectories
            require(all.size <= DeploymentEvidenceRead.MAX_RECORDS && all.distinct().size == all.size)
            val read = DeploymentEvidenceRead()
            for (directory in all) {
                read.ownedPath(scope.managedBase, directory)
                for (target in listOf(destination.dataDirectory, destination.rootDirectory)) {
                    val path = File(target).toPath()
                    require(!directory.toPath().startsWith(path) && !path.startsWith(directory.toPath())) { "Backup and destination overlap" }
                }
            }
        }
    }

    private data class Raw(val journal: String?, val data: String?, val root: String?)
    private fun raw(): Raw = Raw(metadata(journalFile).read(TargetJournalCodec.JOURNAL_BYTES),
        metadata(bindingFile(GameTargetType.DATA)).read(TargetJournalCodec.BINDING_BYTES),
        metadata(bindingFile(GameTargetType.GAME_ROOT)).read(TargetJournalCodec.BINDING_BYTES))

    private fun bindingText(role: GameTargetType): String = JSONObject().put("schemaVersion", 1)
        .put("destination", TargetJournalCodec.destination(destination)).put("role", role.name)
        .put("directory", destination.directory(role))
        .put("journalPath", journalFile.relativeTo(paths.getManagedInternalDirectory()).invariantSeparatorsPath).toString().also {
            require(TargetJournalCodec.bytes(it).size <= TargetJournalCodec.BINDING_BYTES) { "Binding exceeds byte limit" }
        }

    private fun validateBinding(text: String, role: GameTargetType) {
        val json = ModLibraryJsonReader.read(text)
        TargetJournalCodec.keys(json, setOf("schemaVersion", "destination", "role", "directory", "journalPath"))
        require(json.requiredInt("schemaVersion") == 1)
        check(TargetJournalCodec.destination(json.getJSONObject("destination")) == destination &&
            json.requiredString("role") == role.name && json.requiredString("directory") == destination.directory(role) &&
            json.requiredString("journalPath") == journalFile.relativeTo(paths.getManagedInternalDirectory()).invariantSeparatorsPath) {
            "Foreign or conflicting destination binding"
        }
    }

    private fun children(directory: File): List<File> {
        try { requireCriticalDirectory(directory) } catch (_: NoSuchFileException) { return emptyList() }
        return Files.newDirectoryStream(directory.toPath()).use { stream ->
            val result = mutableListOf<File>()
            for (entry in stream) {
                check(result.size < 3) { "Unknown or excess target metadata evidence" }
                val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(!attributes.isSymbolicLink && (attributes.isRegularFile || attributes.isDirectory))
                result.add(entry.toFile())
            }
            result
        }
    }

    private fun validateLayout(record: TargetJournalEnvelope?) {
        val ownerChildren = children(ownerDirectory)
        if (record == null) {
            check(ownerChildren.isEmpty()) { "Missing journal beside retained evidence is not fresh registration" }
            return
        }
        check(ownerChildren.all { it == journalFile || it.name == "generations" }) { "Unknown journal siblings must be preserved" }
        val generations = paths.getDeploymentGenerationsDirectory(destination.mode, destination.rootDirectory)
        val allowed = listOfNotNull(record.completed?.generationId, record.pending?.candidate?.generationId).map(::generationDirectory)
        check(children(generations).all { it in allowed && it.isDirectory }) { "Unreferenced generation evidence must be preserved" }
        record.completed?.let { requireCriticalDirectory(generationDirectory(it.generationId)) }
    }

    private fun snapshot(): Pair<Raw, TargetAssociation> {
        val before = raw()
        before.data?.let { validateBinding(it, GameTargetType.DATA) }
        before.root?.let { validateBinding(it, GameTargetType.GAME_ROOT) }
        val record = before.journal?.let(TargetJournalCodec::decode)
        if (record == null) check(before.data == null && before.root == null) { "Required destination journal is missing" }
        else {
            check(record.destination == destination) { "Destination pairing/game changed; preserve existing history" }
            if (record.pending?.kind != TargetMetadataOperation.REGISTRATION) check(before.data != null && before.root != null) {
                "Required destination binding is missing; no implicit repair"
            }
        }
        validateLayout(record)
        check(raw() == before) { "Destination metadata changed while reading" }
        val state = when {
            record == null -> TargetAssociationState.MISSING
            record.pending?.kind == TargetMetadataOperation.REGISTRATION -> TargetAssociationState.REGISTRATION_PENDING
            record.pending != null -> TargetAssociationState.PREPARATION_PENDING
            else -> TargetAssociationState.BOUND
        }
        return before to TargetAssociation(state, record)
    }

    /** Association/record facts only. Use readCompletedMetadata for hash-pinned metadata. */
    fun inspectAssociation(): TargetAssociation = snapshot().second

    private fun confirm(raw: Raw) {
        listOf(journalFile.parentFile!!, bindingFile(GameTargetType.DATA).parentFile!!,
            bindingFile(GameTargetType.GAME_ROOT).parentFile!!).distinct().forEach { operations.syncDirectory(requireCriticalDirectory(it)) }
        check(this.raw() == raw) { "Destination evidence changed during persistence confirmation" }
    }

    private fun space(bytes: Long) {
        val parent = requireCriticalDirectory(ownerDirectory)
        val available = checkNotNull(availableBytes(parent)) { "Target metadata capacity is unknown" }
        StorageCapacity.requireCopySpace(parent, bytes) { available }
    }

    private fun writeEnvelope(expected: Raw, replacement: TargetJournalEnvelope, contextStillCurrent: () -> Boolean) {
        check(contextStillCurrent() && raw() == expected) { "Stale destination metadata or operation context" }
        val previous = expected.journal?.let(TargetJournalCodec::decode)
        check(replacement.destination == destination && replacement.completed == previous?.completed) { "This foundation cannot publish completion" }
        check(replacement.revision == Math.addExact(previous?.revision ?: 0L, 1L))
        metadata(journalFile).replaceDurably(TargetJournalCodec.encode(replacement), TargetJournalCodec.JOURNAL_BYTES)
    }

    /** Parents are explicitly prepared by the admitted caller. Missing records never
     * grant file ownership. Only exact recorded registration may fill missing bindings. */
    fun registerAssociation(operationId: String, contextStillCurrent: () -> Boolean): TargetAssociation {
        TargetJournalCodec.identifier(operationId)
        check(contextStillCurrent()) { "Operation context changed" }
        var (observed, association) = snapshot()
        if (association.state == TargetAssociationState.BOUND) {
            confirm(observed)
            check(contextStillCurrent()) { "Operation context changed" }
            return association
        }
        check(association.state == TargetAssociationState.MISSING ||
            (association.state == TargetAssociationState.REGISTRATION_PENDING && association.journal?.pending?.operationId == operationId)) {
            "A different destination operation is pending"
        }
        roles.forEach { requireCriticalDirectory(bindingFile(it).parentFile!!) }
        val initial = TargetJournalEnvelope(destination, 1, null, TargetPendingMetadata(operationId, TargetMetadataOperation.REGISTRATION, null))
        val initialBytes = TargetJournalCodec.bytes(TargetJournalCodec.encode(initial)).size.toLong()
        val bindingBytes = roles.sumOf { TargetJournalCodec.bytes(bindingText(it)).size.toLong() }
        space(Math.addExact(Math.multiplyExact(initialBytes, 4L), Math.multiplyExact(bindingBytes, 2L)))
        if (association.state == TargetAssociationState.MISSING) {
            writeEnvelope(observed, initial, contextStillCurrent)
            val fresh = snapshot(); observed = fresh.first; association = fresh.second
        }
        confirm(observed)
        for (role in roles) {
            check(contextStillCurrent() && raw() == observed) { "Registration context/evidence changed" }
            val current = if (role == GameTargetType.DATA) observed.data else observed.root
            if (current == null) metadata(bindingFile(role)).replaceDurably(bindingText(role), TargetJournalCodec.BINDING_BYTES)
            val fresh = snapshot(); observed = fresh.first; association = fresh.second
        }
        confirm(observed)
        val registered = checkNotNull(association.journal).copy(revision = 2, pending = null)
        writeEnvelope(observed, registered, contextStillCurrent)
        val completed = snapshot()
        confirm(completed.first)
        return completed.second
    }

    private fun backupDirectories(kind: TargetMetadataKind): List<File> = when (kind) {
        TargetMetadataKind.DATA_MANIFEST -> backups?.dataDirectories.orEmpty()
        TargetMetadataKind.ROOT_MANIFEST -> backups?.rootDirectories.orEmpty()
        TargetMetadataKind.DATA_INVENTORY -> emptyList()
    }

    private data class MetadataFacts(val entryCount: Int, val managedDataPaths: Set<String> = emptySet())

    private fun validateMetadata(
        text: String, generationId: String, kind: TargetMetadataKind, requiredDataPaths: Set<String> = emptySet()
    ): MetadataFacts {
        // Both callers validate strict UTF-8 byte bounds before materializing JSON.
        require(text.length <= kind.maximumBytes) { "Generation metadata exceeds bound" }
        val json = ModLibraryJsonReader.read(text)
        val common = setOf("schemaVersion", "destinationDigest", "generationId", "kind")
        TargetJournalCodec.keys(json, common + if (kind == TargetMetadataKind.DATA_INVENTORY) setOf("paths") else setOf("entries", "backupHashes"))
        require(json.requiredInt("schemaVersion") == 1 && json.requiredString("destinationDigest") == destination.digest() &&
            json.requiredString("generationId") == generationId && json.requiredString("kind") == kind.name) { "Foreign generation metadata" }
        val read = DeploymentEvidenceRead()
        if (kind == TargetMetadataKind.DATA_INVENTORY) {
            val array = json.getJSONArray("paths")
            read.rows(array, kind.maximumEntries)
            val validatePath = read.paths()
            val missing = requiredDataPaths.toMutableSet()
            for (index in 0 until array.length()) {
                val path = array.get(index)
                require(path is String) { "Inventory path is not a string" }
                validatePath(path)
                missing.remove(ModFileIndexService.normalizedSourcePath(path))
            }
            require(missing.isEmpty()) { "Managed Data output missing from recorded inventory" }
            return MetadataFacts(array.length())
        }
        val entries = json.getJSONArray("entries")
        val decoded = DeploymentManifestRepository(generationFile(generationId, kind))
            .decodeStagedEvidence(read, entries.toString(), backupDirectories(kind))
        require(decoded.state == DeploymentEvidenceState.VALID || decoded.state == DeploymentEvidenceState.VALID_EMPTY) { "Incomplete manifest evidence" }
        val records = checkNotNull(decoded.value)
        if (kind == TargetMetadataKind.ROOT_MANIFEST) {
            val dataName = Normalizer.normalize(File(destination.dataDirectory).name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            require(records.none { ModFileIndexService.normalizedSourcePath(it.normalizedPath).substringBefore('/') == dataName }) {
                "Root manifest overlaps the Data role"
            }
        }
        val originals = records.filter { it.backupFilePath != null }.map { it.normalizedPath }.toSet()
        val backupHashes = json.getJSONArray("backupHashes")
        read.rows(backupHashes, kind.maximumEntries)
        val seen = hashSetOf<String>()
        for (index in 0 until backupHashes.length()) {
            val row = backupHashes.getJSONObject(index)
            TargetJournalCodec.keys(row, setOf("normalizedPath", "hash"))
            require(seen.add(row.requiredString("normalizedPath")))
            TargetJournalCodec.digestText(row.requiredString("hash"))
        }
        require(seen == originals) { "Missing or foreign expected backup hash" }
        return MetadataFacts(entries.length(), if (kind == TargetMetadataKind.DATA_MANIFEST)
            records.map { ModFileIndexService.normalizedSourcePath(it.normalizedPath) }.toSet() else emptySet())
    }

    private fun reference(generationId: String, profileId: String, sourceRevisionDigest: String, texts: TargetMetadataTexts): TargetGenerationReference {
        TargetJournalCodec.identifier(generationId); TargetJournalCodec.identifier(profileId); TargetJournalCodec.digestText(sourceRevisionDigest)
        var managedDataPaths = emptySet<String>()
        fun pin(kind: TargetMetadataKind): TargetMetadataPin {
            val text = texts.text(kind)
            val bytes = TargetJournalCodec.bytes(text, kind.maximumBytes)
            val facts = validateMetadata(text, generationId, kind, managedDataPaths)
            if (kind == TargetMetadataKind.DATA_MANIFEST) managedDataPaths = facts.managedDataPaths
            return TargetMetadataPin(TargetJournalCodec.hash(bytes), bytes.size.toLong(), facts.entryCount)
        }
        return TargetGenerationReference(generationId, profileId, sourceRevisionDigest,
            pin(TargetMetadataKind.DATA_MANIFEST), pin(TargetMetadataKind.ROOT_MANIFEST), pin(TargetMetadataKind.DATA_INVENTORY))
    }

    private fun verifyMetadata(reference: TargetGenerationReference) {
        var managedDataPaths = emptySet<String>()
        for (kind in TargetMetadataKind.entries) {
            val text = checkNotNull(metadata(generationFile(reference.generationId, kind)).read(kind.maximumBytes)) { "Required generation metadata missing" }
            val bytes = TargetJournalCodec.bytes(text, kind.maximumBytes)
            val pin = reference.pin(kind)
            check(bytes.size.toLong() == pin.sizeBytes && TargetJournalCodec.hash(bytes) == pin.hash) { "Generation metadata pin changed" }
            val facts = validateMetadata(text, reference.generationId, kind, managedDataPaths)
            check(facts.entryCount == pin.entryCount) { "Generation metadata entry count changed" }
            if (kind == TargetMetadataKind.DATA_MANIFEST) managedDataPaths = facts.managedDataPaths
        }
    }

    /** On-demand metadata verification. Does not inspect game files or backup payloads. */
    fun readCompletedMetadata(): TargetRecordedMetadata? {
        val (observed, association) = snapshot()
        val completed = association.journal?.completed ?: return null
        verifyMetadata(completed)
        verifyMetadata(completed)
        check(raw() == observed) { "Journal changed while verifying completed metadata" }
        return TargetRecordedMetadata(completed)
    }

    /** Persists only PREPARATION and its immutable candidate files. The previous completed
     * reference never changes; there is intentionally no STARTED/COMPLETED writer here. */
    fun prepareMetadata(
        operationId: String,
        expectedRevision: Long,
        generationId: String,
        profileId: String,
        sourceRevisionDigest: String,
        texts: TargetMetadataTexts,
        contextStillCurrent: () -> Boolean
    ): TargetGenerationReference {
        TargetJournalCodec.identifier(operationId)
        check(contextStillCurrent()) { "Operation context changed" }
        var (observed, association) = snapshot()
        var record = checkNotNull(association.journal) { "Register the destination first" }
        check(record.revision == expectedRevision || (expectedRevision >= 1L && expectedRevision < Long.MAX_VALUE &&
            record.revision == expectedRevision + 1 && record.pending?.kind == TargetMetadataOperation.PREPARATION &&
            record.pending?.operationId == operationId)) { "Destination revision changed" }
        check(association.state != TargetAssociationState.REGISTRATION_PENDING)
        val candidate = reference(generationId, profileId, sourceRevisionDigest, texts)
        check(candidate.generationId != record.completed?.generationId) { "Completed generation is immutable" }
        val pending = TargetPendingMetadata(operationId, TargetMetadataOperation.PREPARATION, candidate)
        check(record.pending == null || record.pending == pending) { "A different metadata preparation is pending" }
        readCompletedMetadata()
        check(raw() == observed) { "Destination changed before metadata preparation" }
        val next = if (record.pending == null) record.copy(revision = Math.addExact(record.revision, 1L), pending = pending) else record
        check(children(generationDirectory(generationId)).all { it.name in TargetMetadataKind.entries.map { kind -> kind.fileName } && it.isFile }) {
            "Unknown candidate evidence must be preserved"
        }
        var missingBytes = 0L
        for (kind in TargetMetadataKind.entries) {
            val previous = metadata(generationFile(generationId, kind)).read(kind.maximumBytes)
            check(previous == null || previous == texts.text(kind)) { "Candidate metadata changed; no replacement allowed" }
            if (previous == null) missingBytes = Math.addExact(missingBytes, candidate.pin(kind).sizeBytes)
        }
        val journalBytes = if (record.pending == null) TargetJournalCodec.bytes(TargetJournalCodec.encode(next)).size.toLong() * 2 else 0L
        val requiredBytes = Math.addExact(missingBytes, journalBytes)
        if (requiredBytes > 0L) space(requiredBytes)
        if (record.pending == null) {
            writeEnvelope(observed, next, contextStillCurrent)
            val fresh = snapshot(); observed = fresh.first; association = fresh.second
            record = checkNotNull(association.journal)
        }
        confirm(observed)
        val generations = paths.getDeploymentGenerationsDirectory(destination.mode, destination.rootDirectory)
        for (directory in listOf(generations, generationDirectory(generationId))) {
            check(contextStillCurrent() && raw() == observed)
            val parent = requireCriticalDirectory(directory.parentFile!!)
            operations.syncDirectory(parent)
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
            requireCriticalDirectory(directory)
            operations.syncDirectory(parent)
        }
        check(children(generationDirectory(generationId)).all { it.name in TargetMetadataKind.entries.map { kind -> kind.fileName } && it.isFile }) {
            "Unknown candidate evidence must be preserved"
        }
        for (kind in TargetMetadataKind.entries) {
            check(contextStillCurrent() && raw() == observed)
            val file = generationFile(generationId, kind)
            val previous = metadata(file).read(kind.maximumBytes)
            if (previous == null) metadata(file).replaceDurably(texts.text(kind), kind.maximumBytes)
            else check(previous == texts.text(kind)) { "Candidate metadata changed; no replacement allowed" }
            operations.syncDirectory(requireCriticalDirectory(file.parentFile!!))
        }
        verifyMetadata(candidate)
        check(contextStillCurrent() && raw() == observed && record.pending == pending)
        return candidate
    }
}
