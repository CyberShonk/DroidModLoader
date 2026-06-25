package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModInstallerTest {
    @Test
    fun `successful reinstall replaces the installed mod`() {
        withFixture("successful-reinstall") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                modName = "ExampleMod",
                marker = "old"
            )

            val installer = installer(
                fixture = fixture,
                extractArchive = { _, rawRoot ->
                    val dataDir = File(rawRoot, "Data")
                    check(dataDir.mkdirs())
                    File(dataDir, "marker.txt").writeText("new")
                }
            )

            val result = installer.installArchive(fixture.archive)

            assertEquals(
                finalDir.canonicalFile,
                result.canonicalFile
            )
            assertEquals(
                "new",
                File(finalDir, "marker.txt").readText()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
            assertTrue(
                fixture.tempDir.listFiles()
                    .orEmpty()
                    .isEmpty()
            )
        }
    }

    @Test
    fun `extraction failure preserves the installed mod`() {
        withFixture("extraction-failure") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                modName = "ExampleMod",
                marker = "old"
            )

            val installer = installer(
                fixture = fixture,
                extractArchive = { _, rawRoot ->
                    File(rawRoot, "partial.txt").writeText("partial")
                    throw IOException("Forced extraction failure")
                }
            )

            val failure = expectIOException {
                installer.installArchive(fixture.archive)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "Archive install failed"
                )
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
            assertTrue(
                fixture.tempDir.listFiles()
                    .orEmpty()
                    .isEmpty()
            )
        }
    }

    @Test
    fun `promotion failure restores the installed mod and cleans staging`() {
        withFixture("promotion-failure") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                modName = "ExampleMod",
                marker = "old"
            )
            val stagedDir = File(
                fixture.modsDir,
                "_installing_ExampleMod_test"
            )

            val operations = FailingPromotionOperations(
                stagedDir = stagedDir,
                finalDir = finalDir
            )
            val replacer = InstalledModDirectoryReplacer(
                operations = operations,
                replacementId = { "backup" },
                cleanupWarning = {},
                transactionStore =
                    InstallReplacementTransactionStore()
            )
            val installer = installer(
                fixture = fixture,
                extractArchive = { _, rawRoot ->
                    val dataDir = File(rawRoot, "Data")
                    check(dataDir.mkdirs())
                    File(dataDir, "marker.txt").writeText("new")
                },
                directoryReplacer = replacer
            )

            val failure = expectIOException {
                installer.installArchive(fixture.archive)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "Archive install failed"
                )
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertFalse(stagedDir.exists())
            assertNoInstallWorkDirectories(fixture.modsDir)
            assertTrue(
                fixture.tempDir.listFiles()
                    .orEmpty()
                    .isEmpty()
            )
        }
    }

    private fun installer(
        fixture: Fixture,
        extractArchive: (File, File) -> Unit,
        directoryReplacer: InstalledModDirectoryReplacer =
            InstalledModDirectoryReplacer(
                operations = TestDirectoryOperations(),
                replacementId = { "backup" },
                cleanupWarning = {},
                transactionStore =
                    InstallReplacementTransactionStore()
            )
    ): ModInstaller {
        return ModInstaller(
            tempDir = fixture.tempDir,
            modsDir = fixture.modsDir,
            extractArchive = extractArchive,
            directoryReplacer = directoryReplacer,
            installId = { "test" },
            debugLog = {}
        )
    }

    private fun createInstalledMod(
        modsDir: File,
        modName: String,
        marker: String
    ): File {
        return File(modsDir, modName).apply {
            check(mkdirs())
            File(this, "marker.txt").writeText(marker)
        }
    }

    private fun assertNoInstallWorkDirectories(
        modsDir: File
    ) {
        assertTrue(
            modsDir.listFiles()
                .orEmpty()
                .none { child ->
                    child.name.startsWith("_installing_") ||
                            child.name.startsWith("_dml_backup_") ||
                            child.name.startsWith(
                                "_dml_transaction_"
                            )
                }
        )
    }

    private fun withFixture(
        name: String,
        action: (Fixture) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-mod-installer-$name"
        ).toFile()
        val tempDir = File(root, "temp").apply {
            check(mkdirs())
        }
        val modsDir = File(root, "mods").apply {
            check(mkdirs())
        }
        val archive = File(root, "ExampleMod.zip").apply {
            writeText("synthetic archive")
        }

        try {
            action(
                Fixture(
                    root = root,
                    tempDir = tempDir,
                    modsDir = modsDir,
                    archive = archive
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun expectIOException(
        action: () -> Unit
    ): IOException {
        try {
            action()
            throw AssertionError(
                "Expected IOException"
            )
        } catch (exception: IOException) {
            return exception
        }
    }

    private data class Fixture(
        val root: File,
        val tempDir: File,
        val modsDir: File,
        val archive: File
    )

    private open class TestDirectoryOperations :
        InstalledModDirectoryOperations {

        override fun move(
            source: File,
            target: File
        ) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    source.toPath(),
                    target.toPath()
                )
            }
        }

        override fun deleteRecursively(
            target: File
        ): Boolean {
            return target.deleteRecursively()
        }
    }

    private class FailingPromotionOperations(
        private val stagedDir: File,
        private val finalDir: File
    ) : TestDirectoryOperations() {

        override fun move(
            source: File,
            target: File
        ) {
            if (
                source.canonicalFile == stagedDir.canonicalFile &&
                target.canonicalFile == finalDir.canonicalFile
            ) {
                throw IOException(
                    "Forced promotion failure"
                )
            }

            super.move(source, target)
        }
    }
}
