package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallFileCopier
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Owns only new temporary import copies, never the selected source or legacy archive library. */
internal class ArchiveImportFileStore(
    private val externalFilesDirProvider: () -> File?,
    private val appendError: (String) -> Unit
) {
    private val activeAttempts = mutableSetOf<Path>()

    @Synchronized
    fun copyToTemporarySnapshot(
        sourceFile: File,
        displayName: String,
        cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ): File {
        cancellationSignal.throwIfCancellationRequested()
        val source = sourceFile.canonicalFile
        require(source.isFile && source.canRead()) { "Selected archive is not readable: $source" }
        val root = stagingDirectory(create = true)!!
        require(!source.toPath().startsWith(root)) { "Cannot import an active temporary archive snapshot." }
        val name = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid archive name." }

        val sourceBefore = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)
        val attempt = Files.createTempDirectory(root, "import-")
        activeAttempts.add(attempt)
        val snapshot = attempt.resolve(name).toFile()
        try {
            InstallFileCopier.copyRecursively(source, snapshot, cancellationSignal)
            cancellationSignal.throwIfCancellationRequested()
            val sourceAfter = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)
            if (!sourceAfter.isRegularFile || sourceBefore.size() != sourceAfter.size() ||
                sourceBefore.lastModifiedTime() != sourceAfter.lastModifiedTime() ||
                sourceBefore.fileKey() != sourceAfter.fileKey() || snapshot.length() != sourceBefore.size()) {
                throw IOException("Selected archive changed while its snapshot was copied.")
            }
            return snapshot
        } catch (failure: Throwable) {
            try {
                deleteAttempt(attempt)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
                appendError("Could not remove partial archive snapshot: ${cleanupFailure.message}")
            } finally {
                activeAttempts.remove(attempt)
            }
            throw failure
        }
    }

    /** Called only after preparation no longer reads the archive, including choice handoff. */
    @Synchronized
    fun releaseSnapshot(snapshot: File) {
        val attempt = snapshot.absoluteFile.toPath().normalize().parent
        check(attempt in activeAttempts) { "Archive snapshot is not owned by this import." }
        try {
            val root = stagingDirectory(create = false)
            check(attempt.parent == root) { "Archive snapshot escaped its staging directory." }
            deleteAttempt(attempt)
        } finally {
            activeAttempts.remove(attempt)
        }
    }

    /** Ordered startup only. Rotation keeps this owner and its active attempts alive. */
    @Synchronized
    fun cleanAbandonedSnapshots() {
        val root = stagingDirectory(create = false) ?: return
        Files.newDirectoryStream(root).use { entries ->
            for (attempt in entries) {
                if (attempt in activeAttempts || !attempt.fileName.toString().matches(Regex("import-\\d+"))) continue
                deleteAttempt(attempt)
            }
        }
    }

    private fun stagingDirectory(create: Boolean): Path? {
        val base = externalFilesDirProvider()?.canonicalFile
            ?: throw IOException("External files directory is unavailable.")
        val downloads = File(base, "downloads").toPath()
        val root = downloads.resolve("import_staging")
        if (Files.isSymbolicLink(downloads) || Files.isSymbolicLink(root)) {
            throw IOException("Archive staging must not use symbolic links.")
        }
        if (create) Files.createDirectories(root)
        val attributes = try {
            Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isDirectory) throw IOException("Archive staging is not a directory: $root")
        return root
    }

    private fun deleteAttempt(attempt: Path) {
        val attributes = Files.readAttributes(attempt, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory) throw IOException("Archive attempt is not a directory: $attempt")
        Files.newDirectoryStream(attempt).use { entries ->
            for (entry in entries) {
                val child = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!child.isRegularFile && !child.isSymbolicLink) {
                    throw IOException("Unexpected content in archive staging: $entry")
                }
                Files.delete(entry) // Never follow a link or recursively delete an unknown directory.
            }
        }
        Files.delete(attempt)
    }
}
