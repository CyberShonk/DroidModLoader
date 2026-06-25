package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArchiveEntryWriterTest {
    @Test
    fun `writes ordinary directories and files`() {
        withFixture("ordinary") { fixture ->
            val writer = fixture.writer()

            writer.writeDirectory("Data/Textures")
            writer.writeFile(
                "Data/Textures/example.txt"
            ) { output ->
                output.write("example".toByteArray())
            }

            assertTrue(
                File(
                    fixture.outputDir,
                    "Data/Textures"
                ).isDirectory
            )
            assertEquals(
                "example",
                File(
                    fixture.outputDir,
                    "Data/Textures/example.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `blocks parent traversal without writing outside output`() {
        withFixture("traversal") { fixture ->
            val failure = expectReadFailure {
                fixture.writer().writeFile(
                    "../escape.txt"
                ) { output ->
                    output.write("escape".toByteArray())
                }
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
    fun `blocks drive-prefixed archive paths`() {
        withFixture("drive-path") { fixture ->
            val failure = expectReadFailure {
                fixture.writer().writeFile(
                    "C:escape.txt"
                ) { output ->
                    output.write("escape".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode.UNSAFE_ENTRY_PATH,
                failure.code
            )
        }
    }

    @Test
    fun `blocks archive paths containing nul`() {
        withFixture("nul-path") { fixture ->
            val failure = expectReadFailure {
                fixture.writer().writeFile(
                    "Data/bad\u0000name.txt"
                ) { output ->
                    output.write("bad".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode.UNSAFE_ENTRY_PATH,
                failure.code
            )
        }
    }

    @Test
    fun `rejects duplicate normalized paths`() {
        withFixture("duplicate") { fixture ->
            val writer = fixture.writer()

            writer.writeFile("Data/example.txt") {
                it.write("first".toByteArray())
            }

            val failure = expectReadFailure {
                writer.writeFile(
                    "Data//example.txt"
                ) {
                    it.write("second".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode
                    .DUPLICATE_ENTRY_PATH,
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
    fun `rejects case-insensitive path collisions`() {
        withFixture("case-collision") { fixture ->
            val writer = fixture.writer()

            writer.writeFile("Textures/A.dds") {
                it.write("first".toByteArray())
            }

            val failure = expectReadFailure {
                writer.writeFile("textures/a.dds") {
                    it.write("second".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode.CASE_COLLISION,
                failure.code
            )
        }
    }

    @Test
    fun `rejects unicode-normalized path collisions`() {
        withFixture("unicode-collision") { fixture ->
            val writer = fixture.writer()

            writer.writeFile(
                "Data/Caf\u00E9.txt"
            ) {
                it.write("first".toByteArray())
            }

            val failure = expectReadFailure {
                writer.writeFile(
                    "Data/Cafe\u0301.txt"
                ) {
                    it.write("second".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode.CASE_COLLISION,
                failure.code
            )
        }
    }

    @Test
    fun `entry limit rejects additional entries`() {
        withFixture("entry-limit") { fixture ->
            val writer = fixture.writer(
                limits = limits(
                    maxEntries = 1
                )
            )

            writer.writeDirectory("Data")

            val failure = expectReadFailure {
                writer.writeFile("example.txt") {
                    it.write("example".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode
                    .ENTRY_LIMIT_EXCEEDED,
                failure.code
            )
            assertFalse(
                File(
                    fixture.outputDir,
                    "example.txt"
                ).exists()
            )
        }
    }

    @Test
    fun `per-file byte limit removes the partial file`() {
        withFixture("file-limit") { fixture ->
            val writer = fixture.writer(
                limits = limits(
                    maxFileBytes = 4,
                    maxTotalBytes = 20
                )
            )

            val failure = expectReadFailure {
                writer.writeFile("large.bin") {
                    it.write(
                        byteArrayOf(1, 2, 3, 4, 5)
                    )
                }
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

    @Test
    fun `total byte limit rejects later content and removes its partial file`() {
        withFixture("total-limit") { fixture ->
            val writer = fixture.writer(
                limits = limits(
                    maxFileBytes = 6,
                    maxTotalBytes = 6
                )
            )

            writer.writeFile("first.bin") {
                it.write(byteArrayOf(1, 2, 3, 4))
            }

            val failure = expectReadFailure {
                writer.writeFile("second.bin") {
                    it.write(byteArrayOf(5, 6, 7))
                }
            }

            assertEquals(
                ArchiveReadFailureCode
                    .TOTAL_SIZE_LIMIT_EXCEEDED,
                failure.code
            )
            assertTrue(
                File(
                    fixture.outputDir,
                    "first.bin"
                ).isFile
            )
            assertFalse(
                File(
                    fixture.outputDir,
                    "second.bin"
                ).exists()
            )
        }
    }

    @Test
    fun `path length limit rejects the entry before writing`() {
        withFixture("path-limit") { fixture ->
            val writer = fixture.writer(
                limits = limits(
                    maxRelativePathCharacters = 10
                )
            )

            val failure = expectReadFailure {
                writer.writeFile(
                    "12345678901"
                ) {
                    it.write("example".toByteArray())
                }
            }

            assertEquals(
                ArchiveReadFailureCode
                    .PATH_LENGTH_LIMIT_EXCEEDED,
                failure.code
            )
        }
    }

    @Test
    fun `content write failure removes the partial file`() {
        withFixture("write-failure") { fixture ->
            val writer = fixture.writer()

            val failure = expectReadFailure {
                writer.writeFile("partial.bin") {
                    it.write(byteArrayOf(1, 2, 3))
                    throw IOException(
                        "Forced content failure"
                    )
                }
            }

            assertEquals(
                ArchiveReadFailureCode.IO_FAILURE,
                failure.code
            )
            assertFalse(
                File(
                    fixture.outputDir,
                    "partial.bin"
                ).exists()
            )
        }
    }

    @Test
    fun `insufficient storage is rejected before creating the file`() {
        withFixture("insufficient-storage") { fixture ->
            val writer = fixture.writer(
                limits = limits(
                    minimumFreeSpaceBytes = 5,
                    spaceCheckIntervalBytes = 4
                ),
                usableSpaceBytes = { 4L }
            )

            val failure = expectReadFailure {
                writer.writeFile("example.bin") {
                    it.write(byteArrayOf(1))
                }
            }

            assertEquals(
                ArchiveReadFailureCode.INSUFFICIENT_STORAGE,
                failure.code
            )
            assertFalse(
                File(
                    fixture.outputDir,
                    "example.bin"
                ).exists()
            )
        }
    }

    @Test
    fun `space is rechecked during a large file write`() {
        withFixture("space-recheck") { fixture ->
            val probeResults =
                java.util.ArrayDeque<Long>().apply {
                    add(10L)
                    add(3L)
                }

            val writer = fixture.writer(
                limits = limits(
                    maxFileBytes = 20,
                    maxTotalBytes = 20,
                    minimumFreeSpaceBytes = 2,
                    spaceCheckIntervalBytes = 4
                ),
                usableSpaceBytes = {
                    if (probeResults.isEmpty()) {
                        3L
                    } else {
                        probeResults.removeFirst()
                    }
                }
            )

            val failure = expectReadFailure {
                writer.writeFile("large.bin") { output ->
                    output.write(
                        byteArrayOf(1, 2, 3, 4)
                    )
                    output.write(
                        byteArrayOf(5, 6, 7, 8)
                    )
                }
            }

            assertEquals(
                ArchiveReadFailureCode.INSUFFICIENT_STORAGE,
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

    @Test
    fun `unavailable usable space information does not block extraction`() {
        withFixture("unknown-space") { fixture ->
            val writer = fixture.writer(
                usableSpaceBytes = { null }
            )

            writer.writeFile("example.bin") {
                it.write(byteArrayOf(1, 2, 3))
            }

            assertEquals(
                3L,
                File(
                    fixture.outputDir,
                    "example.bin"
                ).length()
            )
        }
    }

    private fun limits(
        maxEntries: Int = 100,
        maxFileBytes: Long = 100,
        maxTotalBytes: Long = 1_000,
        maxRelativePathCharacters: Int = 100,
        minimumFreeSpaceBytes: Long = 0,
        spaceCheckIntervalBytes: Long = 100
    ): ExtractionLimits {
        return ExtractionLimits(
            maxEntries = maxEntries,
            maxFileBytes = maxFileBytes,
            maxTotalBytes = maxTotalBytes,
            maxRelativePathCharacters =
                maxRelativePathCharacters,
            minimumFreeSpaceBytes =
                minimumFreeSpaceBytes,
            spaceCheckIntervalBytes =
                spaceCheckIntervalBytes
        )
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
            "dml-entry-writer-$name"
        ).toFile()
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
                    outputDir = outputDir
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: File,
        val outputDir: File
    ) {
        fun writer(
            limits: ExtractionLimits =
                ExtractionLimits(),
            usableSpaceBytes: (File) -> Long? = {
                Long.MAX_VALUE
            }
        ): ArchiveEntryWriter {
            return ArchiveEntryWriter(
                outputDir = outputDir,
                limits = limits,
                debugLog = {},
                usableSpaceBytes = usableSpaceBytes
            )
        }
    }
}