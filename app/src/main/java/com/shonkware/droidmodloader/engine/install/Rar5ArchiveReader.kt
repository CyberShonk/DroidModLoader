package com.shonkware.droidmodloader.engine.install

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedDictionarySizeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.rarfile.rar5.Rar5BlockType
import com.github.junrar.rarfile.rar5.Rar5MainHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.File
import java.io.IOException

class Rar5ArchiveReader internal constructor(
    private val maxDictionaryBytes: Long = MAX_DICTIONARY_BYTES,
    private val maxHeaderBytes: Long = MAX_HEADER_BYTES,
    private val maxHeaderReads: Int = MAX_HEADER_READS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxPathDepth: Int = MAX_PATH_DEPTH
) : ArchiveReader {
    companion object {
        const val MAX_DICTIONARY_BYTES = 32L * 1024 * 1024
        const val MAX_HEADER_BYTES = 4L * 1024 * 1024
        const val MAX_HEADER_READS = 8192
        const val MAX_ENTRIES = 4096
        const val MAX_PATH_DEPTH = 64
    }

    init {
        require(maxDictionaryBytes in 1..MAX_DICTIONARY_BYTES)
        require(maxHeaderBytes in 1..MAX_HEADER_BYTES)
        require(maxHeaderReads in 1..MAX_HEADER_READS)
        require(maxEntries in 1..MAX_ENTRIES)
        require(maxPathDepth in 1..MAX_PATH_DEPTH)
    }

    override fun read(archive: File, writer: ArchiveEntryWriter) {
        writer.checkCancellation()
        try {
            Rar5ArchiveChannel(
                archive, writer::checkCancellation, maxHeaderBytes, maxHeaderReads
            ).use { channel ->
                try {
                    val volumes = VolumeManager { owner, previous ->
                        if (previous != null) fail(
                            ArchiveReadFailureCode.MULTIPART_ARCHIVE,
                            "Multipart RAR5 archives are not supported."
                        )
                        object : Volume {
                            override fun getChannel() = channel
                            override fun getLength() = channel.length
                            override fun getArchive() = owner
                        }
                    }
                    Archive(
                        volumes,
                        ArchiveOptions.builder().maxDictionarySize(maxDictionaryBytes).build()
                    ).use { session ->
                        channel.failure?.let { throw it }
                        writer.checkCancellation()
                        val entries = validateHeaders(session, writer)
                        channel.finishHeaders()
                        for (entry in entries) {
                            writer.checkCancellation()
                            if (entry.isDirectory) {
                                writer.writeDirectory(entry.fileName)
                            } else {
                                writer.writeFile(entry.fileName) { output ->
                                    // No skipped regular entries: solid replay must never decode
                                    // into Junrar's unmetered null output stream.
                                    session.extractFile(entry, output)
                                }
                            }
                        }
                        writer.checkCancellation()
                    }
                } catch (exception: Exception) {
                    channel.failure?.let { throw preserveSuppressed(it, exception) }
                    throw exception
                }
            }
        } catch (exception: Exception) {
            throw mappedFailure(exception, archive.name)
        }
    }

    private fun validateHeaders(
        session: Archive,
        writer: ArchiveEntryWriter
    ): List<FileHeader> {
        if (session.hasBrokenHeaders()) fail(
            ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED,
            "RAR5 headers are damaged or incomplete."
        )
        val headers = session.headers
        val main = headers.firstOrNull() as? Rar5MainHeader
        val end = headers.lastOrNull() as? Rar5MainHeader
        if (main?.rar5Type != Rar5BlockType.MAIN ||
            end?.rar5Type != Rar5BlockType.ENDARC
        ) fail(
            ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED,
            "RAR5 is missing its main or end header."
        )
        if (session.isEncrypted || session.isPasswordProtected) encrypted()
        val entries = ArrayList<FileHeader>()
        for ((index, header) in headers.withIndex()) {
            writer.checkCancellation()
            when (header) {
                is Rar5MainHeader -> {
                    if (header.isVolume || header.isNextVolume) fail(
                        ArchiveReadFailureCode.MULTIPART_ARCHIVE,
                        "Multipart RAR5 archives are not supported."
                    )
                    if (index != 0 && index != headers.lastIndex) unsupported(
                        "additional archive metadata"
                    )
                }
                is FileHeader -> {
                    if (!header.isFileHeader) {
                        validateQuickOpen(header, index == headers.lastIndex - 1)
                        writer.reportMetadata("RAR5: unused QO index (${header.fullPackSize} bytes); canonical headers used.")
                        continue
                    }
                    if (entries.size >= maxEntries) fail(
                        ArchiveReadFailureCode.ENTRY_LIMIT_EXCEEDED,
                        "RAR5 contains more than $maxEntries entries."
                    )
                    validateEntry(header)
                    entries.add(header)
                }
                else -> unsupported("unknown header types")
            }
        }
        return entries
    }

    private fun validateQuickOpen(header: FileHeader, beforeEnd: Boolean) {
        if (header.isEncrypted) encrypted()
        if (header.isSplitBefore || header.isSplitAfter) fail(
            ArchiveReadFailureCode.MULTIPART_ARCHIVE,
            "Multipart RAR5 metadata is not supported."
        )
        // RAR's format explicitly permits ignoring this optional header cache. Never
        // use its names/offsets for listing or extraction, or decode its payload.
        if (header.fileName != "QO" || !beforeEnd || header.isDirectory ||
            header.redirection != null || header.isUnpSizeUnknown ||
            header.fileVersion != 0L || header.unpVersion.toInt() != 50 ||
            header.unpMethod.toInt() != 0 || header.isSolid ||
            header.fullPackSize !in 0..maxHeaderBytes ||
            header.fullPackSize != header.fullUnpackSize
        ) unsupported("service records other than a bounded stored quick open index")
    }

    private fun validateEntry(header: FileHeader) {
        if (header.isEncrypted) encrypted()
        if (header.isSplitBefore || header.isSplitAfter) fail(
            ArchiveReadFailureCode.MULTIPART_ARCHIVE,
            "Multipart RAR5 entries are not supported."
        )
        if (header.redirection != null) unsupported("links and file references")
        if (header.unpVersion.toInt() != 50 ||
            (header.unpMethod.toInt() and 0xff) !in setOf(0, 3, 5)
        ) unsupported("this compression method or version")
        if (header.isUnpSizeUnknown || header.fileVersion != 0L) unsupported(
            "unknown sizes or versioned files"
        )
        val host = header.rar5HostOsValue
        val unixType = header.fileAttr and 0xf000
        if (host !in 0L..1L ||
            (host == 1L && unixType != 0 &&
                unixType != (if (header.isDirectory) 0x4000 else 0x8000)) ||
            (host == 0L && header.fileAttr and 0x400 != 0)
        ) unsupported("special file types")
        if (header.isDirectory &&
            (header.fullPackSize != 0L || header.fullUnpackSize != 0L)
        ) unsupported("directory payloads")
        if (!header.isDirectory && header.rar5WinSize > maxDictionaryBytes) fail(
            ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED,
            "RAR5 declares a ${header.rar5WinSize}-byte dictionary, above " +
                "DML's $maxDictionaryBytes-byte decoder dictionary limit."
        )
        val path = try {
            ArchiveEntryPath.normalize(header.fileName)
        } catch (exception: IOException) {
            throw ArchiveReadException(
                ArchiveReadFailureCode.UNSAFE_ENTRY_PATH,
                "Blocked unsafe RAR5 entry path: ${header.fileName}", exception
            )
        }
        if (path !is ArchiveEntryPathResult.Valid) unsupported("empty or root-only entry names")
        if (path.relativePath.count { it == '/' } >= maxPathDepth) fail(
            ArchiveReadFailureCode.PATH_DEPTH_LIMIT_EXCEEDED,
            "RAR5 entry exceeds $maxPathDepth path segments: ${header.fileName}"
        )
    }

    private fun mappedFailure(exception: Exception, name: String): Exception {
        // Junrar wraps output failures; retain writer limits, cancellation and cleanup facts.
        val causes = generateSequence<Throwable>(exception) { it.cause }.take(16).toList()
        causes.firstOrNull {
            it is InstallCancelledException || it is ArchiveReadException
        }?.let { return preserveSuppressed(it as Exception, exception) }
        val code = when {
            causes.any { it is UnsupportedDictionarySizeException } ->
                ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED
            causes.any { it is WrongPasswordException || it is UnsupportedRarEncryptedException } ->
                ArchiveReadFailureCode.PASSWORD_PROTECTED_OR_ENCRYPTED
            causes.any { it is java.io.EOFException } -> ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED
            causes.any { it is IOException } -> ArchiveReadFailureCode.IO_FAILURE
            causes.any { it is RarException } -> ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED
            else -> ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED
        }
        val message = when (code) {
            ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED ->
                "RAR5 exceeds DML's $maxDictionaryBytes-byte decoder dictionary limit: $name"
            ArchiveReadFailureCode.PASSWORD_PROTECTED_OR_ENCRYPTED ->
                "Password-protected or encrypted RAR5 archives are not supported: $name"
            ArchiveReadFailureCode.IO_FAILURE -> "Could not read or write RAR5 archive data: $name"
            else -> "RAR5 is corrupt, incomplete or uses an unsupported feature: $name"
        }
        return ArchiveReadException(code, message, exception)
    }

    private fun preserveSuppressed(target: Exception, wrapper: Throwable): Exception {
        for (cause in generateSequence(wrapper) { it.cause }.take(16)) {
            if (cause === target) continue
            for (suppressed in cause.suppressed) {
                if (suppressed !== target && target.suppressed.none { it === suppressed }) {
                    target.addSuppressed(suppressed)
                }
            }
        }
        return target
    }

    private fun encrypted(): Nothing = fail(
        ArchiveReadFailureCode.PASSWORD_PROTECTED_OR_ENCRYPTED,
        "Password-protected or encrypted RAR5 entries are not supported."
    )

    private fun unsupported(feature: String): Nothing = fail(
        ArchiveReadFailureCode.UNSUPPORTED_VARIANT,
        "RAR5 $feature are not supported."
    )

    private fun fail(code: ArchiveReadFailureCode, message: String): Nothing {
        throw ArchiveReadException(code, message)
    }
}
