package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry
import com.shonkware.droidmodloader.engine.util.PathUtils
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.LibraryInstallReplacement
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal data class MigrationExecutionResult(val gameId: String, val cleanupPending: Boolean, val cleanupFailure: Exception? = null)

/** Staged only. Caller holds retained-session admission for this entire synchronous
 * operation off the UI thread, and validates the captured generation (not just IDs).
 * No startup caller, archive/physical-target access, copy fallback or rollback. */
internal class GameLibraryMigrationExecutor(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val inventory: GameLibraryMigrationInventory,
    private val sourceReader: ModLibraryService,
    private val contextStillCurrent: () -> Boolean,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations,
    private val directoryMove: CriticalDirectoryMove = CriticalDirectoryMove(),
    private val availableBytes: (File) -> Long? = AndroidStorageCapacityProbe::availableBytes,
    private val deleteOwnedPath: (File) -> Unit = { Files.delete(it.toPath()) }
) {
    private val verifier = MigrationTreeVerifier()
    private val repository = GameModLibraryRepository(paths, gameId, operations)
    private val store = GameLibraryMigrationExecutionStore(paths, gameId, inventory, operations)
    private val maximumBytes = GameLibraryMigrationPlanRepository.MAX_BYTES

    fun execute(signal: InstallCancellationSignal = InstallCancellationSignal.NONE): MigrationExecutionResult {
        val snapshot = checkNotNull(GameLibraryMigrationAccess.read(paths, gameId, operations)) { "Prepare and save the migration mapping first" }
        val completed = snapshot.execution?.takeIf { it.phase == MigrationExecutionPhase.COMPLETE }
        if (completed != null) { store.confirmPersistence(completed); return cleanup(completed, signal) }
        requireContext(signal)
        val entryRecords = repository.decodeLibrary(snapshot.plan.candidateCatalogue).entries.associate { it.installationId to repository.encodeEntryRecord(it) }
        var current = snapshot.execution ?: prepare(snapshot.plan, signal, entryRecords).also(store::begin)
        if (snapshot.execution != null && current.phase == MigrationExecutionPhase.PREPARED) store.ensurePreparedGuard(current)
        store.confirmPersistence(current)
        requireProtectedEvidence(current, signal)
        requirePublicationState(current)
        fun persist(next: MigrationExecutionRecord) { requireContext(signal); store.save(current, next); current = next }
        if (current.phase == MigrationExecutionPhase.PREPARED) {
            prepareInternalDirectory(File(paths.getGameLibraryCatalogueFile(gameId).parentFile, "mods"))
            current.plan.profiles.forEach { prepareInternalDirectory(paths.getProfileMembershipFile(gameId, it.profileId).parentFile!!) }
            persist(current.copy(phase = MigrationExecutionPhase.MOVING))
        }
        if (current.phase == MigrationExecutionPhase.MOVING) {
            for (index in current.entries.indices) {
                val installation = current.plan.installations[index]
                val source = File(installation.sourcePath)
                val destination = File(installation.destinationPath)
                val entryText = entryRecords.getValue(installation.installationId)
                requireContext(signal)
                val oldPresent = attributesOrNull(source) != null
                val newPresent = attributesOrNull(destination) != null
                check(oldPresent != newPresent) { "Ambiguous old/new migration payload locations; preserve both paths" }
                var progress = current.entries[index]
                fun saveProgress(next: MigrationEntryProgress) {
                    persist(current.copy(entries = current.entries.mapIndexed { position, value -> if (position == index) next else value }))
                    progress = next
                }
                if (oldPresent) {
                    check(!progress.moveVerified && !progress.metadataIntended) { "Verified migration payload returned to its old path" }
                    verifier.requireMatching(source, installation.tree, signal)
                    if (!progress.moveIntended) saveProgress(progress.copy(moveIntended = true))
                    requireContext(signal)
                    directoryMove.moveToAbsent(source, destination)
                } else check(progress.moveIntended) { "New payload appeared without recorded move intent" }
                requirePayload(installation, progress, entryText, signal)
                operations.syncDirectory(requireCriticalDirectory(source.parentFile!!))
                operations.syncDirectory(requireCriticalDirectory(destination.parentFile!!))
                if (!progress.moveVerified) saveProgress(progress.copy(moveVerified = true))
                val sidecar = CriticalMetadataFile(File(destination, LibraryInstallReplacement.ENTRY_RECORD), operations)
                if (!progress.metadataIntended) {
                    check(sidecar.read(maximumBytes) == null) { "Unexpected migration entry metadata; preserve evidence" }
                    saveProgress(progress.copy(metadataIntended = true))
                }
                requireContext(signal)
                val metadata = sidecar.read(maximumBytes)
                if (metadata == null) {
                    check(!progress.metadataVerified) { "Verified migration entry metadata disappeared" }
                    sidecar.replaceDurably(entryText, maximumBytes)
                } else check(metadata == entryText) { "Migration entry metadata changed; preserve evidence" }
                confirmRecord(File(destination, LibraryInstallReplacement.ENTRY_RECORD), entryText)
                check(verifier.captureWithEntryRecord(destination, entryText, signal) == installation.tree) { "Migrated payload changed" }
                if (!progress.metadataVerified) saveProgress(progress.copy(metadataVerified = true))
            }
            requireProtectedEvidence(current, signal)
            requireAllPayloads(current, signal, entryRecords)
            requirePublicationState(current)
            persist(current.copy(phase = MigrationExecutionPhase.PUBLISHING))
        }
        if (current.phase == MigrationExecutionPhase.PUBLISHING) {
            requireProtectedEvidence(current, signal)
            requireAllPayloads(current, signal, entryRecords)
            val access = MigrationVerificationAccess.capture(paths, gameId, current)
            val library = repository.decodeLibrary(current.plan.candidateCatalogue)
            val existing = repository.loadLibrary(access)
            if (existing == null) repository.saveLibrary(library, null, access)
            else check(existing == library) { "Foreign catalogue occupies migration publication" }
            confirmRecord(paths.getGameLibraryCatalogueFile(gameId), repository.encodeLibrary(library))
            for (profile in current.plan.profiles) {
                requireContext(signal)
                val membership = repository.decodeProfile(profile.profileId, profile.candidateMembership)
                val saved = repository.loadProfile(profile.profileId, access)
                if (saved == null) repository.saveProfile(profile.profileId, membership, null, access)
                else check(saved == membership) { "Foreign profile choices occupy migration publication" }
                confirmRecord(paths.getProfileMembershipFile(gameId, profile.profileId), repository.encodeProfile(membership))
            }
            persist(current.copy(phase = MigrationExecutionPhase.RELOADING))
        }
        if (current.phase == MigrationExecutionPhase.RELOADING) {
            requireProtectedEvidence(current, signal)
            requireAllPayloads(current, signal, entryRecords)
            requirePublicationState(current)
            // Reload all profiles after every process restart: a previous process's
            // derived objects are not restored by a persisted completion flag.
            for (profile in current.plan.profiles) {
                requireContext(signal)
                val access = MigrationVerificationAccess.capture(paths, gameId, current)
                val view = sourceReader.stagedEffectiveSources(paths, gameId, profile.profileId,
                    contextStillCurrent, signal, access)
                check(view.gameId == gameId && view.profileId == profile.profileId && view.libraryRevision == 1L && view.membershipRevision == 1L)
                if (profile.profileId !in current.reloadedProfileIds) {
                    persist(current.copy(reloadedProfileIds = current.reloadedProfileIds + profile.profileId))
                }
            }
            requireProtectedEvidence(current, signal)
            requireAllPayloads(current, signal, entryRecords)
            requirePublicationState(current)
            persist(current.copy(phase = MigrationExecutionPhase.COMPLETE))
        }
        store.confirmPersistence(current)
        return cleanup(current, signal)
    }

    private fun prepare(plan: GameLibraryMigrationPlan, signal: InstallCancellationSignal, entryRecords: Map<String, String>): MigrationExecutionRecord {
        inventory.requireMatching(plan, signal)
        val knownIndexes = mutableListOf<MigrationCleanupCandidate>()
        for (installation in plan.installations) {
            requireContext(signal)
            val source = File(installation.sourcePath)
            check(CriticalMetadataFile(File(source, LibraryInstallReplacement.ENTRY_RECORD), operations).read(maximumBytes) == null) {
                "Legacy payload already contains a shared entry record; preserve evidence"
            }
            check(attributesOrNull(File(installation.destinationPath)) == null)
            check(Files.getFileStore(source.toPath()) == Files.getFileStore(paths.getManagedInternalDirectory().toPath())) { "Migration requires one managed volume" }
            ModFileIndexService.requireMigrationSidecarCapacity(installation.tree.entryCount)
            val indexedFiles = ModFileIndexService.indexVerifiedFiles(source, signal) // Includes disabled installations.
            val profile = plan.profiles.single { it.profileId == installation.profileId }
            val state = File(inventory.externalFilesDir, "state/profiles/${paths.getLegacyProfileInternalDir(profile.profileId).name}")
            val indexFile = ModFileIndexRepository(File(state, "mod_file_indexes")).getIndexFileForModId(installation.legacyModId)
            val indexText = try { CriticalMetadataFile(indexFile, operations).read(maximumBytes) } catch (_: Exception) { null }
            val legacyName = migrationArray(checkNotNull(profile.legacyState)).objects().single { it.requiredString("id") == installation.legacyModId }.requiredString("name")
            if (indexText != null && isKnownLegacyIndex(indexText, installation, legacyName, indexedFiles)) {
                cleanupCandidate(indexFile, MigrationCleanupKind.FILE, indexText)?.let(knownIndexes::add)
            }
            ModFileIndexService.persistVerifiedFiles(source, signal, operations)
            verifier.requireMatching(source, installation.tree, signal)
            operations.syncDirectory(requireCriticalDirectory(source.parentFile!!))
        }
        for (profile in plan.profiles) ProfileOverwriteRepository(paths, gameId, profile.profileId, operations).loadVerifiedSource(signal)
        val remainders = plan.profiles.map { profile ->
            val root = paths.getLegacyProfileInternalDir(profile.profileId)
            MigrationProfileRemainder(profile.profileId, if (attributesOrNull(root) == null) null else
                verifier.captureProfileRemainder(root, legacyNames(plan, profile.profileId), signal).sha256)
        }
        val cleanup = collectCleanup(plan, knownIndexes)
        val record = MigrationExecutionRecord(plan, MigrationExecutionPhase.PREPARED,
            plan.installations.map { MigrationEntryProgress(it.installationId) }, remainders, emptyList(), cleanup)
        var writeBytes = store.maximumEncodedBytes(record)
        normalRecordTexts(plan).values.forEach { writeBytes = Math.addExact(writeBytes, it.toByteArray(Charsets.UTF_8).size.toLong()) }
        entryRecords.values.forEach { writeBytes = Math.addExact(writeBytes, it.toByteArray(Charsets.UTF_8).size.toLong()) }
        // Count retained normal records and replacement siblings without deletion credit.
        writeBytes = Math.multiplyExact(writeBytes, 2L)
        val parent = requireCriticalDirectory(paths.getGameLibraryMigrationFile(gameId).parentFile!!)
        val available = checkNotNull(availableBytes(parent)) { "Migration metadata capacity is unknown" }
        StorageCapacity.requireCopySpace(parent, writeBytes) { available }
        operations.syncDirectory(parent)
        requireContext(signal)
        return record
    }

    private fun requireProtectedEvidence(record: MigrationExecutionRecord, signal: InstallCancellationSignal) {
        requireContext(signal)
        inventory.requireUnmovedInputs(record.plan, signal)
        check(attributesOrNull(paths.getGameLibraryInstallTransactionDir(gameId)) == null) { "Install recovery appeared during migration" }
        for (remainder in record.protectedRemainders) {
            val root = paths.getLegacyProfileInternalDir(remainder.profileId)
            if (remainder.sha256 == null) check(attributesOrNull(root) == null) { "Unrecorded profile storage appeared" }
            else check(verifier.captureProfileRemainder(root, legacyNames(record.plan, remainder.profileId), signal).sha256 == remainder.sha256) {
                "Unmoved profile content changed; preserve evidence"
            }
        }
        val destinationRoot = File(paths.getGameLibraryCatalogueFile(gameId).parentFile, "mods")
        val allowed = record.plan.installations.map { File(it.destinationPath).name }.toSet()
        if (attributesOrNull(destinationRoot) != null) Files.newDirectoryStream(requireCriticalDirectory(destinationRoot).toPath()).use { children ->
            for (child in children) {
                requireContext(signal)
                check(child.fileName.toString() in allowed) { "Unknown shared payload appeared during migration" }
            }
        }
    }

    private fun requirePayload(installation: MigrationInstallationPlan, progress: MigrationEntryProgress, text: String, signal: InstallCancellationSignal) {
        val destination = File(installation.destinationPath)
        val metadata = CriticalMetadataFile(File(destination, LibraryInstallReplacement.ENTRY_RECORD), operations).read(maximumBytes)
        val actual = if (metadata == null) {
            check(!progress.metadataVerified) { "Verified entry record disappeared" }
            verifier.capture(destination, signal)
        } else {
            check(progress.metadataIntended && metadata == text) { "Unexpected or changed entry record" }
            verifier.captureWithEntryRecord(destination, text, signal)
        }
        check(actual == installation.tree) { "Moved payload does not match recorded bytes" }
    }

    private fun requireAllPayloads(record: MigrationExecutionRecord, signal: InstallCancellationSignal, entryRecords: Map<String, String>) {
        for ((index, installation) in record.plan.installations.withIndex()) {
            check(attributesOrNull(File(installation.sourcePath)) == null) { "Old payload reappeared; preserve both locations" }
            requirePayload(installation, record.entries[index], entryRecords.getValue(installation.installationId), signal)
            check(record.entries[index].metadataVerified)
        }
    }

    private fun normalRecordTexts(plan: GameLibraryMigrationPlan): Map<File, String> = linkedMapOf<File, String>().apply {
        put(paths.getGameLibraryCatalogueFile(gameId), repository.encodeLibrary(repository.decodeLibrary(plan.candidateCatalogue)))
        plan.profiles.forEach { profile -> put(paths.getProfileMembershipFile(gameId, profile.profileId),
            repository.encodeProfile(repository.decodeProfile(profile.profileId, profile.candidateMembership))) }
    }

    private fun requirePublicationState(record: MigrationExecutionRecord) {
        for ((file, expected) in normalRecordTexts(record.plan)) {
            val text = CriticalMetadataFile(file, operations).read(maximumBytes)
            when (record.phase) {
                MigrationExecutionPhase.PREPARED, MigrationExecutionPhase.MOVING -> check(text == null) { "Publication appeared before payload verification" }
                MigrationExecutionPhase.PUBLISHING -> check(text == null || text == expected) { "Foreign migration publication evidence" }
                MigrationExecutionPhase.RELOADING -> check(text == expected) { "Migration publication is missing or changed" }
                MigrationExecutionPhase.COMPLETE -> Unit // Subsequent normal revisions are legitimate.
            }
        }
    }

    private fun legacyNames(plan: GameLibraryMigrationPlan, profileId: String): Set<String> =
        plan.installations.filter { it.profileId == profileId }.map { it.legacyModId }.toSet()

    private fun prepareInternalDirectory(directory: File) {
        val root = paths.getManagedInternalDirectory()
        inventory.validateInputPath(directory)
        check(directory.toPath().startsWith(root.toPath()))
        if (directory == root) { operations.syncDirectory(root); return }
        val parent = checkNotNull(directory.parentFile)
        // An existing directory may be the result of a creation whose parent barrier
        // failed. Confirm the whole owned ancestor chain on restart, not just its leaf.
        prepareInternalDirectory(parent)
        if (attributesOrNull(directory) == null) {
            operations.syncDirectory(parent)
            Files.createDirectory(directory.toPath())
        } else requireCriticalDirectory(directory)
        operations.syncDirectory(directory)
        operations.syncDirectory(parent)
    }

    private fun confirmRecord(file: File, text: String) {
        val metadata = CriticalMetadataFile(file, operations)
        check(metadata.read(maximumBytes) == text) { "Migration metadata does not match its normal record" }
        operations.syncDirectory(requireCriticalDirectory(file.parentFile!!))
        check(metadata.read(maximumBytes) == text) { "Migration metadata changed during persistence confirmation" }
    }

    private fun cleanupCandidate(file: File, kind: MigrationCleanupKind, text: String? = null): MigrationCleanupCandidate? {
        val attributes = attributesOrNull(file) ?: return null
        check(if (kind == MigrationCleanupKind.FILE) attributes.isRegularFile else attributes.isDirectory)
        return MigrationCleanupCandidate(file.absolutePath, kind, attributes.fileKey()?.toString(),
            attributes.lastModifiedTime().toMillis(), text?.let { migrationHash(it.toByteArray(Charsets.UTF_8)) })
    }

    private fun collectCleanup(plan: GameLibraryMigrationPlan, knownIndexes: List<MigrationCleanupCandidate>): List<MigrationCleanupCandidate> {
        val result = linkedMapOf<String, MigrationCleanupCandidate>()
        knownIndexes.forEach { result[it.path] = it }
        fun add(file: File, kind: MigrationCleanupKind, text: String? = null) {
            cleanupCandidate(file, kind, text)?.let { result[it.path] = it }
        }
        for (profile in plan.profiles) {
            val internal = paths.getLegacyProfileInternalDir(profile.profileId)
            val state = File(inventory.externalFilesDir, "state/profiles/${internal.name}")
            val stateFile = File(state, "installed_mods.json")
            profile.legacyState?.let { text ->
                check(CriticalMetadataFile(stateFile, operations).read(maximumBytes) == text)
                add(stateFile, MigrationCleanupKind.FILE, text)
            }
            val indexRoot = File(state, "mod_file_indexes")
            add(File(internal, "mods"), MigrationCleanupKind.EMPTY_DIRECTORY)
            add(indexRoot, MigrationCleanupKind.EMPTY_DIRECTORY)
        }
        return result.values.sortedBy { it.kind.ordinal }
    }

    private fun isKnownLegacyIndex(text: String, installation: MigrationInstallationPlan, legacyName: String,
                                   actualFiles: List<ModFileIndexEntry>): Boolean = try {
        val json = ModLibraryJsonReader.read(text)
        migrationKeys(json, setOf("schemaVersion", "modId", "modName", "modRootPath", "createdAtEpochMillis", "entries"))
        check(json.requiredInt("schemaVersion") == ModFileIndexRepository.CURRENT_SCHEMA_VERSION)
        check(json.requiredString("modId") == installation.legacyModId && json.requiredString("modRootPath") == installation.sourcePath)
        check(json.requiredString("modName") == legacyName); json.requiredLong("createdAtEpochMillis")
        val rootName = File(installation.sourcePath).name.lowercase()
        val expected = if (rootName.startsWith(".") || rootName == "__macosx") emptyMap() else
            actualFiles.filter { PathUtils.normalize(it.originalPath) != null }.associateBy { it.originalPath }
        val entries = json.getJSONArray("entries").objects()
        check(entries.size == expected.size && entries.map { it.requiredString("originalPath") }.distinct().size == entries.size)
        entries.forEach { entry ->
            migrationKeys(entry, setOf("originalPath", "normalizedPath", "hash", "sizeBytes", "modifiedEpochMillis"))
            val path = entry.requiredString("originalPath")
            val actual = expected.getValue(path)
            check(entry.requiredString("normalizedPath") == PathUtils.normalize(path))
            check(entry.requiredString("hash") == actual.hash && entry.requiredLong("sizeBytes") == actual.sizeBytes &&
                entry.requiredLong("modifiedEpochMillis") == actual.modifiedEpochMillis)
        }
        true
    } catch (_: Exception) { false } // Unknown evidence is preserved, never adopted as a cache.

    private fun cleanup(initial: MigrationExecutionRecord, signal: InstallCancellationSignal): MigrationExecutionResult {
        var current = initial
        var failure: Exception? = null
        for (index in current.cleanup.indices) {
            val candidate = current.cleanup[index]
            if (candidate.retired) continue
            try {
                requireContext(signal)
                val file = File(candidate.path)
                val attributes = attributesOrNull(file)
                if (attributes != null) {
                    check(candidate.fileKey != null && candidate.fileKey == attributes.fileKey()?.toString()) { "Cleanup identity changed or is unavailable" }
                    val library = checkNotNull(LibraryInstallReplacement(paths, gameId).loadPublishedLibrary()) { "Published library is unavailable for cleanup" }
                    if (candidate.kind == MigrationCleanupKind.FILE) {
                        check(attributes.isRegularFile && attributes.lastModifiedTime().toMillis() == candidate.modifiedAtMillis)
                        val text = checkNotNull(CriticalMetadataFile(file, operations).read(maximumBytes))
                        check(migrationHash(text.toByteArray(Charsets.UTF_8)) == candidate.sha256) { "Obsolete metadata changed; preserve it" }
                        val indexParent = checkNotNull(file.parentFile)
                        if (indexParent.name == "mod_file_indexes") {
                            check(library.entries.none { ModFileIndexRepository(indexParent).getIndexFileForModId(it.installationId) == file }) {
                                "Index filename is used by a current installation"
                            }
                        }
                    } else {
                        requireCriticalDirectory(file)
                        Files.newDirectoryStream(file.toPath()).use { check(!it.iterator().hasNext()) { "Obsolete directory is not empty; preserve it" } }
                    }
                    val unchanged = checkNotNull(attributesOrNull(file))
                    check(unchanged.fileKey() == attributes.fileKey() && unchanged.lastModifiedTime() == attributes.lastModifiedTime() && unchanged.size() == attributes.size())
                    requireContext(signal)
                    deleteOwnedPath(file) // Exact allowlist only, never recursive deletion.
                }
                var parent = checkNotNull(file.parentFile)
                while (attributesOrNull(parent) == null) parent = checkNotNull(parent.parentFile)
                operations.syncDirectory(requireCriticalDirectory(parent))
                check(attributesOrNull(file) == null) { "Cleanup path reappeared; preserve it" }
                val next = current.copy(cleanup = current.cleanup.mapIndexed { position, value -> if (position == index) value.copy(retired = true) else value })
                try { store.save(current, next) } catch (uncertain: Exception) {
                    return MigrationExecutionResult(gameId, true, uncertain)
                }
                current = next
            } catch (exception: Exception) { if (failure == null) failure = exception }
        }
        return MigrationExecutionResult(gameId, current.cleanup.any { !it.retired }, failure)
    }

    private fun requireContext(signal: InstallCancellationSignal) {
        signal.throwIfCancellationRequested()
        check(contextStillCurrent()) { "Captured migration context changed" }
    }

    private fun attributesOrNull(file: File): BasicFileAttributes? = try {
        inventory.validateInputPath(file)
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
            check(!it.isSymbolicLink && (it.isDirectory || it.isRegularFile)) { "Unsafe migration path" }
        }
    } catch (_: NoSuchFileException) { null }
}
