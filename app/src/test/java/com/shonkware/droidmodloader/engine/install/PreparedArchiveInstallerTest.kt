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

class PreparedArchiveInstallerTest {
    @Test
    fun `successful prepared reinstall replaces the installed mod`() {
        withFixture("successful-reinstall") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                marker = "old"
            )
            val prepared = createPreparedInstall(
                fixture = fixture,
                sourceMarker = "new"
            )
            val installer = installer(fixture)

            val result = installer.finalizeInstall(
                prepared = prepared,
                selection = InstallerSelection(
                    selectedOptionIds = emptySet()
                )
            )

            assertEquals(
                finalDir.canonicalFile,
                result.canonicalFile
            )
            assertEquals(
                "new",
                File(finalDir, "marker.txt").readText()
            )
            assertFalse(
                File(prepared.sessionRootPath).exists()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
        }
    }

    @Test
    fun `missing selected source preserves the installed mod`() {
        withFixture("missing-source") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                marker = "old"
            )
            val prepared = createPreparedInstall(
                fixture = fixture,
                sourceMarker = "new",
                sourcePath = "missing"
            )
            val installer = installer(fixture)

            val failure = expectIOException {
                installer.finalizeInstall(
                    prepared = prepared,
                    selection = InstallerSelection(
                        selectedOptionIds = emptySet()
                    )
                )
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "Failed to finalize archive install"
                )
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertTrue(
                File(prepared.sessionRootPath).exists()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
        }
    }

    @Test
    fun `promotion failure restores the installed mod`() {
        withFixture("promotion-failure") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                marker = "old"
            )
            val prepared = createPreparedInstall(
                fixture = fixture,
                sourceMarker = "new"
            )
            val stagedDir = File(
                fixture.modsDir,
                "_installing_ExampleMod_finalize"
            )
            val operations = FailingPromotionOperations(
                stagedDir = stagedDir,
                finalDir = finalDir
            )
            val replacer =
                InstalledModDirectoryReplacer(
                    operations = operations,
                    replacementId = { "test" },
                    cleanupWarning = {},
                    transactionStore =
                        InstallReplacementTransactionStore()
                )

            val installer = installer(
                fixture = fixture,
                directoryReplacer = replacer
            )

            val failure = expectIOException {
                installer.finalizeInstall(
                    prepared = prepared,
                    selection = InstallerSelection(
                        selectedOptionIds = emptySet()
                    )
                )
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "Failed to finalize archive install"
                )
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertTrue(
                File(prepared.sessionRootPath).exists()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
        }
    }

    @Test
    fun `required and selected options are assembled before replacement`() {
        withFixture("selected-options") { fixture ->
            val finalDir = createInstalledMod(
                modsDir = fixture.modsDir,
                marker = "old"
            )
            val sessionRoot = File(
                fixture.root,
                "prepared-session"
            ).apply {
                check(mkdirs())
            }
            val installRoot = File(
                sessionRoot,
                "content"
            ).apply {
                check(mkdirs())
            }

            File(installRoot, "required").apply {
                check(mkdirs())
                File(this, "required.txt").writeText("required")
            }
            File(installRoot, "optional").apply {
                check(mkdirs())
                File(this, "optional.txt").writeText("optional")
            }
            File(installRoot, "ignored").apply {
                check(mkdirs())
                File(this, "ignored.txt").writeText("ignored")
            }

            val prepared = preparedInstall(
                sessionRoot = sessionRoot,
                installRoot = installRoot,
                options = listOf(
                    InstallerOption(
                        id = "required",
                        name = "Required",
                        sourcePath = "required",
                        destinationPath = "",
                        required = true
                    ),
                    InstallerOption(
                        id = "optional",
                        name = "Optional",
                        sourcePath = "optional",
                        destinationPath = "",
                        selectedByDefault = false
                    ),
                    InstallerOption(
                        id = "ignored",
                        name = "Ignored",
                        sourcePath = "ignored",
                        destinationPath = "",
                        selectedByDefault = false
                    )
                )
            )
            val installer = installer(fixture)

            installer.finalizeInstall(
                prepared = prepared,
                selection = InstallerSelection(
                    selectedOptionIds = setOf("optional")
                )
            )

            assertTrue(
                File(finalDir, "required.txt").isFile
            )
            assertTrue(
                File(finalDir, "optional.txt").isFile
            )
            assertFalse(
                File(finalDir, "ignored.txt").exists()
            )
            assertNoInstallWorkDirectories(fixture.modsDir)
        }
    }

    private fun installer(
        fixture: Fixture,
        directoryReplacer: InstalledModDirectoryReplacer =
            InstalledModDirectoryReplacer(
                operations = TestDirectoryOperations(),
                replacementId = { "backup" },
                cleanupWarning = {},
                transactionStore =
                    InstallReplacementTransactionStore()
            )
    ): PreparedArchiveInstaller {
        return PreparedArchiveInstaller(
            tempDir = fixture.tempDir,
            modsDir = fixture.modsDir,
            directoryReplacer = directoryReplacer,
            operationId = { "finalize" },
            debugLog = {}
        )
    }

    private fun createPreparedInstall(
        fixture: Fixture,
        sourceMarker: String,
        sourcePath: String = "."
    ): PreparedArchiveInstall {
        val sessionRoot = File(
            fixture.root,
            "prepared-session"
        ).apply {
            check(mkdirs())
        }
        val installRoot = File(
            sessionRoot,
            "content"
        ).apply {
            check(mkdirs())
        }

        File(installRoot, "marker.txt").writeText(sourceMarker)

        return preparedInstall(
            sessionRoot = sessionRoot,
            installRoot = installRoot,
            options = listOf(
                InstallerOption(
                    id = "main",
                    name = "Main",
                    sourcePath = sourcePath,
                    destinationPath = "",
                    required = true
                )
            )
        )
    }

    private fun preparedInstall(
        sessionRoot: File,
        installRoot: File,
        options: List<InstallerOption>
    ): PreparedArchiveInstall {
        return PreparedArchiveInstall(
            archivePath = File(
                sessionRoot.parentFile,
                "ExampleMod.zip"
            ).absolutePath,
            archiveName = "ExampleMod.zip",
            modName = "ExampleMod",
            sessionRootPath = sessionRoot.absolutePath,
            extractedRootPath = installRoot.absolutePath,
            installRootPath = installRoot.absolutePath,
            plan = InstallerPlan(
                installerType = InstallerType.SIMPLE,
                displayName = "ExampleMod",
                rootPath = installRoot.absolutePath,
                groups = listOf(
                    InstallerGroup(
                        id = "main",
                        name = "Main",
                        type = InstallerGroupType.SELECT_ANY,
                        options = options
                    )
                )
            )
        )
    }

    private fun createInstalledMod(
        modsDir: File,
        marker: String
    ): File {
        return File(modsDir, "ExampleMod").apply {
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
                            child.name.startsWith("_dml_transaction_")
                }
        )
    }

    private fun withFixture(
        name: String,
        action: (Fixture) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-prepared-installer-$name"
        ).toFile()
        val tempDir = File(root, "temp").apply {
            check(mkdirs())
        }
        val modsDir = File(root, "mods").apply {
            check(mkdirs())
        }

        try {
            action(
                Fixture(
                    root = root,
                    tempDir = tempDir,
                    modsDir = modsDir
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
            throw AssertionError("Expected IOException")
        } catch (exception: IOException) {
            return exception
        }
    }

    private data class Fixture(
        val root: File,
        val tempDir: File,
        val modsDir: File
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
                    "Forced prepared-install promotion failure"
                )
            }

            super.move(source, target)
        }
    }
}