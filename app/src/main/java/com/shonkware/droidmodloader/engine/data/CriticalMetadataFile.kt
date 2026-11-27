package com.shonkware.droidmodloader.engine.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption

/**
 * Atomic metadata replacement for archive records, deployment journals and operation history.
 *
 * A missing path is not a read failure. Replacement writes a unique sibling, syncs its
 * contents, closes it, then atomically replaces the destination. Before promotion the
 * old destination is untouched; readers see an old or new complete file on supported
 * filesystems. An abandoned sibling is never recovery authority.
 *
 * There is deliberately no non-atomic move fallback: failed ordinary replacement has
 * undefined target state. Unsupported atomic replacement must fail before target mutation.
 * No directory fsync/power-loss durability or cross-process serialization is promised.
 * Callers must serialize read/modify/write and validate existing evidence before replacing.
 */
internal class CriticalMetadataFile(
    private val file: File,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations
) {
    fun read(maxBytes: Int? = null): String? {
        require(maxBytes == null || maxBytes in 1 until Int.MAX_VALUE)
        val bytes = try {
            if (maxBytes == null) operations.read(file) else operations.readBounded(file, maxBytes)
        } catch (_: NoSuchFileException) {
            return null
        } catch (exception: Exception) {
            throw IOException("Could not read critical metadata: ${file.name}", exception)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: Exception) {
            throw IOException("Invalid UTF-8 in critical metadata: ${file.name}", exception)
        }
    }

    /**
     * Opt-in persistence for new critical records. The parent must already exist at
     * its canonical, caller-owned path; callers serialize all access and validate
     * record semantics before saving. Unlike replace(), this checks directory sync
     * before promotion and requires it afterward. A failed promotion or later barrier
     * is uncertain: callers must reread/reconcile, never assume the old bytes remain.
     * Successful filesystem barriers are not a hardware/power-loss qualification.
     */
    fun replaceDurably(text: String, maxBytes: Int) {
        require(maxBytes in 1 until Int.MAX_VALUE)
        var temporary: File? = null
        var mutationMayHaveOccurred = false
        try {
            if (text.length > maxBytes) throw IOException("Metadata exceeds byte limit")
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            if (encoded.remaining() > maxBytes) throw IOException("Metadata exceeds byte limit")
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            val parent = requireCriticalDirectory(
                file.absoluteFile.parentFile ?: throw IOException("Metadata has no parent directory")
            )
            // Refuse unreadable, linked, oversized or invalid UTF-8 evidence. Only
            // absence is fresh; parsing/version validation belongs to the repository.
            read(maxBytes)
            operations.syncDirectory(parent)
            temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
            operations.writeAndSync(temporary, bytes)
            mutationMayHaveOccurred = true
            operations.promote(temporary, file)
            operations.syncDirectory(parent)
        } catch (exception: Exception) {
            throw CriticalStorageException(
                "Could not durably persist critical metadata: ${file.name}",
                mutationMayHaveOccurred,
                exception
            )
        } finally {
            // A leftover private sibling is never authority or a recovery candidate.
            runCatching { temporary?.delete() }
        }
    }

    fun replace(text: String) {
        var temporary: File? = null
        try {
            val parent = file.absoluteFile.parentFile
                ?: throw IOException("Metadata has no parent directory: ${file.name}")
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Could not create metadata directory: $parent")
            }
            temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
            operations.writeAndSync(temporary, text.toByteArray(Charsets.UTF_8))
            operations.promote(temporary, file)
        } catch (exception: Exception) {
            throw IOException("Could not persist critical metadata: ${file.name}", exception)
        } finally {
            // Best effort only. A leftover temporary file is ignored, never loaded/promoted.
            temporary?.delete()
        }
    }
}

internal interface CriticalMetadataFileOperations {
    fun read(file: File): ByteArray
    fun readBounded(file: File, maxBytes: Int): ByteArray = read(file).also {
        if (it.size > maxBytes) throw IOException("Metadata exceeds byte limit")
    }
    fun writeAndSync(file: File, bytes: ByteArray)
    fun promote(source: File, target: File)
    fun syncDirectory(directory: File) {
        throw IOException("Directory persistence is unsupported by these operations")
    }
}

internal object SystemCriticalMetadataFileOperations : CriticalMetadataFileOperations {
    override fun read(file: File): ByteArray {
        val attributes = Files.readAttributes(
            file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        if (!attributes.isRegularFile) throw IOException("Metadata is not a regular file: ${file.name}")
        return Files.readAllBytes(file.toPath())
    }

    override fun readBounded(file: File, maxBytes: Int): ByteArray {
        val attributes = Files.readAttributes(
            file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        if (!attributes.isRegularFile) throw IOException("Metadata is not a regular file: ${file.name}")
        if (attributes.size() > maxBytes) throw IOException("Metadata exceeds byte limit")
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            // Start at the observed length, not the limit: ordinary small journals
            // must not allocate their entire multi-megabyte ceiling on every lookup.
            var buffer = ByteArray(minOf(maxBytes.toLong() + 1, attributes.size() + 1).toInt())
            var count = 0
            while (true) {
                if (count > maxBytes) throw IOException("Metadata exceeds byte limit")
                if (count == buffer.size) {
                    buffer = buffer.copyOf(minOf(maxBytes.toLong() + 1, buffer.size.toLong() * 2).toInt())
                }
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
    }

    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    override fun syncDirectory(directory: File) = syncCriticalDirectory(directory)

    override fun promote(source: File, target: File) {
        Files.move(
            source.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    }
}
