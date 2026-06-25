package com.shonkware.droidmodloader.engine.install

import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SevenZipArchiveReaderTest {
    @Test
    fun `extracts lzma2 nested and unicode entries`() {
        withFixture("lzma2") { fixture ->
            createSevenZ(
                archive = fixture.archive,
                method = SevenZMethod.LZMA2
            ) {
                directory("Data/")
                directory("Data/Textures/")
                file(
                    entryName = "Data/Caf\u00E9.txt",
                    content = "lzma2 content".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            }

            readArchive(fixture)

            assertTrue(
                File(
                    fixture.outputDir,
                    "Data"
                ).isDirectory
            )
            assertTrue(
                File(
                    fixture.outputDir,
                    "Data/Textures"
                ).isDirectory
            )
            assertEquals(
                "lzma2 content",
                File(
                    fixture.outputDir,
                    "Data/Caf\u00E9.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `extracts copy method entries`() {
        withFixture("copy") { fixture ->
            createSevenZ(
                archive = fixture.archive,
                method = SevenZMethod.COPY
            ) {
                file(
                    entryName = "Data/copied.txt",
                    content = "copy content".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            }

            readArchive(fixture)

            assertEquals(
                "copy content",
                File(
                    fixture.outputDir,
                    "Data/copied.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `extracts zero byte files`() {
        withFixture("zero-byte") { fixture ->
            createSevenZ(
                archive = fixture.archive
            ) {
                file(
                    entryName = "Data/empty.txt",
                    content = byteArrayOf()
                )
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
    fun `empty seven z succeeds without producing files`() {
        withFixture("empty") { fixture ->
            createSevenZ(
                archive = fixture.archive
            ) {
                // A closed SevenZOutputFile writes a valid empty archive.
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
    fun `unsafe seven z path is rejected by the shared writer`() {
        withFixture("unsafe-path") { fixture ->
            createSevenZ(
                archive = fixture.archive
            ) {
                file(
                    entryName = "../escape.txt",
                    content = "escape".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            }

            val failure = expectReadFailure {
                readArchive(fixture)
            }

            assertEquals(
                ArchiveReadFailureCode.UNSAFE_ENTRY_PATH,
                failure.code
            )
            assertFalse(
                File(
                    fixture.root,
                    "escape.txt"
                ).exists()
            )
        }
    }

    @Test
    fun `duplicate seven z destinations are rejected`() {
        withFixture("duplicate") { fixture ->
            createSevenZ(
                archive = fixture.archive
            ) {
                file(
                    entryName = "Data/example.txt",
                    content = "first".toByteArray()
                )
                file(
                    entryName = "Data//example.txt",
                    content = "second".toByteArray()
                )
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
    fun `seven z extraction obeys expanded byte limits`() {
        withFixture("byte-limit") { fixture ->
            createSevenZ(
                archive = fixture.archive
            ) {
                file(
                    entryName = "large.bin",
                    content = byteArrayOf(
                        1,
                        2,
                        3,
                        4,
                        5
                    )
                )
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
        limits: ExtractionLimits =
            ExtractionLimits()
    ) {
        SevenZipArchiveReader().read(
            archive = fixture.archive,
            writer = ArchiveEntryWriter(
                outputDir = fixture.outputDir,
                limits = limits,
                debugLog = {}
            )
        )
    }

    private fun createSevenZ(
        archive: File,
        method: SevenZMethod =
            SevenZMethod.LZMA2,
        content: SevenZFixtureBuilder.() -> Unit
    ) {
        val sourceRoot = File(
            archive.parentFile,
            "seven-z-sources-${archive.nameWithoutExtension}"
        ).apply {
            check(mkdirs())
        }

        try {
            SevenZOutputFile(archive).use { sevenZ ->
                sevenZ.setContentCompression(method)

                SevenZFixtureBuilder(
                    archive = sevenZ,
                    sourceRoot = sourceRoot
                ).content()
            }
        } finally {
            sourceRoot.deleteRecursively()
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
            "dml-seven-z-reader-$name"
        ).toFile()
        val archive = File(
            root,
            "example.7z"
        )
        val outputDir = File(
            root,
            "output"
        ).apply {
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

    private class SevenZFixtureBuilder(
        private val archive: SevenZOutputFile,
        private val sourceRoot: File
    ) {
        private var sourceId = 0

        fun directory(entryName: String) {
            val source = File(
                sourceRoot,
                "directory-${sourceId++}"
            ).apply {
                check(mkdirs())
            }

            val entry = archive.createArchiveEntry(
                source,
                entryName
            )

            archive.putArchiveEntry(entry)
            archive.closeArchiveEntry()
        }

        fun file(
            entryName: String,
            content: ByteArray
        ) {
            val source = File(
                sourceRoot,
                "file-${sourceId++}"
            ).apply {
                writeBytes(content)
            }

            val entry = archive.createArchiveEntry(
                source,
                entryName
            )

            archive.putArchiveEntry(entry)

            if (content.isNotEmpty()) {
                archive.write(content)
            }

            archive.closeArchiveEntry()
        }
    }
}