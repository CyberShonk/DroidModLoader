package com.shonkware.droidmodloader.engine.data

import java.io.File
import java.io.IOException
import java.io.SyncFailedException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException

internal enum class MetadataFault { READ, OPEN_WRITE, WRITE, SYNC, PROMOTE, ATOMIC_UNSUPPORTED }

/** Deterministic denial/interruption; does not depend on host UID or chmod behavior. */
internal class MetadataFaultOperations(private val fault: MetadataFault) :
    CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
    override fun read(file: File): ByteArray {
        if (fault == MetadataFault.READ) throw AccessDeniedException(file.path)
        return SystemCriticalMetadataFileOperations.read(file)
    }

    override fun readBounded(file: File, maxBytes: Int): ByteArray {
        if (fault == MetadataFault.READ) throw AccessDeniedException(file.path)
        return SystemCriticalMetadataFileOperations.readBounded(file, maxBytes)
    }

    override fun writeAndSync(file: File, bytes: ByteArray) {
        if (fault == MetadataFault.OPEN_WRITE) throw AccessDeniedException(file.path)
        if (fault == MetadataFault.WRITE) {
            file.writeBytes(bytes.copyOf(bytes.size / 2))
            throw AccessDeniedException(file.path, null, "Injected partial write failure")
        }
        if (fault == MetadataFault.SYNC) {
            file.writeBytes(bytes)
            throw SyncFailedException("Injected sync failure")
        }
        SystemCriticalMetadataFileOperations.writeAndSync(file, bytes)
    }

    override fun promote(source: File, target: File) {
        when (fault) {
            MetadataFault.PROMOTE -> throw IOException("Injected promotion failure")
            MetadataFault.ATOMIC_UNSUPPORTED -> throw AtomicMoveNotSupportedException(
                source.path, target.path, "Injected unsupported atomic move"
            )
            else -> SystemCriticalMetadataFileOperations.promote(source, target)
        }
    }
}
