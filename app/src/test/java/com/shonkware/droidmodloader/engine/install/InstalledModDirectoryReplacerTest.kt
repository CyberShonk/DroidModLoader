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

class InstalledModDirectoryReplacerTest {
    @Test
    fun `first install promotes the complete staged directory`() {
        withRoot("first-install") { root ->
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val finalDir = File(root, "Example Mod")
            val replacer = replacer()

            val result = replacer.replace(stagedDir, finalDir)

            assertEquals(finalDir.canonicalFile, result.canonicalFile)
            assertEquals("new", File(finalDir, "marker.txt").readText())
            assertFalse(stagedDir.exists())
            assertTrue(findBackups(root).isEmpty())
            assertTrue(findTransactions(root).isEmpty())
        }
    }

    @Test
    fun `successful replacement promotes the new mod and removes the backup`() {
        withRoot("successful-replacement") { root ->
            val finalDir = createModDirectory(
                root = root,
                name = "Example Mod",
                marker = "old"
            )
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val replacer = replacer()

            replacer.replace(stagedDir, finalDir)

            assertEquals("new", File(finalDir, "marker.txt").readText())
            assertFalse(stagedDir.exists())
            assertTrue(findBackups(root).isEmpty())
            assertTrue(findTransactions(root).isEmpty())
        }
    }

