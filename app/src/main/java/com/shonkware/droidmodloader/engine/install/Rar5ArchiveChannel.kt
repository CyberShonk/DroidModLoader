package com.shonkware.droidmodloader.engine.install

import com.github.junrar.io.SeekableReadOnlyByteChannel
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Owns one managed snapshot. No sibling-volume discovery or backend path writes. */
internal class Rar5ArchiveChannel(
    file: File,
    private val checkCancellation: () -> Unit,
    private val maxHeaderBytes: Long,
    private val maxHeaderReads: Int
) : SeekableReadOnlyByteChannel, Closeable {
    private val input = RandomAccessFile(file, "r")
    val length: Long = try {
        input.length()
    } catch (exception: IOException) {
        try {
            input.close()
        } catch (cleanup: IOException) {
            exception.addSuppressed(cleanup)
        }
        throw exception
    }
    private var readingHeaders = true
    private var headerBytes = 0L
    private var headerReads = 0

    // Junrar may record a channel exception and return a partially parsed archive.
    // This latch, not constructor success, remains authority for that failure.
    var failure: Exception? = null
        private set

    fun finishHeaders() {
        check()
        readingHeaders = false
    }

    private fun check() {
        failure?.let { throw it }
        try {
            checkCancellation()
        } catch (exception: Exception) {
            failure = exception
            throw exception
        }
    }

    private fun beforeRead(count: Int) {
        check()
        if (readingHeaders && (
                headerReads >= maxHeaderReads ||
                    count.toLong() > maxHeaderBytes - headerBytes
                )
        ) {
            val exception = ArchiveReadException(
                ArchiveReadFailureCode.HEADER_LIMIT_EXCEEDED,
                "RAR5 headers exceed DML's safe metadata read budget " +
                    "($maxHeaderBytes bytes or $maxHeaderReads reads)."
            )
            failure = exception
            throw exception
        }
        if (readingHeaders) headerReads++
    }

    private inline fun <T> io(action: () -> T): T {
        try {
            return action()
        } catch (exception: IOException) {
            failure = exception
            throw exception
        }
    }

    override fun getPosition(): Long {
        check()
        return io { input.filePointer }
    }

    override fun setPosition(position: Long) {
        check()
        if (position < 0 || position > length) {
            val exception = ArchiveReadException(
                ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED,
                "RAR5 data extends outside its archive snapshot."
            )
            failure = exception
            throw exception
        }
        io { input.seek(position) }
    }

    override fun read(): Int {
        beforeRead(1)
        val value = io { input.read() }
        if (readingHeaders && value >= 0) headerBytes++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
        beforeRead(count)
        val read = io { input.read(buffer, offset, count) }
        if (readingHeaders && read > 0) headerBytes += read
        return read
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        require(count >= 0 && count <= buffer.size)
        var total = 0
        while (total < count) {
            val read = read(buffer, total, count - total)
            if (read < 0) return if (total == 0) -1 else total
            total += read
        }
        return total
    }

    override fun close() {
        input.close()
    }
}
