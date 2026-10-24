package com.shonkware.droidmodloader.engine.download

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import org.json.JSONArray
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class LegacyArchiveCleanupResult(
    val removedFiles: Int,
    val removedBytes: Long,
    val preservedFiles: Int
)

/** Explicit maintenance only. No startup sweep, recursive deletion, or history rewriting. */
internal class LegacyArchiveCleanup(
    externalFilesDir: File,
    internalFilesDir: File,
    private val selectedFolders: () -> Set<String>,
    private val report: (String) -> Unit = {}
) {
    private val base = externalFilesDir.canonicalFile
    private val library = File(base, "downloads/archive_library")
    private val appRoots = listOf(base, internalFilesDir.canonicalFile)

    fun clean(): LegacyArchiveCleanupResult {
        checkInterrupted()
        val entries = children(library)
        if (entries.isEmpty()) return LegacyArchiveCleanupResult(0, 0, 0)
        // Read every profile (including deleted profiles), legacy state and selections before deleting.
        val evidence = readEvidence()
        val repository = DownloadedArchiveRepository(library, File(base, "state/downloaded_archives.json"))
        val records = evidence.metadata.values.filterNotNull().flatMap { text ->
            val decoded = repository.decode(text)
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val keys = array.getJSONObject(i).keys()
                while (keys.hasNext()) {
                    if (keys.next() !in KNOWN_FIELDS) throw IOException("Unknown archive metadata; nothing further removed.")
                }
            }
            decoded
        }
        val sources = records.mapNotNull { it.sourcePath }.associateWith(::sourceIdentity)
        val ambiguousTargets = records.filter { it.relativePath != it.fileName }.map { record ->
            val path = File(record.relativePath)
            (if (path.isAbsolute) path else File(library, record.relativePath)).canonicalFile
        }.toSet()
        var removed = 0
        var bytes = 0L
        var preserved = 0
        for (entry in entries) {
            checkInterrupted()
            val proof = try {
                if (entry in sources.values || entry.parentFile in evidence.folders || entry in ambiguousTargets) {
                    throw PreserveArchive("referenced as a source or selected library")
                }
                val matching = records.filter { it.relativePath == entry.name && it.fileName == entry.name }
                if (matching.isEmpty()) throw PreserveArchive("no direct copy record")
                val candidate = stamp(entry)
                if (matching.any { it.sourcePath == null || it.sizeBytes != candidate.size }) {
                    throw PreserveArchive("incomplete or stale copy records")
                }
                val originals = matching.map { File(checkNotNull(it.sourcePath)).absoluteFile }.distinct()
                val stamps = mutableListOf(entry to candidate)
                for (original in originals) {
                    if (appRoots.any { original.toPath().startsWith(it.toPath()) } || Files.isSameFile(entry.toPath(), original.toPath())) {
                        throw PreserveArchive("no separate original outside app storage")
                    }
                    val before = stamp(original)
                    if (before.size != candidate.size || !equalBytes(entry, original, candidate.size)) {
                        throw PreserveArchive("original differs")
                    }
                    stamps += original to before
                }
                stamps
            } catch (cancelled: InterruptedIOException) {
                throw cancelled
            } catch (_: NoSuchFileException) {
                report("Preserved ${entry.name}: original or copy is missing.")
                preserved++
                continue
            } catch (failure: IOException) {
                report("Preserved ${entry.name}: ${preservedReason(failure)}")
                preserved++
                continue
            }
            report("Verified ${entry.name}; checking references before removal.")
            // Metadata is small. Recheck raw evidence, not just its timestamps, before each unlink.
            if (readEvidence() != evidence) throw IOException("Archive references changed; cleanup stopped.")
            try {
                checkInterrupted()
                if (proof.any { (file, before) -> stamp(file) != before }) {
                    throw PreserveArchive("original or copy changed during verification")
                }
                // stamp() checked the entry and its canonical ancestors immediately before this unlink.
                Files.delete(entry.toPath())
                removed++
                bytes += proof.first().second.size
                report("Removed old DML copy: ${entry.name}")
            } catch (cancelled: InterruptedIOException) {
                throw cancelled
            } catch (failure: IOException) {
                report("Preserved ${entry.name}: ${preservedReason(failure)}")
                preserved++
            }
        }
        return LegacyArchiveCleanupResult(removed, bytes, preserved)
    }

    private data class Evidence(val metadata: Map<File, String?>, val folders: Set<File>)

    private fun readEvidence(): Evidence {
        val folders = selectedFolders().map(::sourceIdentity).toSet()
        val state = File(base, "state")
        val profileDirs = children(File(state, "profiles"))
        val metadata = linkedMapOf<File, String?>()
        for (directory in listOf(state) + profileDirs) {
            val entries = children(directory)
            if (entries.any { it.name.startsWith(".downloaded_archives.json.") && it.name.endsWith(".tmp") }) {
                throw IOException("Unfinished archive metadata present; cleanup stopped.")
            }
            val file = File(directory, "downloaded_archives.json")
            if (file.canonicalFile != file.absoluteFile) throw IOException("Linked archive metadata; cleanup stopped.")
            metadata[file] = CriticalMetadataFile(file).read()
        }
        return Evidence(metadata, folders)
    }

    private fun sourceIdentity(path: String): File {
        val file = File(path)
        if (!file.isAbsolute || path.contains("://")) throw IOException("Unresolved archive source or selection; cleanup stopped.")
        return file.canonicalFile
    }

    private fun children(directory: File): List<File> {
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Linked cleanup directory; cleanup stopped.")
        val attributes = try {
            Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        if (!attributes.isDirectory) throw IOException("Unexpected cleanup directory; cleanup stopped.")
        return directory.listFiles()?.sortedBy { it.name }
            ?: throw IOException("Cannot list cleanup directory; cleanup stopped.")
    }

    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)

    private fun stamp(file: File): Stamp {
        if (file.canonicalFile != file.absoluteFile) throw PreserveArchive("linked file or ancestor")
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw PreserveArchive("not a regular file")
        return Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }

    private fun equalBytes(copy: File, original: File, size: Long): Boolean {
        DataInputStream(copy.inputStream().buffered()).use { left ->
            DataInputStream(original.inputStream().buffered()).use { right ->
                val a = ByteArray(64 * 1024)
                val b = ByteArray(a.size)
                var remaining = size
                while (remaining > 0) {
                    checkInterrupted()
                    val count = minOf(remaining, a.size.toLong()).toInt()
                    left.readFully(a, 0, count)
                    right.readFully(b, 0, count)
                    for (i in 0 until count) if (a[i] != b[i]) return false
                    remaining -= count
                }
                return left.read() == -1 && right.read() == -1
            }
        }
    }

    private class PreserveArchive(reason: String) : IOException(reason)

    private fun preservedReason(failure: IOException): String =
        if (failure is PreserveArchive) failure.message.orEmpty()
        else "could not verify or remove a safe copy (${failure.javaClass.simpleName})"

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive cleanup interrupted.")
    }

    private companion object {
        val KNOWN_FIELDS = setOf(
            "archiveId", "displayName", "fileName", "archiveFormat", "relativePath", "sizeBytes",
            "modifiedAtMillis", "fingerprint", "sourcePath", "sourceUri", "sourceUrl", "nexusGameDomain",
            "nexusModId", "nexusFileId", "nexusFileName", "version", "installedModId", "installedAtMillis", "createdAtMillis"
        )
    }
}
