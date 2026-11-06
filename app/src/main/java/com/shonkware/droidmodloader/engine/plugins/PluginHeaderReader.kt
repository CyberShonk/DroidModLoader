package com.shonkware.droidmodloader.engine.plugins

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Reads only the opening TES4 record, from an already selected source. No resolver or writes. */
class PluginHeaderReader {
    fun read(gameId: String, source: PluginHeaderSource, request: PluginHeaderRequest): PluginHeaderObservation {
        var observation = PluginHeaderObservation(
            gameId, source, pluginIdentity(File(source.sourceFilePath).extension)
        )
        try {
            request.takeCandidate()
            val headerLength = when (gameId) {
                "oblivion" -> 20
                "skyrim_le", "fallout_3", "fallout_nv", "ttw", "skyrim_se", "fallout_4" -> 24
                else -> fail(PluginFactFailure.UNSUPPORTED_GAME)
            }
            val path = validateSource(source)
            val before = attributes(path)
            if (!before.isRegularFile) fail(PluginFactFailure.UNSAFE_SOURCE)
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val headerBytes = ByteArray(headerLength)
                readExactly(channel, headerBytes, 0, headerLength, request)
                val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                if (signature(header, 0) != "TES4") fail(PluginFactFailure.UNSUPPORTED_HEADER)
                val bodyLength = unsignedInt(header, 4)
                val totalLength = headerLength.toLong() + bodyLength
                if (totalLength > PluginHeaderRequest.MAX_OPENING_BYTES) fail(PluginFactFailure.OPENING_LIMIT)
                if (totalLength > before.size()) fail(PluginFactFailure.MALFORMED_HEADER)
                val record = PluginRecordHeader(
                    unsignedInt(header, 8), unsignedInt(header, 12), unsignedInt(header, 16),
                    if (headerLength == 24) unsignedShort(header, 20) else null,
                    if (headerLength == 24) unsignedShort(header, 22) else null
                )
                observation = observation.copy(recordHeader = record)
                if (record.flags and 0x40000L != 0L) fail(PluginFactFailure.COMPRESSED_HEADER)
                request.permitBytes(bodyLength.toInt()) // Refuse before allocating the declared body.
                val opening = headerBytes.copyOf(totalLength.toInt())
                readExactly(channel, opening, headerLength, bodyLength.toInt(), request)

                // Compare the same bounded bytes again, not just size/mtime. Both reads consume budget.
                channel.position(0)
                val verification = ByteArray(8192)
                var offset = 0
                while (offset < opening.size) {
                    val count = minOf(verification.size, opening.size - offset)
                    readExactly(channel, verification, 0, count, request)
                    for (index in 0 until count) {
                        if (verification[index] != opening[offset + index]) fail(PluginFactFailure.SOURCE_CHANGED)
                    }
                    offset += count
                }
                request.checkCancellation()
                if (validateSource(source) != path || !sameFile(before, attributes(path)) || channel.size() != before.size()) {
                    fail(PluginFactFailure.SOURCE_CHANGED)
                }
                observation = observation.copy(
                    openingByteCount = opening.size,
                    openingSha256 = MessageDigest.getInstance("SHA-256").digest(opening)
                        .joinToString("") { "%02x".format(it) }
                )
                val parsed = parse(opening, headerLength, request)
                observation = observation.copy(
                    hedr = parsed.first, declaredMasters = immutablePluginFacts(parsed.second)
                )
                val identities = parsed.second.map(::pluginIdentity)
                observation = observation.copy(failure = when {
                    identities.distinct().size != identities.size -> PluginFactFailure.DUPLICATE_MASTER
                    pluginIdentity(File(source.sourceFilePath).name) in identities -> PluginFactFailure.SELF_MASTER
                    else -> null
                })
            }
        } catch (failure: PluginFactException) {
            observation = observation.copy(failure = failure.failure)
        } catch (_: IOException) {
            observation = observation.copy(failure = PluginFactFailure.IO_ERROR)
        } catch (_: java.nio.file.InvalidPathException) {
            observation = observation.copy(failure = PluginFactFailure.UNSAFE_SOURCE)
        } catch (_: SecurityException) {
            observation = observation.copy(failure = PluginFactFailure.IO_ERROR)
        }
        return observation
    }

    private fun parse(
        opening: ByteArray, headerLength: Int, request: PluginHeaderRequest
    ): Pair<PluginHedr, List<String>> {
        val buffer = ByteBuffer.wrap(opening).order(ByteOrder.LITTLE_ENDIAN)
        var position = headerLength
        var subrecords = 0
        var extendedLength: Long? = null
        var hedr: PluginHedr? = null
        val masters = mutableListOf<String>()
        while (position < opening.size) {
            request.checkCancellation()
            if (subrecords == PluginHeaderRequest.MAX_SUBRECORDS) fail(PluginFactFailure.SUBRECORD_LIMIT)
            subrecords++
            if (opening.size - position < 6) fail(PluginFactFailure.MALFORMED_HEADER)
            val kind = signature(buffer, position)
            val shortLength = unsignedShort(buffer, position + 4)
            position += 6
            if (kind == "XXXX") {
                if (extendedLength != null || shortLength != 4 || opening.size - position < 4) {
                    fail(PluginFactFailure.MALFORMED_HEADER)
                }
                extendedLength = unsignedInt(buffer, position)
                position += 4
                continue
            }
            val length = extendedLength ?: shortLength.toLong()
            extendedLength = null
            if (length > opening.size - position) fail(PluginFactFailure.MALFORMED_HEADER)
            when (kind) {
                "HEDR" -> {
                    if (hedr != null || length != 12L) fail(PluginFactFailure.MALFORMED_HEADER)
                    val version = buffer.getFloat(position)
                    if (!version.isFinite()) fail(PluginFactFailure.MALFORMED_HEADER)
                    hedr = PluginHedr(version, unsignedInt(buffer, position + 4), unsignedInt(buffer, position + 8))
                }
                "MAST" -> {
                    if (masters.size == PluginHeaderRequest.MAX_MASTERS) fail(PluginFactFailure.MASTER_LIMIT)
                    if (length > PluginHeaderRequest.MAX_NAME_BYTES) fail(PluginFactFailure.NAME_LIMIT)
                    request.takeReference() // Count declarations before identity deduplication.
                    masters.add(decodeMaster(opening, position, length.toInt()))
                }
            }
            position += length.toInt()
        }
        if (extendedLength != null || hedr == null) fail(PluginFactFailure.MALFORMED_HEADER)
        return hedr to masters
    }

    private fun decodeMaster(bytes: ByteArray, offset: Int, length: Int): String {
        if (length < 2 || bytes[offset + length - 1] != 0.toByte()) fail(PluginFactFailure.MALFORMED_MASTER)
        val decoder = Charset.forName("windows-1252").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val name = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length - 1)).toString()
        } catch (_: CharacterCodingException) {
            fail(PluginFactFailure.MALFORMED_MASTER)
        }
        if (name == "." || name == ".." || name.any { it.isISOControl() || it in "/\\:" }) {
            fail(PluginFactFailure.MALFORMED_MASTER)
        }
        return name
    }

    private fun readExactly(
        channel: SeekableByteChannel, bytes: ByteArray, offset: Int, length: Int, request: PluginHeaderRequest
    ) {
        var consumed = 0
        while (consumed < length) {
            val count = minOf(8192, length - consumed)
            request.permitBytes(count)
            val read = channel.read(ByteBuffer.wrap(bytes, offset + consumed, count))
            if (read <= 0) fail(PluginFactFailure.MALFORMED_HEADER)
            request.recordBytes(read)
            consumed += read
        }
    }

    private fun unsignedInt(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    private fun unsignedShort(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    private fun signature(buffer: ByteBuffer, offset: Int): String =
        (0 until 4).map { buffer.get(offset + it).toInt().toChar() }.joinToString("")

    private fun sameFile(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        after.isRegularFile && before.size() == after.size() && before.fileKey() == after.fileKey() &&
            before.lastModifiedTime() == after.lastModifiedTime() && before.creationTime() == after.creationTime()

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun fail(failure: PluginFactFailure): Nothing = throw PluginFactException(failure)

    internal companion object {
        /** Reject escapes and symbolic links, including links in parent components. */
        fun validateSource(source: PluginHeaderSource): Path {
            val root = File(source.ownedRootPath).toPath().toAbsolutePath().normalize()
            val path = File(source.sourceFilePath).toPath().toAbsolutePath().normalize()
            if (path == root || !path.startsWith(root) || root.toRealPath() != root || path.toRealPath() != path) {
                throw PluginFactException(PluginFactFailure.UNSAFE_SOURCE)
            }
            return path
        }
    }
}
