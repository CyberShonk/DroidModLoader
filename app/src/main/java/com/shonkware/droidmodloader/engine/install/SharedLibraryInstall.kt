package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.GameLibraryEntry
import com.shonkware.droidmodloader.engine.model.GameModLibrary
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.ProfileModChoice
import com.shonkware.droidmodloader.engine.model.ProfileModMembership
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import java.io.File

internal data class LibraryInstallStaging(
    val stageDirectory: File,
    val finalDirectory: File,
    val hasExistingInstall: Boolean,
    val publish: () -> Unit
)

/** The confirmation includes disabled references and their saved revisions/choices.
 * The profile inventory provider must enumerate the complete game scope reliably;
 * missing, duplicate, unreadable or changed scope blocks a shared update. */
internal data class LibraryInstallReview(
    val gameId: String,
    val installationId: String,
    val library: GameModLibrary?,
    val profiles: Map<String, ProfileModMembership?>
) {
    val affectedProfileIds: Set<String> get() = profiles.filterValues { membership ->
        membership?.choices?.any { it.installationId == installationId } == true
    }.keys
}

internal data class SharedLibraryInstallResult(
    val entry: GameLibraryEntry,
    val profileSelectionSaved: Boolean,
    val derivedViewsReady: Boolean,
    val profileFailure: Exception? = null,
    val indexFailure: Exception? = null,
    val temporaryCleanupFailure: Exception? = null
) {
    val message: String get() = if (profileSelectionSaved) "Installed in library"
        else "Installed in library; profile selection not saved"
}

/** Staged normal installation only; not connected to the UI, legacy readers or targets.
 * Existing retained-session admission must cover review revalidation, install, profile
 * save and derived invalidation. Providers must not consult a mutable selected game.
 * These synchronous APIs run on that admitted background path, never the UI thread. */
