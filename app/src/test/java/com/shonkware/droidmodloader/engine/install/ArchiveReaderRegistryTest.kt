package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArchiveReaderRegistryTest {
    @Test
    fun `production registry selects rar5 without changing other readers`() {
        val registry = ArchiveReaderRegistry()
        assertTrue(registry.findReader(ArchiveFormat.RAR5, "example.zip") is Rar5ArchiveReader)
        assertTrue(registry.findReader(ArchiveFormat.RAR4, "example.rar") is RarArchiveReader)
        assertTrue(registry.findReader(ArchiveFormat.ZIP, "example.zip") is ZipArchiveReader)
        assertTrue(registry.findReader(ArchiveFormat.SEVEN_Z, "example.7z") is SevenZipArchiveReader)
    }

    @Test
    fun `zip signature selects zip reader`() {
        withFixture("zip") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "example.zip",
                bytes = bytes(
                    0x50, 0x4B, 0x03, 0x04,
                    0x00, 0x00, 0x00, 0x00
                )
            )

            assertSame(
                fixture.zipReader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `seven z signature selects seven z reader`() {
        withFixture("seven-z") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "example.7z",
                bytes = bytes(
                    0x37, 0x7A, 0xBC, 0xAF,
                    0x27, 0x1C, 0x00, 0x04
                )
            )

            assertSame(
                fixture.sevenZipReader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `rar4 signature selects rar reader`() {
        withFixture("rar4") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "example.rar",
                bytes = bytes(
                    0x52, 0x61, 0x72, 0x21,
                    0x1A, 0x07, 0x00
                )
            )

            assertSame(
                fixture.rar4Reader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `rar5 signature fails when the reader is unavailable`() {
        withFixture("rar5") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "example.rar",
                bytes = bytes(
                    0x52, 0x61, 0x72, 0x21,
                    0x1A, 0x07, 0x01, 0x00
                )
            )

            val failure = expectReadFailure {
                fixture.registry.findReader(archive)
            }

            assertEquals(
                ArchiveReadFailureCode.UNSUPPORTED_VARIANT,
                failure.code
            )
            assertTrue(
                failure.message.orEmpty().contains("RAR5")
            )
        }
    }

    @Test
    fun `unavailable rar5 reader fails format selection before copying`() {
        withFixture("rar5-format") { fixture ->
            val failure = expectReadFailure {
                fixture.registry.findReader(
                    format = ArchiveFormat.RAR5,
                    archiveName = "example.rar"
                )
            }

            assertEquals(
                ArchiveReadFailureCode.UNSUPPORTED_VARIANT,
                failure.code
            )
            assertTrue(
                failure.message.orEmpty().contains("example.rar")
            )
        }
    }

    @Test
    fun `rar5 signature selects an injected rar5 reader`() {
        withFixture(
            name = "rar5-reader",
            includeRar5Reader = true
        ) { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "example.rar",
                bytes = bytes(
                    0x52, 0x61, 0x72, 0x21,
                    0x1A, 0x07, 0x01, 0x00
                )
            )

            assertSame(
                fixture.rar5Reader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `content signature overrides a misleading extension`() {
        withFixture("mismatched-extension") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "actually-seven-z.zip",
                bytes = bytes(
                    0x37, 0x7A, 0xBC, 0xAF,
                    0x27, 0x1C, 0x00, 0x04
                )
            )

            assertSame(
                fixture.sevenZipReader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `supported content without an extension still selects a reader`() {
        withFixture("no-extension") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "archive",
                bytes = bytes(
                    0x50, 0x4B, 0x03, 0x04,
                    0x00, 0x00, 0x00, 0x00
                )
            )

            assertSame(
                fixture.zipReader,
                fixture.registry.findReader(archive)
            )
        }
    }

    @Test
    fun `known extension with unknown content fails as unsupported`() {
        withFixture("unknown-content") { fixture ->
            val archive = writeArchive(
                root = fixture.root,
                name = "invalid.zip",
                bytes = bytes(
                    0x00, 0x01, 0x02, 0x03,
                    0x04, 0x05, 0x06, 0x07
                )
            )

            val failure = expectProbeFailure {
                fixture.registry.findReader(archive)
            }

            assertEquals(
                ArchiveProbeFailureCode.UNSUPPORTED_FORMAT,
                failure.code
            )
        }
    }

    @Test
    fun `missing archive preserves the probe failure code`() {
        withFixture("missing") { fixture ->
            val missing = File(
                fixture.root,
                "missing.rar"
            )

            val failure = expectProbeFailure {
                fixture.registry.findReader(missing)
            }

            assertEquals(
                ArchiveProbeFailureCode.FILE_NOT_FOUND,
                failure.code
            )
        }
    }

    private fun withFixture(
        name: String,
        includeRar5Reader: Boolean = false,
        action: (Fixture) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-reader-registry-$name"
        ).toFile()

        val zipReader = TestArchiveReader("zip")
        val sevenZipReader = TestArchiveReader("seven-z")
        val rar4Reader = TestArchiveReader("rar4")
        val rar5Reader = TestArchiveReader("rar5")

        val registry = ArchiveReaderRegistry(
            formatProbe = ArchiveFormatProbe(),
            zipReader = zipReader,
            sevenZipReader = sevenZipReader,
            rar4Reader = rar4Reader,
            rar5Reader = rar5Reader.takeIf {
                includeRar5Reader
            }
        )

        try {
            action(
                Fixture(
                    root = root,
                    registry = registry,
                    zipReader = zipReader,
                    sevenZipReader = sevenZipReader,
                    rar4Reader = rar4Reader,
                    rar5Reader = rar5Reader
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeArchive(
        root: File,
        name: String,
        bytes: ByteArray
    ): File {
        return File(root, name).apply {
            writeBytes(bytes)
        }
    }

    private fun bytes(
        vararg values: Int
    ): ByteArray {
        return values
            .map(Int::toByte)
            .toByteArray()
    }

    private fun expectProbeFailure(
        action: () -> Unit
    ): ArchiveFormatProbeException {
        try {
            action()
            throw AssertionError(
                "Expected ArchiveFormatProbeException"
            )
        } catch (exception: ArchiveFormatProbeException) {
            return exception
        }
    }
    private fun expectReadFailure(
        action: () -> Unit
    ): ArchiveReadException {
        try {
            action()
            throw AssertionError(
                "Expected ArchiveReadException"
            )
        } catch (exception: ArchiveReadException) {
            return exception
        }
    }

    private data class Fixture(
        val root: File,
        val registry: ArchiveReaderRegistry,
        val zipReader: ArchiveReader,
        val sevenZipReader: ArchiveReader,
        val rar4Reader: ArchiveReader,
        val rar5Reader: ArchiveReader
    )

    private class TestArchiveReader(
        private val name: String
    ) : ArchiveReader {
        override fun read(
            archive: File,
            writer: ArchiveEntryWriter
        ) {
            throw AssertionError(
                "$name reader should not extract during selection"
            )
        }
    }
}