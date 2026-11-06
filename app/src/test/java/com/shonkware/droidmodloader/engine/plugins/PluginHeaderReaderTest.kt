package com.shonkware.droidmodloader.engine.plugins

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

class PluginHeaderReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val reader = PluginHeaderReader()
    private val fixtures = PluginHeaderFixtures
    private var sequence = 0

    private fun read(
        bytes: ByteArray = fixtures.opening(), game: String = "fallout_nv", name: String = "Example.esp",
        request: PluginHeaderRequest = PluginHeaderRequest()
    ): PluginHeaderObservation {
        val file = temporaryFolder.newFolder("source${sequence++}").resolve(name)
        file.writeBytes(bytes)
        val result = reader.read(game, fixtures.source(file), request)
        assertArrayEquals("reader must not write", bytes, file.readBytes())
        return result
    }

    @Test fun HDR01_02_completeZeroAndOrderedMastersAcrossSevenGames() {
        val oblivion = read(fixtures.opening(flags = 1, headerLength = 20), "oblivion")
        assertTrue(oblivion.complete)
        assertEquals(emptyList<String>(), oblivion.declaredMasters)
        assertEquals(true, oblivion.isMaster)
        assertEquals(38, oblivion.openingByteCount)
        assertNull(oblivion.recordHeader!!.formVersion)
        for (game in listOf("skyrim_le", "fallout_3", "fallout_nv", "ttw", "skyrim_se", "fallout_4")) {
            val bytes = fixtures.opening(fixtures.hedr + fixtures.baseMaster + fixtures.data +
                fixtures.subrecord("MAST", "Second.esm\u0000".toByteArray()))
            val result = read(bytes, game)
            assertTrue(result.complete)
            assertEquals(listOf("Base.esm", "Second.esm"), result.declaredMasters)
            assertEquals(PluginRecordHeader(0, 0, 0, 0, 0), result.recordHeader)
            assertEquals(PluginHedr(1f, 0, 0), result.hedr)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, result.openingSha256)
        }
    }

    @Test fun HDR03_05_flagsAndSuffixAreSeparateFromGameClassification() {
        for (game in listOf("oblivion", "skyrim_le", "fallout_3", "fallout_nv", "ttw", "skyrim_se", "fallout_4")) {
            val headerLength = if (game == "oblivion") 20 else 24
            val suffix = read(fixtures.opening(headerLength = headerLength), game, "M.esm")
            assertEquals(game in listOf("skyrim_se", "fallout_4"), suffix.isMaster)
            assertFalse(suffix.recordHeader!!.masterFlag)
            val light = read(fixtures.opening(flags = 0x200, headerLength = headerLength), game)
            assertTrue(light.recordHeader!!.lightFlag)
            assertEquals(false, light.isMaster)
            assertEquals(true, read(fixtures.opening(flags = 1, headerLength = headerLength), game).isMaster)
        }
        val bytes = fixtures.opening()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 0x87654321.toInt()).putInt(16, 7).putShort(20, 44).putShort(22, 9)
        assertEquals(PluginRecordHeader(0, 0x87654321L, 7, 44, 9), read(bytes).recordHeader)
    }

    @Test fun HDR06_07_strictNames() {
        val cafe = byteArrayOf(0x43, 0x61, 0x66, 0xe9.toByte(), 0x2e, 0x65, 0x73, 0x6d, 0)
        assertEquals(listOf("Café.esm"), read(fixtures.opening(fixtures.hedr + fixtures.subrecord("MAST", cafe))).declaredMasters)
        for (name in listOf(byteArrayOf(0), "Base.esm".toByteArray(), "a/b\u0000".toByteArray(),
            "a\\b\u0000".toByteArray(), "a\u0000b\u0000".toByteArray(), "..\u0000".toByteArray(), byteArrayOf(0x81.toByte(), 0))) {
            val result = read(fixtures.opening(fixtures.hedr + fixtures.subrecord("MAST", name)))
            assertEquals(PluginFactFailure.MALFORMED_MASTER, result.failure)
            assertNull(result.declaredMasters)
        }
    }

    @Test fun HDR08_truncationAndUnsignedLengthsNeverBecomeEmptyMasters() {
        val valid = fixtures.opening(fixtures.hedr + fixtures.baseMaster + fixtures.data)
        for (size in listOf(0, 19, 23, valid.size - 1)) {
            assertFalse(read(valid.copyOf(size)).complete)
        }
        val overflow = valid.copyOf()
        ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1)
        assertEquals(PluginFactFailure.OPENING_LIMIT, read(overflow).failure)
        val overrun = fixtures.hedr + byteArrayOf(0x4d, 0x41, 0x53, 0x54, -1, -1)
        assertEquals(PluginFactFailure.MALFORMED_HEADER, read(fixtures.opening(overrun)).failure)
    }

    @Test fun HDR09_11_unknownAndExtendedSubrecordsAreBounded() {
        val body = fixtures.hedr + fixtures.subrecord("ZZZZ", byteArrayOf(17, 34)) +
            fixtures.extended("MAST", "Base.esm\u0000".toByteArray())
        assertEquals(listOf("Base.esm"), read(fixtures.opening(body)).declaredMasters)
        val xxxx = byteArrayOf(0x58, 0x58, 0x58, 0x58, 4, 0, 9, 0, 0, 0)
        for (invalid in listOf(xxxx, xxxx + xxxx, xxxx.copyOf().apply { this[4] = 3 },
            xxxx + byteArrayOf(0x4d, 0x41, 0x53, 0x54, 0, 0), fixtures.hedr.copyOf(17),
            fixtures.hedr.copyOf().apply { this[8] = 0xc0.toByte(); this[9] = 0x7f })) {
            assertEquals(PluginFactFailure.MALFORMED_HEADER, read(fixtures.opening(invalid)).failure)
        }
    }

    @Test fun HDR12_13_unsupportedMissingDuplicateAndSelfFactsStayExplicit() {
        assertEquals(PluginFactFailure.COMPRESSED_HEADER, read(fixtures.opening(flags = 0x40000)).failure)
        assertEquals(PluginFactFailure.UNSUPPORTED_GAME, read(game = "unknown").failure)
        for (signature in listOf("TES3", "GRUP")) {
            val bytes = fixtures.opening()
            signature.toByteArray().copyInto(bytes)
            assertEquals(PluginFactFailure.UNSUPPORTED_HEADER, read(bytes).failure)
        }
        assertEquals(PluginFactFailure.MALFORMED_HEADER, read(fixtures.opening(ByteArray(0))).failure)
        assertEquals(PluginFactFailure.MALFORMED_HEADER, read(fixtures.opening(fixtures.hedr + fixtures.hedr)).failure)
        val duplicate = read(fixtures.opening(fixtures.hedr + fixtures.baseMaster + fixtures.subrecord("MAST", "BASE.ESM\u0000".toByteArray())))
        assertEquals(PluginFactFailure.DUPLICATE_MASTER, duplicate.failure)
        assertEquals(listOf("Base.esm", "BASE.ESM"), duplicate.declaredMasters)
        assertFalse(duplicate.complete)
        assertEquals(PluginFactFailure.SELF_MASTER, read(fixtures.opening(fixtures.hedr + fixtures.baseMaster), name = "BASE.ESM").failure)
    }

    @Test fun BUD01_02_openingAndSubrecordExactCaps() {
        val padding = PluginHeaderRequest.MAX_OPENING_BYTES - 24 - fixtures.hedr.size - 16
        val exact = fixtures.opening(fixtures.hedr + fixtures.extended("ZZZZ", ByteArray(padding)))
        assertEquals(PluginHeaderRequest.MAX_OPENING_BYTES, exact.size)
        assertTrue(read(exact).complete)
        val excess = exact.copyOf(exact.size + 1)
        ByteBuffer.wrap(excess).order(ByteOrder.LITTLE_ENDIAN).putInt(4, excess.size - 24)
        assertEquals(PluginFactFailure.OPENING_LIMIT, read(excess).failure)
        val unknown = fixtures.subrecord("ZZZZ", ByteArray(0))
        val body = fixtures.hedr + ByteArray(unknown.size * 65535).apply {
            repeat(65535) { unknown.copyInto(this, it * unknown.size) }
        }
        assertTrue(read(fixtures.opening(body)).complete)
        assertEquals(PluginFactFailure.SUBRECORD_LIMIT, read(fixtures.opening(body + unknown)).failure)
    }

    @Test fun BUD03_04_masterAndNameExactCaps() {
        assertEquals(4096, read(fixtures.opening(fixtures.masters(4096))).declaredMasters!!.size)
        assertEquals(PluginFactFailure.MASTER_LIMIT, read(fixtures.opening(fixtures.masters(4097))).failure)
        assertTrue(read(fixtures.opening(fixtures.hedr + fixtures.subrecord("MAST", ("x".repeat(255) + "\u0000").toByteArray()))).complete)
        assertEquals(PluginFactFailure.NAME_LIMIT, read(fixtures.opening(fixtures.hedr + fixtures.subrecord("MAST", ("x".repeat(256) + "\u0000").toByteArray()))).failure)
    }

    @Test fun BUD05_07_sharedCandidateByteAndReferenceBudgets() {
        val file = temporaryFolder.newFile("Example.esp").apply { writeBytes(fixtures.opening()) }
        val request = PluginHeaderRequest()
        repeat(8192) { assertTrue(reader.read("fallout_nv", fixtures.source(file), request).complete) }
        assertEquals(8192, request.candidatesRead)
        assertEquals(PluginFactFailure.CANDIDATE_LIMIT, reader.read("fallout_nv", fixtures.source(file), request).failure)
        val byteRequest = PluginHeaderRequest()
        val padding = PluginHeaderRequest.MAX_OPENING_BYTES - 24 - fixtures.hedr.size - 16
        file.writeBytes(fixtures.opening(fixtures.hedr + fixtures.extended("ZZZZ", ByteArray(padding))))
        repeat(4) { assertTrue(reader.read("fallout_nv", fixtures.source(file), byteRequest).complete) }
        assertEquals(33554432L, byteRequest.headerBytesRead)
        assertEquals(PluginFactFailure.BYTE_LIMIT, reader.read("fallout_nv", fixtures.source(file), byteRequest).failure)
        assertEquals(33554432L, byteRequest.headerBytesRead)
        val referenceRequest = PluginHeaderRequest()
        file.writeBytes(fixtures.opening(fixtures.masters(4096)))
        repeat(16) { assertTrue(reader.read("fallout_nv", fixtures.source(file), referenceRequest).complete) }
        assertEquals(65536, referenceRequest.referencesRead)
        assertEquals(PluginFactFailure.REFERENCE_LIMIT, reader.read("fallout_nv", fixtures.source(file), referenceRequest).failure)
    }

    @Test fun HDR14_SRC06_canceledOutsideRootSymlinkAndChangedSource() {
        assertEquals(PluginFactFailure.CANCELED, read(request = PluginHeaderRequest { true }).failure)
        val file = temporaryFolder.newFile("Example.esp").apply { writeBytes(fixtures.opening()) }
        val outsideRoot = temporaryFolder.newFolder("elsewhere")
        assertEquals(PluginFactFailure.UNSAFE_SOURCE, reader.read("fallout_nv", fixtures.source(file, outsideRoot), PluginHeaderRequest()).failure)
        val link = temporaryFolder.root.resolve("Link.esp")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        assertEquals(PluginFactFailure.UNSAFE_SOURCE, reader.read("fallout_nv", fixtures.source(link), PluginHeaderRequest()).failure)
        val mtime = Files.getLastModifiedTime(file.toPath())
        lateinit var request: PluginHeaderRequest
        var changed = false
        request = PluginHeaderRequest {
            if (!changed && request.headerBytesRead == file.length()) {
                changed = true
                file.writeBytes(fixtures.opening(flags = 1))
                Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(mtime.toMillis()))
            }
            false
        }
        assertEquals(PluginFactFailure.SOURCE_CHANGED, reader.read("fallout_nv", fixtures.source(file), request).failure)
        assertTrue(changed)
        val invalid = fixtures.source(file).copy(sourceFilePath = file.absolutePath + "\u0000")
        assertEquals(PluginFactFailure.UNSAFE_SOURCE, reader.read("fallout_nv", invalid, PluginHeaderRequest()).failure)
        val missing = temporaryFolder.root.resolve("Missing.esp")
        assertEquals(PluginFactFailure.IO_ERROR, reader.read("fallout_nv", fixtures.source(missing), PluginHeaderRequest()).failure)
    }

    @Test fun BUD06_nextSingleByteCannotExceedRequestCap() {
        val request = PluginHeaderRequest()
        request.permitBytes(33554432)
        request.recordBytes(33554432)
        try {
            request.permitBytes(1)
            fail("cap plus one must be refused")
        } catch (failure: PluginFactException) {
            assertEquals(PluginFactFailure.BYTE_LIMIT, failure.failure)
        }
        assertEquals(33554432L, request.headerBytesRead)
    }

    @Test fun openingOnlyAndCancellationBetweenReads() {
        val opening = fixtures.opening()
        val request = PluginHeaderRequest()
        assertTrue(read(opening + ByteArray(100000), request = request).complete)
        assertEquals(opening.size * 2L, request.headerBytesRead)
        lateinit var canceled: PluginHeaderRequest
        canceled = PluginHeaderRequest { canceled.headerBytesRead >= 24 }
        assertEquals(PluginFactFailure.CANCELED, read(request = canceled).failure)
        assertEquals(24L, canceled.headerBytesRead)
    }
}
