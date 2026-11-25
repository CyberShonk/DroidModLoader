package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.GameLibraryEntry
import com.shonkware.droidmodloader.engine.model.GameModLibrary
import com.shonkware.droidmodloader.engine.model.ProfileModMembership
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal data class SharedLibraryRemovalReview(
    val gameId: String,
    val installationId: String,
    val library: GameModLibrary,
    val profiles: Map<String, ProfileModMembership?>
) {
    val affectedProfileIds: Set<String> get() = profiles.filterValues { membership ->
        membership?.choices?.any { it.installationId == installationId } == true
    }.keys
}

/** Returned only after a confirmed library commit. Cancellation or cleanup failure
 * after that point cannot mean restored, enabled or still installed. */
internal data class SharedLibraryUninstallResult(
    val removedEntry: GameLibraryEntry,
    val cleanupPending: Boolean,
    val derivedViewsReady: Boolean,
    val cleanupFailure: Exception? = null,
    val indexFailure: Exception? = null
) {
    val message: String get() = if (cleanupPending) "Uninstalled; cleanup pending" else "Uninstalled"
}

/** Staged shared removal, not connected to active controls or game targets. Caller
 * supplies the complete captured game-profile inventory and retains existing session
 * admission across review validation, publication, invalidation and private cleanup.
 * Synchronous filesystem work belongs on that admitted background path. */
internal class SharedLibraryUninstall(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val allGameProfileIds: () -> List<String>,
    private val repository: GameModLibraryRepository = GameModLibraryRepository(paths, gameId),
    private val replacement: LibraryInstallReplacement = LibraryInstallReplacement(paths, gameId),
    private val availableBytes: (File) -> Long? = AndroidStorageCapacityProbe::availableBytes,
    private val invalidateDerivedViews: (GameLibraryEntry, Set<String>) -> Unit
) {
    fun review(installationId: String): SharedLibraryRemovalReview {
        paths.getGameLibraryEntryDir(gameId, installationId)
        val library = checkNotNull(replacement.loadPublishedLibrary()) { "Library is missing" }
        check(library.entries.any { it.installationId == installationId }) { "Installation is missing" }
        return SharedLibraryRemovalReview(gameId, installationId, library,
            repository.loadProfileScope(allGameProfileIds(), requireSavedMembership = true))
    }

    /** Passing this reviewed value is explicit uninstall confirmation. Compare the
     * admitted context generation, not just profile names, in contextStillCurrent. */
    fun uninstall(
        operationId: String,
        review: SharedLibraryRemovalReview,
        requestingProfileId: String,
        contextStillCurrent: () -> Boolean,
        signal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ): SharedLibraryUninstallResult {
        check(review.gameId == gameId && contextStillCurrent()) { "Uninstall context changed" }
        check(requestingProfileId in review.profiles) { "Requesting profile is outside the captured scope" }
        check(review == review(review.installationId)) { "Library or affected profiles changed; review uninstall again" }
        val entry = review.library.entries.single { it.installationId == review.installationId }
        val newLibrary = review.library.copy(revision = Math.addExact(review.library.revision, 1L),
            entries = review.library.entries.filterNot { it.installationId == review.installationId })
        val catalogue = paths.getGameLibraryCatalogueFile(gameId)
        val oldMetadataBytes = Files.readAttributes(catalogue.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).size()
        val newMetadataBytes = repository.encodeLibrary(newLibrary).toByteArray(Charsets.UTF_8).size.toLong()
        // Old/new snapshots, replacement siblings and bounded control records. Payload
        // moves do not copy bytes; retained files already consume available space.
        val requiredBytes = Math.addExact(Math.addExact(Math.addExact(oldMetadataBytes, newMetadataBytes),
            maxOf(oldMetadataBytes, newMetadataBytes)), 16L * 1024)
        val available = checkNotNull(availableBytes(catalogue.parentFile!!)) { "Free storage could not be checked" }
        StorageCapacity.requireCopySpace(catalogue.parentFile!!, requiredBytes) { available }
        signal.throwIfCancellationRequested()
        check(contextStillCurrent()) { "Uninstall context changed" }
        replacement.begin()
        var publicationAttempted = false
        try {
            check(contextStillCurrent()) { "Uninstall context changed" }
            check(repository.loadProfileScope(allGameProfileIds(), true) == review.profiles) { "Affected profiles changed" }
            publicationAttempted = true
            replacement.publishRemoval(operationId, review.library, review.installationId, signal) {
                check(contextStillCurrent()) { "Uninstall context changed" }
                check(repository.loadProfileScope(allGameProfileIds(), true) == review.profiles) { "Affected profiles changed" }
            }
        } catch (failure: Exception) {
            if (!publicationAttempted) try { replacement.discardUnpublishedStage() }
            catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            throw failure // Uncertain publication requires explicit reconciliation, never inferred success.
        }
        var indexFailure: Exception? = null
        try { invalidateDerivedViews(entry, review.affectedProfileIds) }
        catch (failure: Exception) { indexFailure = failure }
        var cleanupFailure: Exception? = null
        try { replacement.finish(LibraryInstallReplacement.Operation.UNINSTALL) }
        catch (failure: Exception) { cleanupFailure = failure }
        return SharedLibraryUninstallResult(entry, cleanupFailure != null, indexFailure == null, cleanupFailure, indexFailure)
    }

    /** Recovery never rewrites profile references or replays a removal request. */
    fun recoverInterruptedRemoval(): Boolean = replacement.recover(LibraryInstallReplacement.Operation.UNINSTALL)

    fun finishPrivateRemovalCleanup() = replacement.finish(LibraryInstallReplacement.Operation.UNINSTALL)
}
