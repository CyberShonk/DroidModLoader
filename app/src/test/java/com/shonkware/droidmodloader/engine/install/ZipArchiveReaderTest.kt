package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveReaderTest {
    @Test
    fun `extracts deflated nested and unicode entries`() {
        withFixture("deflated") { fixture ->
            createZip(fixture.archive) { zip ->
                zip.putNextEntry(ZipEntry("Data/"))
                zip.closeEntry()

                zip.putNextEntry(
                    ZipEntry("Data/Caf\u00E9.txt").apply {
                        method = ZipEntry.DEFLATED
                    }
                )
                zip.write(
                    "deflated content".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                zip.closeEntry()
            }

            readArchive(fixture)

            assertTrue(
                File(fixture.outputDir, "Data").isDirectory
            )
            assertEquals(
                "deflated content",
                File(
                    fixture.outputDir,
                    "Data/Caf\u00E9.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `extracts stored entries`() {
        withFixture("stored") { fixture ->
            val content = "stored content".toByteArray(
                StandardCharsets.UTF_8
            )
            val checksum = CRC32().apply {
                update(content)
            }

            createZip(fixture.archive) { zip ->
                val entry = ZipEntry("stored.txt").apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    compressedSize = content.size.toLong()
                    crc = checksum.value
                }

                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }

            readArchive(fixture)

            assertEquals(
                "stored content",
                File(
                    fixture.outputDir,
                    "stored.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `extracts zero byte files`() {
        withFixture("zero-byte") { fixture ->
            createZip(fixture.archive) { zip ->
                zip.putNextEntry(
                    ZipEntry("Data/empty.txt")
                )
                zip.closeEntry()
            }

            readArchive(fixture)

            val extracted = File(
                fixture.outputDir,
                "Data/empty.txt"
            )

            assertTrue(extracted.isFile)
            assertEquals(0L, extracted.length())
        }
    }

    @Test
    fun `empty zip succeeds without producing files`() {
        withFixture("empty") { fixture ->
            createZip(fixture.archive) {
                // A closed ZipOutputStream writes a valid empty ZIP.
            }

            readArchive(fixture)

            assertTrue(
                fixture.outputDir.listFiles()
                    .orEmpty()
                    .isEmpty()
            )
        }
    }

    @Test
    fun `unsafe zip path is rejected by the shared writer`() {
        withFixture("unsafe-path") { fixture ->
            createZip(fixture.archive) { zip ->
                zip.putNextEntry(
                    ZipEntry("../escape.txt")
                )
                zip.write(
                    "escape".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                zip.closeEntry()
            }

            val failure = expectReadFailure {
                readArchive(fixture)
            }

            assertEquals(
                ArchiveReadFailureCode.UNSAFE_ENTRY_PATH,
                failure.code
            )
            assertFalse(
                File(fixture.root, "escape.txt").exists()
            )
        }
    }

    @Test
    fun `duplicate zip destinations are rejected`() {
        withFixture("duplicate") { fixture ->
            createZip(fixture.archive) { zip ->
                zip.putNextEntry(
                    ZipEntry("Data/example.txt")
                )
                zip.write("first".toByteArray())
                zip.closeEntry()

                zip.putNextEntry(
                    ZipEntry("Data//example.txt")
                )
                zip.write("second".toByteArray())
                zip.closeEntry()
            }

            val failure = expectReadFailure {
                readArchive(fixture)
            }

            assertEquals(
                ArchiveReadFailureCode.DUPLICATE_ENTRY_PATH,
                failure.code
            )
            assertEquals(
                "first",
                File(
                    fixture.outputDir,
                    "Data/example.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `zip extraction obeys expanded byte limits`() {
        withFixture("byte-limit") { fixture ->
            createZip(fixture.archive) { zip ->
                zip.putNextEntry(
                    ZipEntry("large.bin")
                )
                zip.write(
                    byteArrayOf(1, 2, 3, 4, 5)
                )
                zip.closeEntry()
            }

            val failure = expectReadFailure {
                readArchive(
                    fixture = fixture,
                    limits = ExtractionLimits(
                        maxEntries = 10,
                        maxFileBytes = 4,
                        maxTotalBytes = 20,
                        maxRelativePathCharacters = 100
                    )
                )
            }

            assertEquals(
                ArchiveReadFailureCode
                    .FILE_SIZE_LIMIT_EXCEEDED,
                failure.code
            )
            assertFalse(
                File(
                    fixture.outputDir,
                    "large.bin"
                ).exists()
            )
        }
    }

    private fun readArchive(
        fixture: Fixture,
        limits: ExtractionLimits = ExtractionLimits()
    ) {
        ZipArchiveReader().read(
            archive = fixture.archive,
            writer = ArchiveEntryWriter(
                outputDir = fixture.outputDir,
                limits = limits,
                debugLog = {}
            )
        )
    }

    private fun createZip(
        archive: File,
        content: (ZipOutputStream) -> Unit
    ) {
        ZipOutputStream(
            archive.outputStream().buffered()
        ).use { zip ->
            content(zip)
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

    private fun withFixture(
        name: String,
        action: (Fixture) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-zip-reader-$name"
        ).toFile()
        val archive = File(root, "example.zip")
        val outputDir = File(root, "output").apply {
            check(mkdirs())
        }

        try {
            action(
                Fixture(
                    root = root,
                    archive = archive,
                    outputDir = outputDir
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: File,
        val archive: File,
        val outputDir: File
    )
}