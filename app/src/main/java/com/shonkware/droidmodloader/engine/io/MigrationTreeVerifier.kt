package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.install.LibraryInstallReplacement
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class MigrationTreeDigest(val sha256: String, val entryCount: Int, val payloadBytes: Long)

/** Staged migration verification, not a discovery/index API. Caller retains exclusive
 * session admission and runs off the UI thread. Streams file bytes and keeps only
 * bounded names for the current directory chain, never the complete tree inventory.
 * Limits are verification workspace accounting, not a promise about total JVM RSS.
 * No file, directory or timestamp is written. */
internal class MigrationTreeVerifier(
    private val maximumEntries: Int = 200_000,
    private val maximumWorkspaceBytes: Long = 32L * 1024 * 1024
) {
    init {
        require(maximumEntries in 1..200_000)
        require(maximumWorkspaceBytes in 64 * 1024L..32L * 1024 * 1024)
    }

    fun capture(
        directory: File,
        cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ): MigrationTreeDigest = captureTree(directory, emptySet(), cancellationSignal)

    /** Only exact mapped mods slots are omitted. Every such slot must independently
     * pass the execution owner's old/new verification. Unknown files remain hashed. */
    internal fun captureProfileRemainder(directory: File, legacyInstallationNames: Set<String>,
                                         signal: InstallCancellationSignal = InstallCancellationSignal.NONE): MigrationTreeDigest {
        require(legacyInstallationNames.size <= maximumEntries)
        legacyInstallationNames.forEach { name ->
            require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' })
        }
        return captureTree(directory, legacyInstallationNames.map { "mods/$it" }.toSet(), signal)
    }

    /** A single journaled normal entry sidecar is the only allowed payload addition.
     * Match its exact normal-writer bytes before/after hashing everything else. */
    internal fun captureWithEntryRecord(directory: File, expectedEntryText: String,
                                        signal: InstallCancellationSignal = InstallCancellationSignal.NONE): MigrationTreeDigest {
        signal.throwIfCancellationRequested()
        val root = requireCriticalDirectory(directory)
        val metadata = CriticalMetadataFile(File(root, LibraryInstallReplacement.ENTRY_RECORD))
        check(metadata.read(GameLibraryMigrationPlanRepository.MAX_BYTES) == expectedEntryText) { "Migration entry metadata changed" }
        val digest = captureTree(root, setOf(LibraryInstallReplacement.ENTRY_RECORD), signal)
        check(metadata.read(GameLibraryMigrationPlanRepository.MAX_BYTES) == expectedEntryText) { "Migration entry metadata changed during verification" }
        return digest
    }

    private fun captureTree(directory: File, omittedPaths: Set<String>, cancellationSignal: InstallCancellationSignal): MigrationTreeDigest {
        cancellationSignal.throwIfCancellationRequested()
        val root = requireCriticalDirectory(directory)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var workspaceBytes = buffer.size.toLong()
        var entryCount = 0
        var payloadBytes = 0L

        fun attributes(file: File): BasicFileAttributes {
            if (file.absoluteFile != file.canonicalFile) throw IOException("Migration tree contains an alias")
            return Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
                if (it.isSymbolicLink || (!it.isRegularFile && !it.isDirectory)) {
                    throw IOException("Migration tree contains a link or unsupported entry")
                }
            }
        }

        fun requireUnchanged(before: BasicFileAttributes, after: BasicFileAttributes) {
            if (before.fileKey() != after.fileKey() || before.isDirectory != after.isDirectory ||
                before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
                throw IOException("Migration tree changed during verification")
            }
        }

        fun hashText(text: String) {
            val bytes = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array())
            digest.update(bytes)
        }

        fun visit(current: File, relativePath: String, depth: Int) {
            cancellationSignal.throwIfCancellationRequested()
            if (depth > 64 || relativePath.length > 4096) throw IOException("Migration tree path exceeds bounds")
            val before = attributes(current)
            hashText(relativePath)
            digest.update(if (before.isDirectory) 0.toByte() else 1.toByte())
            if (before.isDirectory) {
                // Reserve conservatively for path/string/list storage before retaining
                // a child. Sorted depth-first traversal gives an unambiguous digest,
                // including empty directories, without a full-tree sort or hash map.
                val children = mutableListOf<String>()
                var retainedBytes = 0L
                Files.newDirectoryStream(current.toPath()).use { stream ->
                    for (child in stream) {
                        cancellationSignal.throwIfCancellationRequested()
                        val name = child.fileName.toString()
                        val relativeChild = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        if (relativeChild in omittedPaths) continue
                        if (++entryCount > maximumEntries) throw IOException("Migration tree exceeds entry limit")
                        if (name.length > 4096 || '\u0000' in name) throw IOException("Migration entry name exceeds bounds")
                        val reservation = 256L + 8L * (current.path.length + name.length)
                        if (reservation > maximumWorkspaceBytes - workspaceBytes) {
                            throw IOException("Migration tree exceeds verification workspace")
                        }
                        workspaceBytes += reservation
                        retainedBytes += reservation
                        children.add(name)
                    }
                }
                children.sort()
                for (name in children) {
                    visit(File(current, name), if (relativePath.isEmpty()) name else "$relativePath/$name", depth + 1)
                }
                workspaceBytes -= retainedBytes
            } else {
                val size = before.size()
                if (size < 0 || size > Long.MAX_VALUE - payloadBytes) throw IOException("Migration byte count exceeds bounds")
                payloadBytes += size
                digest.update(ByteBuffer.allocate(8).putLong(size).array())
                Files.newInputStream(current.toPath(), NOFOLLOW_LINKS).use { input ->
                    var remaining = size
                    while (remaining > 0) {
                        cancellationSignal.throwIfCancellationRequested()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count <= 0) throw IOException("Migration file shrank during verification")
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) throw IOException("Migration file grew during verification")
                }
            }
            requireUnchanged(before, attributes(current))
        }

        visit(root, "", 0)
        return MigrationTreeDigest(digest.digest().joinToString("") { "%02x".format(it) }, entryCount, payloadBytes)
    }

    fun requireMatching(
        directory: File,
        expected: MigrationTreeDigest,
        cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ) {
        if (capture(directory, cancellationSignal) != expected) throw IOException("Recorded migration payload changed")
    }
}
