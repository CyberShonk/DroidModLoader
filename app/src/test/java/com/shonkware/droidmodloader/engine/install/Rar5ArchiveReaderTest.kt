package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Rar5ArchiveReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun read(
        bytes: ByteArray,
        reader: ArchiveReader = Rar5ArchiveReader(),
        limits: ExtractionLimits = ExtractionLimits(),
        cancellation: InstallCancellationSignal = InstallCancellationSignal.NONE,
        space: (File) -> Long? = { Long.MAX_VALUE }
    ): File {
        val input = temporary.newFile("archive-${temporary.root.listFiles()!!.size}.zip")
        input.writeBytes(bytes)
        val output = temporary.newFolder()
        reader.read(input, ArchiveEntryWriter(output, limits, {}, space, cancellation))
        return output
    }

    private fun refused(code: ArchiveReadFailureCode, action: () -> Unit) {
        val failure = assertThrows(ArchiveReadException::class.java) { action() }
        assertEquals(code, failure.code)
    }

    @Test fun `signature overrides extension and stored Unicode files directories and empty files extract`() {
        val bytes = RarTestArchives.rar5(
            RarTestArchives.entry("empty", directory = true),
            RarTestArchives.entry("Data/雪.txt", "expected bytes".toByteArray()),
            RarTestArchives.entry("Data/zero.bin")
        )
        val probe = temporary.newFile("signature.zip").apply { writeBytes(bytes) }
        val registry = ArchiveReaderRegistry()
        assertTrue(registry.findReader(probe) is Rar5ArchiveReader)
        val out = read(bytes, registry.findReader(probe))
        assertTrue(File(out, "empty").isDirectory)
        assertEquals("expected bytes", File(out, "Data/雪.txt").readText())
        assertEquals(0L, File(out, "Data/zero.bin").length())
        assertTrue(File(out, "Data/zero.bin").isFile)
    }

    @Test fun `RAR4 stored bytes survive the Junrar API upgrade`() {
        val out = read(RarTestArchives.rar4("plain.txt", "unchanged".toByteArray()), RarArchiveReader())
        assertEquals("unchanged", File(out, "plain.txt").readText())
    }

    @Test fun `unsafe entries refuse before output`() {
        for (path in listOf("../outside", "/absolute", "C:/absolute", "a/../../outside", "a\\..\\outside")) {
            refused(ArchiveReadFailureCode.UNSAFE_ENTRY_PATH) {
                read(RarTestArchives.rar5(RarTestArchives.entry(path, byteArrayOf(1))))
            }
        }
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun `empty root names cannot skip solid output accounting`() {
        refused(ArchiveReadFailureCode.UNSUPPORTED_VARIANT) {
            read(RarTestArchives.rar5(RarTestArchives.entry(".", byteArrayOf(1))))
        }
    }

    @Test fun `case and normalized Unicode collisions use existing writer checks`() {
        for (names in listOf("A" to "a", "é" to "e\u0301")) {
            refused(ArchiveReadFailureCode.CASE_COLLISION) {
                read(RarTestArchives.rar5(RarTestArchives.entry(names.first), RarTestArchives.entry(names.second)))
            }
        }
    }

    @Test fun `duplicate names use existing writer checks`() {
        refused(ArchiveReadFailureCode.DUPLICATE_ENTRY_PATH) {
            read(RarTestArchives.rar5(RarTestArchives.entry("same"), RarTestArchives.entry("same")))
        }
    }

    @Test fun `bad CRC missing end and truncated payload refuse`() {
        val valid = RarTestArchives.rar5(RarTestArchives.entry("file", ByteArray(4096) { 1 }))
        for (bad in listOf(valid.copyOf().apply { this[8] = (this[8].toInt() xor 1).toByte() },
            valid.copyOf(valid.size - 8), valid.copyOf(valid.size - 2000))) {
            refused(ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED) { read(bad) }
        }
    }

    @Test fun `corrupt stored payload removes partial file`() {
        val bytes = RarTestArchives.rar5(RarTestArchives.entry("file", ByteArray(4096) { 1 }))
        // The final eight bytes are ENDARC; mutate the preceding payload byte only.
        bytes[bytes.size - 9] = 2
        refused(ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED) { read(bytes) }
        assertNoPartialFile("file")
    }

    @Test fun `dictionary limit is checked before output even for stored entries`() {
        refused(ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED) {
            read(RarTestArchives.rar5(RarTestArchives.entry("file", compression = 13 shl 10)))
        }
    }

    @Test fun `standard method tags accept empty files`() {
        // Header-policy coverage only; empty entries do not qualify compressed payloads.
        for (method in 0..5) {
            val out = read(RarTestArchives.rar5(RarTestArchives.entry("empty", compression = method shl 7)))
            assertTrue(File(out, "empty").isFile)
            assertEquals(0L, File(out, "empty").length())
        }
    }

    @Test fun `newly supported methods retain dictionary and path checks`() {
        for (method in listOf(1, 2, 4)) {
            refused(ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED) {
                read(RarTestArchives.rar5(RarTestArchives.entry("empty", compression = (13 shl 10) or (method shl 7))))
            }
            refused(ArchiveReadFailureCode.UNSAFE_ENTRY_PATH) {
                read(RarTestArchives.rar5(RarTestArchives.entry("../outside", compression = method shl 7)))
            }
        }
    }

    @Test fun `reserved compression methods and newer versions refuse`() {
        for (compression in listOf(6 shl 7, 7 shl 7) + (0..5).map { (it shl 7) or 1 }) {
            refused(ArchiveReadFailureCode.UNSUPPORTED_VARIANT) {
                read(RarTestArchives.rar5(RarTestArchives.entry("file", compression = compression)))
            }
        }
    }

    @Test fun `quick open cache is logged but never used as content or path authority`() {
        val archive = temporary.newFile("cached.rar").apply {
            writeBytes(RarTestArchives.rar5(
                RarTestArchives.entry("canonical.txt", "actual".toByteArray()),
                RarTestArchives.entry("QO", "../outside and conflicting cached names".toByteArray(), type = 3)
            ))
        }
        val out = temporary.newFolder()
        val logs = mutableListOf<String>()
        Rar5ArchiveReader().read(archive, ArchiveEntryWriter(out, ExtractionLimits(), { logs.add(it) }, { Long.MAX_VALUE }))
        assertEquals(listOf("canonical.txt"), out.list()!!.toList())
        assertEquals("actual", File(out, "canonical.txt").readText())
        assertTrue(logs.single().contains("unused QO index"))
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun `misplaced duplicated compressed or oversized quick open records refuse`() {
        val file = RarTestArchives.entry("file")
        val qo = RarTestArchives.entry("QO", type = 3)
        for (bytes in listOf(
            RarTestArchives.rar5(qo, file), RarTestArchives.rar5(file, qo, qo),
            RarTestArchives.rar5(file, RarTestArchives.entry("QO", type = 3, compression = 3 shl 7)),
            RarTestArchives.rar5(file, RarTestArchives.entry("QO", ByteArray((Rar5ArchiveReader.MAX_HEADER_BYTES + 1).toInt()), type = 3))
        )) refused(ArchiveReadFailureCode.UNSUPPORTED_VARIANT) { read(bytes) }
    }

    @Test fun `regular file named QO is still ordinary content`() {
        val out = read(RarTestArchives.rar5(RarTestArchives.entry("QO", "payload".toByteArray())))
        assertEquals("payload", File(out, "QO").readText())
    }

    @Test fun `split service special and unknown entries refuse`() {
        val unsupported = listOf(
            RarTestArchives.entry("service", type = 3),
            RarTestArchives.entry("fifo", attributes = 0x11a4),
            RarTestArchives.header(99, 4, byteArrayOf())
        )
        for (entry in unsupported) refused(ArchiveReadFailureCode.UNSUPPORTED_VARIANT) {
            read(RarTestArchives.rar5(entry))
        }
        for (flag in listOf(8, 16)) refused(ArchiveReadFailureCode.MULTIPART_ARCHIVE) {
            read(RarTestArchives.rar5(RarTestArchives.entry("split", flags = flag)))
        }
    }

    @Test fun `header bytes reads and entry count are independently bounded`() {
        val bytes = RarTestArchives.rar5(RarTestArchives.entry("one"), RarTestArchives.entry("two"))
        refused(ArchiveReadFailureCode.HEADER_LIMIT_EXCEEDED) {
            read(bytes, Rar5ArchiveReader(maxHeaderBytes = 16))
        }
        refused(ArchiveReadFailureCode.HEADER_LIMIT_EXCEEDED) {
            read(bytes, Rar5ArchiveReader(maxHeaderReads = 2))
        }
        refused(ArchiveReadFailureCode.ENTRY_LIMIT_EXCEEDED) {
            read(bytes, Rar5ArchiveReader(maxEntries = 1))
        }
        // Counts all constructor reads, including headers that the backend does not expose as files.
        val flood = RarTestArchives.rar5(*Array(100) { RarTestArchives.header(99, 4, byteArrayOf()) })
        refused(ArchiveReadFailureCode.HEADER_LIMIT_EXCEEDED) {
            read(flood, Rar5ArchiveReader(maxHeaderReads = 16))
        }
    }

    @Test fun `constructor cancellation survives swallowed backend failures`() {
        var checks = 0
        val signal = InstallCancellationSignal { if (++checks >= 16) throw InstallCancelledException() }
        val bytes = RarTestArchives.rar5(*Array(100) { RarTestArchives.header(99, 4, byteArrayOf()) })
        assertThrows(InstallCancelledException::class.java) { read(bytes, cancellation = signal) }
        assertTrue(checks <= 17)
    }

    @Test fun `pre cancellation does not open a missing input`() {
        val controller = InstallCancellationController().apply { cancel() }
        val writer = ArchiveEntryWriter(temporary.newFolder(), ExtractionLimits(), {}, { Long.MAX_VALUE }, controller.signal)
        assertThrows(InstallCancelledException::class.java) {
            Rar5ArchiveReader().read(File(temporary.root, "not-present"), writer)
        }
    }

    @Test fun `writer byte space and path budgets remain authoritative`() {
        val bytes = RarTestArchives.rar5(RarTestArchives.entry("file", ByteArray(4096) { 1 }))
        refused(ArchiveReadFailureCode.FILE_SIZE_LIMIT_EXCEEDED) {
            read(bytes, limits = ExtractionLimits(maxFileBytes = 16))
        }
        assertNoPartialFile("file")
        refused(ArchiveReadFailureCode.INSUFFICIENT_STORAGE) { read(bytes, space = { 0 }) }
        refused(ArchiveReadFailureCode.PATH_LENGTH_LIMIT_EXCEEDED) {
            read(bytes, limits = ExtractionLimits(maxRelativePathCharacters = 2))
        }
        refused(ArchiveReadFailureCode.PATH_DEPTH_LIMIT_EXCEEDED) {
            read(RarTestArchives.rar5(RarTestArchives.entry("a/b/c")), Rar5ArchiveReader(maxPathDepth = 2))
        }
    }

    @Test fun `decode cancellation removes partial file and remains cancellation`() {
        var probes = 0
        var cancel = false
        val bytes = RarTestArchives.rar5(RarTestArchives.entry("file", ByteArray(1 shl 20) { 1 }))
        assertThrows(InstallCancelledException::class.java) {
            read(bytes, limits = ExtractionLimits(spaceCheckIntervalBytes = 1),
                cancellation = InstallCancellationSignal { if (cancel) throw InstallCancelledException() },
                space = { if (++probes >= 2) cancel = true; Long.MAX_VALUE })
        }
        assertNoPartialFile("file")
    }

    @Test fun `latched decode cancellation retains partial cleanup failure`() {
        val input = temporary.newFile("cleanup-failure.rar").apply {
            writeBytes(RarTestArchives.rar5(RarTestArchives.entry("file", ByteArray(1 shl 20) { 1 })))
        }
        val output = temporary.newFolder()
        val cancellation = InstallCancelledException("cancel after output")
        val signal = InstallCancellationSignal {
            if (File(output, "file").length() > 0) {
                check(output.setWritable(false, true))
                throw cancellation
            }
        }
        try {
            val actual = assertThrows(InstallCancelledException::class.java) {
                Rar5ArchiveReader().read(input, ArchiveEntryWriter(output, ExtractionLimits(), {}, { Long.MAX_VALUE }, signal))
            }
            assertSame(cancellation, actual)
            assertTrue("Lost partial-file cleanup failure", actual.suppressed.any {
                it is java.io.IOException && it.message.orEmpty().contains("Could not remove partial")
            })
        } finally {
            check(output.setWritable(true, true))
        }
    }

    private fun assertNoPartialFile(name: String) {
        assertFalse(temporary.root.walkTopDown().any { it.isFile && it.name == name })
    }
}
