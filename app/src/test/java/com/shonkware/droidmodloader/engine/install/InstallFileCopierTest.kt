package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class InstallFileCopierTest {
    @Test
    fun `copies directory contents recursively`() {
        withFixture("ordinary") { root ->
            val source = File(root, "source").apply {
                check(mkdirs())
            }
            val target = File(root, "target")

            File(source, "Data/Textures").apply {
                check(mkdirs())
            }
            File(
                source,
                "Data/Textures/example.txt"
            ).writeText("example")

            InstallFileCopier.copyRecursively(
                source = source,
                target = target
            )

            assertEquals(
                "example",
                File(
                    target,
                    "Data/Textures/example.txt"
                ).readText()
            )
        }
    }

    @Test
    fun `cancellation removes partial target file`() {
        withFixture("cancelled") { root ->
            val source = File(
                root,
                "source.bin"
            ).apply {
                writeBytes(
                    ByteArray(256 * 1024) { 1 }
                )
            }
            val target = File(
                root,
                "target.bin"
            )

            var checks = 0
            val signal =
                InstallCancellationSignal {
                    checks++

                    if (checks >= 4) {
                        throw InstallCancelledException()
                    }
                }

            expectCancellation {
                InstallFileCopier.copyRecursively(
                    source = source,
                    target = target,
                    cancellationSignal = signal
                )
            }

            assertFalse(target.exists())
        }
    }

    @Test
    fun `pre-cancelled copy creates no destination`() {
        withFixture("pre-cancelled") { root ->
            val source = File(
                root,
                "source.txt"
            ).apply {
                writeText("example")
            }
            val target = File(
                root,
                "target.txt"
            )
            val controller =
                InstallCancellationController()

            controller.cancel()

            expectCancellation {
                InstallFileCopier.copyRecursively(
                    source = source,
                    target = target,
                    cancellationSignal =
                        controller.signal
                )
            }

            assertFalse(target.exists())
            assertTrue(source.isFile)
        }
    }

    @Test
    fun `low space preserves existing destination and source before copy`() {
        withFixture("low-space-existing") { root ->
            val source = File(root, "source").apply { writeText("new content") }
            val target = File(root, "target").apply { writeText("old content") }
            expectInsufficientSpace {
                copyWithSpace(source, target) { 0L }
            }
            assertEquals("old content", target.readText())
            assertEquals("new content", source.readText())
        }
    }

    @Test
    fun `insufficient headroom creates no file or parent`() {
        withFixture("low-space-new") { root ->
            val source = File(root, "source").apply { writeText("example") }
            val target = File(root, "new/target")
            expectInsufficientSpace {
                copyWithSpace(source, target) { headroom + source.length() - 1L }
            }
            assertFalse(target.parentFile!!.exists())
            assertEquals("example", source.readText())
        }
    }

    @Test
    fun `exact headroom boundary permits copy without overflow`() {
        withFixture("headroom-boundary") { root ->
            val source = File(root, "source").apply { writeText("example") }
            val target = File(root, "target")
            copyWithSpace(source, target) { headroom + source.length() }
            assertEquals("example", target.readText())
            copyWithSpace(source, target) { Long.MAX_VALUE }
            assertEquals("example", target.readText())
        }
    }

    @Test
    fun `unknown capacity does not invent a low space failure`() {
        withFixture("unknown-space") { root ->
            val source = File(root, "source").apply { writeText("example") }
            val target = File(root, "target")
            copyWithSpace(source, target) { null }
            assertEquals("example", target.readText())
            copyWithSpace(source, target) { -1L }
            assertEquals("example", target.readText())
        }
    }

    @Test
    fun `capacity loss during copy removes partial file and preserves source`() {
        withFixture("space-loss") { root ->
            val source = File(root, "source").apply { writeBytes(ByteArray(256 * 1024) { 7 }) }
            val target = File(root, "target")
            var probes = 0
            expectInsufficientSpace {
                copyWithSpace(source, target) {
                    probes++
                    if (probes == 1) Long.MAX_VALUE else 0L
                }
            }
            assertEquals(2, probes)
            assertFalse(target.exists())
            assertEquals(256L * 1024, source.length())
            assertTrue(source.readBytes().all { it == 7.toByte() })
        }
    }

    @Test
    fun `recursive copy applies space check to child destination`() {
        withFixture("recursive-space") { root ->
            val source = File(root, "source").apply { mkdirs() }
            val child = File(source, "child").apply { writeText("example") }
            val target = File(root, "target")
            expectInsufficientSpace { copyWithSpace(source, target) { 0L } }
            assertFalse(File(target, "child").exists())
            assertEquals("example", child.readText())
        }
    }

    @Test
    fun `pre-cancelled copy never probes capacity`() {
        withFixture("cancel-before-space") { root ->
            val source = File(root, "source").apply { writeText("example") }
            val target = File(root, "target")
            val controller = InstallCancellationController().apply { cancel() }
            expectCancellation {
                InstallFileCopier.copyRecursively(source, target, controller.signal) {
                    throw AssertionError("Cancelled copy must not probe capacity")
                }
            }
            assertFalse(target.exists())
        }
    }

    private val headroom = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES

    private fun copyWithSpace(source: File, target: File, probe: (File) -> Long?) {
        InstallFileCopier.copyRecursively(
            source, target, InstallCancellationSignal.NONE,
            spaceCheckIntervalBytes = 64 * 1024L,
            usableSpaceBytes = probe
        )
    }

    private fun expectInsufficientSpace(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected insufficient storage failure")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("Not enough free storage"))
        }
    }

    private fun expectCancellation(
        action: () -> Unit
    ): InstallCancelledException {
        try {
            action()
            throw AssertionError(
                "Expected InstallCancelledException"
            )
        } catch (
            exception: InstallCancelledException
        ) {
            return exception
        }
    }

    private fun withFixture(
        name: String,
        action: (File) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-install-copy-$name"
        ).toFile()

        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}