    @Test
    fun `promotion failure restores the prior installed mod`() {
        withRoot("promotion-failure") { root ->
            val finalDir = createModDirectory(
                root = root,
                name = "Example Mod",
                marker = "old"
            )
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val operations = TestOperations(
                failMove = { source, target ->
                    source.canonicalFile == stagedDir.canonicalFile &&
                            target.canonicalFile == finalDir.canonicalFile
                }
            )
            val replacer = replacer(operations)

            val failure = expectIOException {
                replacer.replace(stagedDir, finalDir)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "prior installation was restored"
                )
            )
            assertEquals("old", File(finalDir, "marker.txt").readText())
            assertTrue(stagedDir.exists())
            assertTrue(findBackups(root).isEmpty())
            assertTrue(findTransactions(root).isEmpty())
        }
    }

    @Test
    fun `backup failure leaves the existing mod untouched`() {
        withRoot("backup-failure") { root ->
            val finalDir = createModDirectory(
                root = root,
                name = "Example Mod",
                marker = "old"
            )
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val operations = TestOperations(
                failMove = { source, target ->
                    source.canonicalFile == finalDir.canonicalFile &&
                            target.name.startsWith("_dml_backup_")
                }
            )
            val replacer = replacer(operations)

            val failure = expectIOException {
                replacer.replace(stagedDir, finalDir)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "Could not preserve the existing installed mod"
                )
            )
            assertEquals("old", File(finalDir, "marker.txt").readText())
            assertTrue(stagedDir.exists())
            assertTrue(findBackups(root).isEmpty())
            assertTrue(findTransactions(root).isEmpty())
        }
    }

    @Test
    fun `rollback failure retains the recoverable backup`() {
        withRoot("rollback-failure") { root ->
            val finalDir = createModDirectory(
                root = root,
                name = "Example Mod",
                marker = "old"
            )
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val operations = TestOperations(
                failMove = { source, target ->
                    val isPromotion =
                        source.canonicalFile == stagedDir.canonicalFile &&
                                target.canonicalFile == finalDir.canonicalFile

                    val isRestore =
                        source.name.startsWith("_dml_backup_") &&
                                target.canonicalFile == finalDir.canonicalFile

                    isPromotion || isRestore
                }
            )
            val replacer = replacer(operations)

            val failure = expectIOException {
                replacer.replace(stagedDir, finalDir)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "rollback was incomplete"
                )
            )
            assertFalse(finalDir.exists())
            assertTrue(stagedDir.exists())

            val backup = findBackups(root).single()
            assertEquals(
                "old",
                File(backup, "marker.txt").readText()
            )

            val transactionFile =
                findTransactions(root).single()

            val transaction =
                InstallReplacementTransactionStore()
                    .load(transactionFile)

            assertEquals(
                InstallReplacementState.BACKUP_CREATED,
                transaction.state
            )
        }
    }

    @Test
    fun `backup cleanup failure keeps the successful new installation`() {
        withRoot("cleanup-failure") { root ->
            val finalDir = createModDirectory(
                root = root,
                name = "Example Mod",
                marker = "old"
            )
            val stagedDir = createModDirectory(
                root = root,
                name = "_installing_example",
                marker = "new"
            )
            val warnings = mutableListOf<String>()
            val operations = TestOperations(
                failDelete = { target ->
                    target.name.startsWith("_dml_backup_")
                }
            )
            val replacer = replacer(
                operations = operations,
                cleanupWarning = warnings::add
            )

            val result = replacer.replace(stagedDir, finalDir)

            assertEquals(finalDir.canonicalFile, result.canonicalFile)
            assertEquals("new", File(finalDir, "marker.txt").readText())
            assertFalse(stagedDir.exists())

            val backup = findBackups(root).single()
            assertEquals(
                "old",
                File(backup, "marker.txt").readText()
            )
            assertEquals(1, warnings.size)

            val transactionFile =
                findTransactions(root).single()

            val transaction =
                InstallReplacementTransactionStore()
                    .load(transactionFile)

            assertEquals(
                InstallReplacementState.PROMOTED,
                transaction.state
            )
        }
    }

    @Test
    fun `staged and final directories must share the same parent`() {
        withRoot("different-parents") { root ->
            val stagedParent = File(root, "staging").apply {
                check(mkdirs())
            }
            val finalParent = File(root, "mods").apply {
                check(mkdirs())
            }
            val stagedDir = createModDirectory(
                root = stagedParent,
                name = "_installing_example",
                marker = "new"
            )
            val finalDir = File(finalParent, "Example Mod")
            val replacer = replacer()

            val failure = expectIOException {
                replacer.replace(stagedDir, finalDir)
            }

            assertTrue(
                failure.message.orEmpty().contains(
                    "must have the same parent"
                )
            )
            assertTrue(stagedDir.exists())
            assertFalse(finalDir.exists())
            assertTrue(findTransactions(finalParent).isEmpty())
        }
    }

    private fun replacer(
        operations: InstalledModDirectoryOperations =
            DefaultInstalledModDirectoryOperations,
        cleanupWarning: (String) -> Unit = {}
    ): InstalledModDirectoryReplacer {
        return InstalledModDirectoryReplacer(
            operations = operations,
            replacementId = { "test" },
            cleanupWarning = cleanupWarning,
            transactionStore =
                InstallReplacementTransactionStore()
        )
    }

    private fun findTransactions(
        modsDir: File
    ): List<File> {
        return modsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                        file.name.startsWith(
                            "_dml_transaction_"
                        ) &&
                        file.name.endsWith(
                            ".properties"
                        )
            }
    }

    private fun createModDirectory(
        root: File,
        name: String,
        marker: String
    ): File {
        return File(root, name).apply {
            check(mkdirs())
            File(this, "marker.txt").writeText(marker)
        }
    }

    private fun findBackups(
        root: File
    ): List<File> {
        return root.listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("_dml_backup_")
            }
    }

    private fun withRoot(
        name: String,
        action: (File) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-directory-replacer-$name"
        ).toFile()

        try {
            action(root)
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

    private class TestOperations(
        private val failMove: (File, File) -> Boolean = {
                _,
                _ ->
            false
        },
        private val failDelete: (File) -> Boolean = {
            false
        }
    ) : InstalledModDirectoryOperations {
        override fun move(
            source: File,
            target: File
        ) {
            if (failMove(source, target)) {
                throw IOException(
                    "Forced move failure: " +
                            "${source.name} -> ${target.name}"
                )
            }

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
            if (failDelete(target)) {
                return false
            }

            return target.deleteRecursively()
        }
    }
}