internal class SharedLibraryInstall(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val installer: PreparedArchiveInstaller,
    private val allGameProfileIds: () -> List<String>,
    private val repository: GameModLibraryRepository = GameModLibraryRepository(paths, gameId),
    private val replacement: LibraryInstallReplacement = LibraryInstallReplacement(paths, gameId),
    private val availableBytes: (File) -> Long? = AndroidStorageCapacityProbe::availableBytes,
    private val invalidateDerivedViews: (GameLibraryEntry, Set<String>) -> Unit,
    private val rebuildIndex: (GameLibraryEntry) -> Unit
) {
    fun review(installationId: String): LibraryInstallReview {
        paths.getGameLibraryEntryDir(gameId, installationId)
        val library = replacement.loadPublishedLibrary()
        val isUpdate = library?.entries?.any { it.installationId == installationId } == true
        val ids = allGameProfileIds()
        check(ids.size == ids.distinct().size) { "Profile scope has duplicate identities" }
        val profiles = ids.associateWith { profileId ->
            repository.loadProfile(profileId).also {
                check(!isUpdate || it != null) { "A profile's saved mod choices are missing; update stopped" }
            }
        }
        return LibraryInstallReview(gameId, installationId, library, profiles)
    }

    /** Passing the reviewed value is the caller's explicit confirmation. The context
     * validator must compare the admitted context generation, not just profile names. */
    fun install(
        operationId: String,
        review: LibraryInstallReview,
        requestingProfileId: String,
        metadata: InstalledModRecord,
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        requestedChoice: ProfileModChoice?,
        contextStillCurrent: () -> Boolean,
        signal: InstallCancellationSignal = InstallCancellationSignal.NONE,
        manualLayoutProposal: ManualLayoutProposal? = null
    ): SharedLibraryInstallResult {
        check(review.gameId == gameId && contextStillCurrent()) { "Install context changed" }
        check(review == review(review.installationId)) { "Library or affected profiles changed; review the update again" }
        check(requestingProfileId in review.profiles) { "Requesting profile is outside the captured scope" }
        val previousEntry = review.library?.entries?.singleOrNull { it.installationId == review.installationId }
        check(metadata.modId == review.installationId &&
            metadata.installPath == paths.getGameLibraryEntryDir(gameId, review.installationId).absolutePath)
        check(requestedChoice == null || requestedChoice.installationId == review.installationId)
        val previousProfile = review.profiles[requestingProfileId]
        val requestedMembership = requestedChoice?.let { choice ->
            ProfileModMembership(gameId, requestingProfileId, Math.addExact(previousProfile?.revision ?: 0L, 1L),
                previousProfile?.choices.orEmpty().filterNot { it.installationId == choice.installationId } + choice)
                .also(repository::validateProfileMembership)
        }
        val installedMetadata = preserveSourceAssociations(metadata, previousEntry?.installedMetadata)
        val entry = GameLibraryEntry(review.installationId, Math.addExact(previousEntry?.payloadRevision ?: 0L, 1L), installedMetadata)
        val library = GameModLibrary(gameId, Math.addExact(review.library?.revision ?: 0L, 1L),
            review.library?.entries.orEmpty().filterNot { it.installationId == entry.installationId } + entry)
        // Count each selected copy. Retained old bytes already consume free space;
        // never credit their future deletion against the simultaneous staging budget.
        val incomingBytes = installer.libraryCopyBytes(prepared, selection, manualLayoutProposal, signal)
        val oldBytes = if (previousEntry == null) 0L else ManualLayoutInventory.capture(
            paths.getGameLibraryEntryDir(gameId, entry.installationId).parentFile!!,
            paths.getGameLibraryEntryDir(gameId, entry.installationId), signal
        ).entries.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
        val copyBytes = Math.addExact(incomingBytes, if (prepared.confirmation?.existingModAction == ExistingModAction.ADD_UPDATE) oldBytes else 0L)
        val newBytes = Math.addExact(copyBytes, 8L * GameModLibraryRepository.MAX_RECORD_BYTES)
        val parent = paths.getGameLibraryCatalogueFile(gameId).parentFile!!
        val available = checkNotNull(availableBytes(parent)) { "Free storage could not be checked" }
        StorageCapacity.requireCopySpace(parent, newBytes) { available }
        signal.throwIfCancellationRequested()
        check(contextStillCurrent()) { "Install context changed" }
        val stage = replacement.begin()
        var publicationAttempted = false
        var publicationCompleted = false
        var temporaryCleanupFailure: Exception? = null
        try {
            installer.finalizeLibraryInstall(prepared, selection,
                LibraryInstallStaging(stage, paths.getGameLibraryEntryDir(gameId, entry.installationId), previousEntry != null) {
                    // Recheck before durable intent; no mutable selection can retarget this request.
                    check(contextStillCurrent()) { "Install context changed" }
                    check(review.profiles == readProfiles(review.profiles.keys)) { "Affected profiles changed" }
                    publicationAttempted = true
                    replacement.publish(operationId, review.library, library, entry, signal)
                    publicationCompleted = true
                }, signal, manualLayoutProposal)
        } catch (failure: Exception) {
            if (publicationCompleted) temporaryCleanupFailure = failure
            else {
                if (!publicationAttempted) try { replacement.discardUnpublishedStage() }
                catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
                throw failure
            }
        }
        var profileFailure: Exception? = null
        try {
            check(contextStillCurrent()) { "Profile context changed after library commit" }
            signal.throwIfCancellationRequested()
            check(repository.loadProfile(requestingProfileId) == previousProfile) { "Profile choices changed" }
            if (requestedMembership != null) {
                repository.saveProfile(requestingProfileId, requestedMembership, previousProfile?.revision)
            }
        } catch (failure: Exception) { profileFailure = failure }
        var indexFailure: Exception? = null
        try {
            invalidateDerivedViews(entry, review.affectedProfileIds + requestingProfileId)
            rebuildIndex(entry)
        } catch (failure: Exception) { indexFailure = failure }
        return SharedLibraryInstallResult(entry, profileFailure == null, indexFailure == null, profileFailure, indexFailure, temporaryCleanupFailure)
    }

    fun recoverInterruptedInstall(): Boolean = replacement.recover()

    fun finishPrivateInstallCleanup() = replacement.finish()

    /** Explicit later selection reuses a verified entry; never installs again. */
    fun selectExisting(profileId: String, expectedProfileRevision: Long?, choice: ProfileModChoice) {
        val library = checkNotNull(replacement.loadPublishedLibrary()) { "Library is missing" }
        check(library.entries.any { it.installationId == choice.installationId }) { "Installation is missing" }
        val previous = repository.loadProfile(profileId)
        check(previous?.revision == expectedProfileRevision) { "Profile choices changed" }
        repository.saveProfile(profileId, ProfileModMembership(gameId, profileId,
            Math.addExact(expectedProfileRevision ?: 0L, 1L),
            previous?.choices.orEmpty().filterNot { it.installationId == choice.installationId } + choice), expectedProfileRevision)
    }

    private fun readProfiles(ids: Set<String>): Map<String, ProfileModMembership?> {
        check(allGameProfileIds().let { it.size == it.distinct().size && it.toSet() == ids }) { "Profile scope changed" }
        return ids.associateWith(repository::loadProfile)
    }

    private fun preserveSourceAssociations(incoming: InstalledModRecord, previous: InstalledModRecord?): InstalledModRecord =
        if (previous == null) incoming else incoming.copy(
            sourceArchiveName = incoming.sourceArchiveName ?: previous.sourceArchiveName,
            nexusGameDomain = incoming.nexusGameDomain ?: previous.nexusGameDomain,
            nexusModId = incoming.nexusModId ?: previous.nexusModId,
            nexusFileId = incoming.nexusFileId ?: previous.nexusFileId,
            nexusFileName = incoming.nexusFileName ?: previous.nexusFileName,
            sourceUrl = incoming.sourceUrl ?: previous.sourceUrl
        )
}
