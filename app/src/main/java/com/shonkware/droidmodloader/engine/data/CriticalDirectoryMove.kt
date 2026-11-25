package com.shonkware.droidmodloader.engine.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A failed critical operation, not a recovery decision. False means no authoritative
 * rename was attempted (private staging may exist). True requires strict reread and
 * owner-specific reconciliation: the old or new state may be present. Do not retry
 * blindly, infer rollback, or treat it as a corrupt/empty repository.
 */
internal class CriticalStorageException(
    message: String,
    val mutationMayHaveOccurred: Boolean,
    cause: Exception
) : IOException(message, cause)

/**
 * Move a caller-owned directory within one filesystem; never copy/delete or replace
 * an observed destination. Parents must already exist and all paths must be canonical.
 * Callers own containment, content verification, durable intent and recovery records.
 *
 * Requires exclusive access to these private paths, including their parents. NIO
 * ATOMIC_MOVE does not provide atomic no-clobber against an uncontrolled concurrent
 * writer. This primitive is not cross-process locking or an all-profile migration.
 * No source tree flush or power-loss guarantee is implied by directory sync alone.
 */
internal class CriticalDirectoryMove(
    private val operations: CriticalDirectoryMoveOperations = SystemCriticalDirectoryMoveOperations
) {
    fun moveToAbsent(source: File, destination: File) {
        var mutationMayHaveOccurred = false
        try {
            val sourceDirectory = requireCriticalDirectory(source)
            val sourceParent = requireCriticalDirectory(
                sourceDirectory.parentFile ?: throw IOException("Source has no parent directory")
            )
            val destinationParent = requireCriticalDirectory(
                destination.absoluteFile.parentFile
                    ?: throw IOException("Destination has no parent directory")
            )
            val destinationPath = File(destinationParent, destination.name)
            if (destinationPath.toPath().startsWith(sourceDirectory.toPath())) {
                throw IOException("Destination must not be the source or inside it")
            }
            requireAbsentCriticalPath(destinationPath)
            if (!operations.sameFileStore(sourceDirectory, destinationParent)) {
                throw IOException("Directory move requires the same filesystem")
            }
            val parents = listOf(destinationParent, sourceParent).distinct()
            // Unsupported directory persistence must fail before moving payloads.
            parents.forEach(operations::syncDirectory)
            requireCriticalDirectory(sourceDirectory)
            requireAbsentCriticalPath(destinationPath)
            mutationMayHaveOccurred = true
            operations.move(sourceDirectory, destinationPath)
            // Persist the new name before removal of the old name across parents.
            parents.forEach(operations::syncDirectory)
        } catch (exception: Exception) {
            throw CriticalStorageException(
                "Could not durably move directory: ${source.name}",
                mutationMayHaveOccurred,
                exception
            )
        }
    }
}

internal interface CriticalDirectoryMoveOperations {
    fun sameFileStore(source: File, destinationParent: File): Boolean
    fun syncDirectory(directory: File)
    fun move(source: File, destination: File)
}

internal object SystemCriticalDirectoryMoveOperations : CriticalDirectoryMoveOperations {
    override fun sameFileStore(source: File, destinationParent: File): Boolean =
        Files.getFileStore(source.toPath()) == Files.getFileStore(destinationParent.toPath())

    override fun syncDirectory(directory: File) = syncCriticalDirectory(directory)

    override fun move(source: File, destination: File) {
        // ATOMIC_MOVE has implementation-specific replacement behavior. Check again
        // at the native boundary; exclusivity of the caller-owned parents is required.
        requireAbsentCriticalPath(destination)
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

internal fun requireCriticalDirectory(directory: File): File {
    val absolute = directory.absoluteFile
    if (absolute != absolute.canonicalFile) {
        throw IOException("Directory path contains an alias or symbolic link")
    }
    val attributes = Files.readAttributes(
        absolute.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
    )
    if (!attributes.isDirectory) throw IOException("Path is not a directory")
    return absolute
}

private fun requireAbsentCriticalPath(file: File) {
    try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return
    }
    throw IOException("Destination already exists")
}

internal fun syncCriticalDirectory(directory: File) {
    val validatedDirectory = requireCriticalDirectory(directory)
    FileChannel.open(
        validatedDirectory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
    ).use { channel -> channel.force(true) }
}
