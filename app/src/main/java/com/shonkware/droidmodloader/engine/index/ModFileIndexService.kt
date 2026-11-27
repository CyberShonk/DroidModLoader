package com.shonkware.droidmodloader.engine.index

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.install.ArchiveEntryPath
import com.shonkware.droidmodloader.engine.install.ArchiveEntryPathResult
import com.shonkware.droidmodloader.engine.install.ExtractionLimits
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.ManualLayoutInventory
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.WRITE
import java.text.Normalizer
import java.util.Locale

class ModFileIndexService(
    private val repository: ModFileIndexRepository
) {

    companion object {
        internal const val MAX_SOURCE_ENTRIES = 200_000
        private val sourceLimits = ExtractionLimits(maxEntries = MAX_SOURCE_ENTRIES,
            maxFileBytes = Long.MAX_VALUE, maxTotalBytes = Long.MAX_VALUE)

        internal fun requireMigrationSidecarCapacity(existingEntryCount: Int) {
            require(existingEntryCount in 0 until MAX_SOURCE_ENTRIES) { "Normal source entry limit leaves no room for migration metadata" }
        }

        /** Explicit-role source paths must not strip another Data/ prefix or fold away
         * path evidence. Legacy index/cache behavior below remains unchanged. */
        internal fun normalizedSourcePath(originalPath: String): String {
            require(originalPath.length <= sourceLimits.maxRelativePathCharacters &&
                originalPath.count { it == '/' } < ManualLayoutInventory.MAX_PATH_DEPTH &&
                originalPath.none { it.isISOControl() || it == '\\' || it == ':' }) { "Unsafe source path" }
            require((ArchiveEntryPath.normalize(originalPath) as? ArchiveEntryPathResult.Valid)?.relativePath == originalPath) {
                "Source path is not an exact relative path"
            }
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(originalPath))
            return Normalizer.normalize(originalPath, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        }

        /** Complete bounded in-memory index, never an automatic cache save. No missing,
         * linked, unreadable or partially enumerated tree can become a valid empty list. */
        internal fun indexVerifiedFiles(root: File, signal: InstallCancellationSignal): List<ModFileIndexEntry> {
            requireCriticalDirectory(root)
            val inventory = ManualLayoutInventory.capture(root.parentFile!!, root, signal, sourceLimits)
            val entries = inventory.entries.filterNot { it.directory }.map { entry ->
                signal.throwIfCancellationRequested()
                val normalized = normalizedSourcePath(entry.path)
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(File(root, entry.path).toPath(), NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = entry.sizeBytes
                    while (remaining > 0) {
                        signal.throwIfCancellationRequested()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        check(count > 0) { "Source file changed while indexing" }
                        digest.update(buffer, 0, count); remaining -= count
                    }
                    check(input.read() == -1) { "Source file grew while indexing" }
                }
                ModFileIndexEntry(entry.path, normalized, digest.digest().joinToString("") { "%02x".format(it) },
                    entry.sizeBytes, entry.modifiedAtMillis)
            }
            inventory.requireCurrent(signal)
            return entries.sortedBy { it.normalizedPath }
        }

        /** Publication barrier for an explicitly prepared private source, not capture.
         * Callers verify its complete index before and after persistence under admission. */
        internal fun persistVerifiedFiles(root: File, signal: InstallCancellationSignal, operations: CriticalMetadataFileOperations) {
            requireCriticalDirectory(root)
            val inventory = ManualLayoutInventory.capture(root.parentFile!!, root, signal, sourceLimits)
            inventory.entries.filterNot { it.directory }.forEach { entry ->
                signal.throwIfCancellationRequested()
                FileChannel.open(File(root, entry.path).toPath(), WRITE, NOFOLLOW_LINKS).use { it.force(true) }
            }
            inventory.entries.filter { it.directory }.sortedByDescending { it.path.length }.forEach {
                operations.syncDirectory(File(root, it.path))
            }
            operations.syncDirectory(root)
            inventory.requireCurrent(signal)
        }
    }

    private data class FileSignature(
        val originalPath: String,
        val normalizedPath: String,
        val sizeBytes: Long,
        val modifiedEpochMillis: Long
    )

    fun getOrBuildIndex(mod: Mod): ModFileIndexSnapshot {
        val existing = repository.load(mod)

        if (existing != null && !isDirty(mod, existing)) {
            return existing
        }

        return rebuildIndex(mod)
    }

    fun rebuildIndex(mod: Mod): ModFileIndexSnapshot {
        val root = File(mod.installPath)

        val entries = if (root.exists() && root.isDirectory) {
            scanSignatures(root).values
                .map { signature ->
                    ModFileIndexEntry(
                        originalPath = signature.originalPath,
                        normalizedPath = signature.normalizedPath,
                        hash = computeHash(File(root, signature.originalPath)),
                        sizeBytes = signature.sizeBytes,
                        modifiedEpochMillis = signature.modifiedEpochMillis
                    )
                }
                .sortedBy { it.normalizedPath }
        } else {
            emptyList()
        }

        val snapshot = ModFileIndexSnapshot(
            schemaVersion = ModFileIndexRepository.CURRENT_SCHEMA_VERSION,
            modId = mod.id,
            modName = mod.name,
            modRootPath = mod.installPath,
            createdAtEpochMillis = System.currentTimeMillis(),
            entries = entries
        )

        repository.save(snapshot)
        return snapshot
    }

    fun deleteIndex(mod: Mod) {
        repository.delete(mod)
    }

    private fun isDirty(
        mod: Mod,
        snapshot: ModFileIndexSnapshot
    ): Boolean {
        if (snapshot.schemaVersion != ModFileIndexRepository.CURRENT_SCHEMA_VERSION) {
            return true
        }

        if (snapshot.modId != mod.id) {
            return true
        }

        if (snapshot.modRootPath != mod.installPath) {
            return true
        }

        val root = File(mod.installPath)
        if (!root.exists() || !root.isDirectory) {
            return true
        }

        val current = scanSignatures(root)
        val indexed = snapshot.entries.associateBy { it.normalizedPath }

        if (current.size != indexed.size) {
            return true
        }

        for ((path, currentSignature) in current) {
            val indexedEntry = indexed[path] ?: return true

            if (indexedEntry.sizeBytes != currentSignature.sizeBytes) {
                return true
            }

            if (indexedEntry.modifiedEpochMillis != currentSignature.modifiedEpochMillis) {
                return true
            }

            if (indexedEntry.originalPath != currentSignature.originalPath) {
                return true
            }
        }

        return false
    }

    private fun scanSignatures(root: File): Map<String, FileSignature> {
        val result = linkedMapOf<String, FileSignature>()

        root.walkTopDown()
            .onEnter { dir ->
                val name = dir.name.lowercase()
                name != "__macosx" && !name.startsWith(".")
            }
            .forEach { file ->
                if (!file.isFile) {
                    return@forEach
                }

                val originalPath = file
                    .relativeTo(root)
                    .path
                    .replace("\\", "/")

                val normalizedPath = PathUtils.normalize(originalPath) ?: return@forEach

                result[normalizedPath] = FileSignature(
                    originalPath = originalPath,
                    normalizedPath = normalizedPath,
                    sizeBytes = file.length(),
                    modifiedEpochMillis = file.lastModified()
                )
            }

        return result
    }

    private fun computeHash(file: File): String {
        val buffer = ByteArray(1024 * 64)
        val digest = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}