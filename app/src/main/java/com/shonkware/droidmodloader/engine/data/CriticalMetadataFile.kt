package com.shonkware.droidmodloader.engine.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
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
            val buffer = ByteArray(maxBytes + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > maxBytes) throw IOException("Metadata exceeds byte limit")
            buffer.copyOf(count)
        }
    }

    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    override fun promote(source: File, target: File) {
        Files.move(
            source.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    }
}
