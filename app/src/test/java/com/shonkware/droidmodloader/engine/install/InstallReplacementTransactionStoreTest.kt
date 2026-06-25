package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

class InstallReplacementTransactionStoreTest {
    private val store =
        InstallReplacementTransactionStore()

    @Test
    fun `creates loads and updates a transaction`() {
        withRoot("create-update") { modsDir ->
            val prepared = transaction(
                state =
                    InstallReplacementState.PREPARED
            )

            val transactionFile = store.create(
                modsDir = modsDir,
                transaction = prepared
            )

            assertTrue(transactionFile.isFile)
            assertEquals(
                prepared,
                store.load(transactionFile)
            )

            val promoted = prepared.withState(
                InstallReplacementState.PROMOTED
            )

            store.update(
                transactionFile = transactionFile,
                transaction = promoted
            )

            assertEquals(
                promoted,
                store.load(transactionFile)
            )
        }
    }

    @Test
    fun `deletes a completed transaction`() {
        withRoot("delete") { modsDir ->
            val transactionFile = store.create(
                modsDir = modsDir,
                transaction = transaction()
            )

            store.delete(transactionFile)

            assertFalse(transactionFile.exists())
        }
    }

    @Test
    fun `lists only complete transaction records`() {
        withRoot("list") { modsDir ->
            val first = store.create(
                modsDir = modsDir,
                transaction = transaction(id = "one")
            )
            val second = store.create(
                modsDir = modsDir,
                transaction = transaction(id = "two")
            )

            File(
                modsDir,
                "${first.name}.tmp"
            ).writeText("partial")
            File(
                modsDir,
                "_dml_transaction_not-a-record.txt"
            ).writeText("ignored")
            File(
                modsDir,
                "_dml_backup_Example_backup"
            ).mkdirs()

            assertEquals(
                listOf(first.name, second.name),
                store.listTransactionFiles(modsDir)
                    .map { it.name }
            )
        }
    }

    @Test
    fun `refuses to overwrite an existing transaction`() {
        withRoot("existing") { modsDir ->
            val transaction = transaction()

            store.create(
                modsDir = modsDir,
                transaction = transaction
            )

            expectIOException {
                store.create(
                    modsDir = modsDir,
                    transaction = transaction
                )
            }
        }
    }

    @Test
    fun `rejects unsafe directory names before writing`() {
        withRoot("unsafe-name") { modsDir ->
            val failure = expectIOException {
                store.create(
                    modsDir = modsDir,
                    transaction = transaction(
                        finalDirectoryName =
                            "../escape"
                    )
                )
            }

            assertTrue(
                failure.message.orEmpty()
                    .contains("Unsafe")
            )
            assertTrue(
                store.listTransactionFiles(modsDir)
                    .isEmpty()
            )
        }
    }

    @Test
    fun `rejects malformed persisted state`() {
        withRoot("malformed") { modsDir ->
            val transactionFile = File(
                modsDir,
                "_dml_transaction_test.properties"
            )
            val properties = Properties().apply {
                setProperty("version", "1")
                setProperty("id", "test")
                setProperty("state", "NOT_A_STATE")
                setProperty(
                    "finalDirectoryName",
                    "ExampleMod"
                )
                setProperty(
                    "stagedDirectoryName",
                    "_installing_ExampleMod_test"
                )
                setProperty(
                    "backupDirectoryName",
                    "_dml_backup_ExampleMod_test"
                )
                setProperty(
                    "hadExistingInstall",
                    "true"
                )
            }

            transactionFile.outputStream().use {
                properties.store(it, "malformed")
            }

            expectIOException {
                store.load(transactionFile)
            }
        }
    }

    @Test
    fun `rejects transaction id that differs from filename`() {
        withRoot("id-mismatch") { modsDir ->
            val original = store.create(
                modsDir = modsDir,
                transaction = transaction(id = "one")
            )
            val renamed = File(
                modsDir,
                "_dml_transaction_two.properties"
            )

            assertTrue(original.renameTo(renamed))

            expectIOException {
                store.load(renamed)
            }
        }
    }

    private fun transaction(
        id: String = "test",
        state: InstallReplacementState =
            InstallReplacementState.PREPARED,
        finalDirectoryName: String = "ExampleMod"
    ): InstallReplacementTransaction {
        return InstallReplacementTransaction(
            id = id,
            state = state,
            finalDirectoryName = finalDirectoryName,
            stagedDirectoryName =
                "_installing_ExampleMod_$id",
            backupDirectoryName =
                "_dml_backup_ExampleMod_$id",
            hadExistingInstall = true
        )
    }

    private fun withRoot(
        name: String,
        action: (File) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-install-transaction-$name"
        ).toFile()
        val modsDir = File(root, "mods").apply {
            check(mkdirs())
        }

        try {
            action(modsDir)
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
}