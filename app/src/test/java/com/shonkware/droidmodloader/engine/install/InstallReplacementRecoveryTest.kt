package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallReplacementRecoveryTest {
    private val store =
        InstallReplacementTransactionStore()

    @Test
    fun `prepared first install discards staged content`() {
        withFixture("first-prepared") { fixture ->
            val transaction = transaction(
                state = InstallReplacementState.PREPARED,
                hadExistingInstall = false
            )
            val stagedDir = fixture.directory(
                transaction.stagedDirectoryName,
                "new"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()

            assertEquals(
                InstallReplacementRecoveryAction
                    .DISCARDED_STAGED_INSTALL,
                result.action
            )
            assertFalse(stagedDir.exists())
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `prepared first install recognizes completed promotion`() {
        withFixture("first-promoted") { fixture ->
            val transaction = transaction(
                state = InstallReplacementState.PREPARED,
                hadExistingInstall = false
            )
            val finalDir = fixture.directory(
                transaction.finalDirectoryName,
                "new"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()

            assertEquals(
                InstallReplacementRecoveryAction
                    .COMPLETED_PROMOTED_INSTALL,
                result.action
            )
            assertEquals(
                "new",
                File(finalDir, "marker.txt").readText()
            )
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `prepared replacement restores a backup moved before state update`() {
        withFixture("prepared-backup") { fixture ->
            val transaction = transaction(
                state = InstallReplacementState.PREPARED,
                hadExistingInstall = true
            )
            val backupDir = fixture.directory(
                transaction.backupDirectoryName,
                "old"
            )
            val stagedDir = fixture.directory(
                transaction.stagedDirectoryName,
                "new"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()
            val finalDir = File(
                fixture.modsDir,
                transaction.finalDirectoryName
            )

            assertEquals(
                InstallReplacementRecoveryAction
                    .RESTORED_PREVIOUS_INSTALL,
                result.action
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertFalse(backupDir.exists())
            assertFalse(stagedDir.exists())
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `backup created state restores the previous installation`() {
        withFixture("backup-created") { fixture ->
            val transaction = transaction(
                state =
                    InstallReplacementState.BACKUP_CREATED,
                hadExistingInstall = true
            )
            fixture.directory(
                transaction.backupDirectoryName,
                "old"
            )
            fixture.directory(
                transaction.stagedDirectoryName,
                "new"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()
            val finalDir = File(
                fixture.modsDir,
                transaction.finalDirectoryName
            )

            assertEquals(
                InstallReplacementRecoveryAction
                    .RESTORED_PREVIOUS_INSTALL,
                result.action
            )
            assertEquals(
                "old",
                File(finalDir, "marker.txt").readText()
            )
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `backup created state completes promotion that already moved`() {
        withFixture("promotion-before-state") { fixture ->
            val transaction = transaction(
                state =
                    InstallReplacementState.BACKUP_CREATED,
                hadExistingInstall = true
            )
            val finalDir = fixture.directory(
                transaction.finalDirectoryName,
                "new"
            )
            val backupDir = fixture.directory(
                transaction.backupDirectoryName,
                "old"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()

            assertEquals(
                InstallReplacementRecoveryAction
                    .COMPLETED_PROMOTED_INSTALL,
                result.action
            )
            assertEquals(
                "new",
                File(finalDir, "marker.txt").readText()
            )
            assertFalse(backupDir.exists())
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `promoted state removes the stale backup`() {
        withFixture("promoted") { fixture ->
            val transaction = transaction(
                state = InstallReplacementState.PROMOTED,
                hadExistingInstall = true
            )
            val finalDir = fixture.directory(
                transaction.finalDirectoryName,
                "new"
            )
            val backupDir = fixture.directory(
                transaction.backupDirectoryName,
                "old"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()

            assertEquals(
                InstallReplacementRecoveryAction
                    .COMPLETED_PROMOTED_INSTALL,
                result.action
            )
            assertEquals(
                "new",
                File(finalDir, "marker.txt").readText()
            )
            assertFalse(backupDir.exists())
            assertTrue(fixture.transactions().isEmpty())
        }
    }

    @Test
    fun `ambiguous state is retained for manual recovery`() {
        withFixture("ambiguous") { fixture ->
            val transaction = transaction(
                state = InstallReplacementState.PREPARED,
                hadExistingInstall = false
            )
            val finalDir = fixture.directory(
                transaction.finalDirectoryName,
                "final"
            )
            val stagedDir = fixture.directory(
                transaction.stagedDirectoryName,
                "staged"
            )
            fixture.persist(transaction)

            val result = fixture.recover().single()

            assertFalse(result.recovered)
            assertTrue(
                result.failureMessage.orEmpty().contains(
                    "manual recovery"
                )
            )
            assertTrue(finalDir.exists())
            assertTrue(stagedDir.exists())
            assertEquals(1, fixture.transactions().size)
        }
    }

    @Test
    fun `inspection reports all transaction boundaries without performing recovery`() {
        for (state in InstallReplacementState.entries) for (replacement in listOf(false, true)) {
            withFixture("inspect-$state-$replacement") { fixture ->
                val transaction = transaction(state, replacement)
                fixture.directory(transaction.stagedDirectoryName, "staged")
                fixture.persist(transaction)
                fun snapshot() = fixture.modsDir.walkTopDown().filter { it.isFile }
                    .associate { it.relativeTo(fixture.modsDir).path to it.readText() }
                val before = snapshot()
                val recovery = InstallReplacementRecovery()
                val fact = recovery.inspectAll(fixture.modsDir).single()
                assertEquals(state.name, fact.recordedState)
                assertEquals(replacement, fact.hadExistingInstall)
                assertEquals(false, fact.finalPresent)
                assertEquals(true, fact.stagedPresent)
                assertEquals(false, fact.backupPresent)
                assertEquals(fact, recovery.inspectAll(fixture.modsDir).single())
                assertEquals(before, snapshot())
                if (replacement && state == InstallReplacementState.BACKUP_CREATED) {
                    assertFalse(fixture.recover().single().recovered)
                    assertEquals(before, snapshot())
                }
            }
        }
    }

    @Test
    fun `corrupt and unreadable transaction evidence stays visible without mutation`() {
        withFixture("inspect-invalid") { fixture ->
            val bad = File(fixture.modsDir, "_dml_transaction_bad.properties").apply { writeText("corrupt") }
            val unreadable = File(fixture.modsDir, "_dml_transaction_directory.properties").apply { mkdirs() }
            val recovery = InstallReplacementRecovery()
            val facts = recovery.inspectAll(fixture.modsDir)
            assertEquals(2, facts.size)
            assertTrue(facts.all { it.recordedState == null && it.problem != null })
            assertEquals("corrupt", bad.readText())
            assertTrue(unreadable.isDirectory)
            assertEquals(2, fixture.recover().count { !it.recovered })
            assertEquals("corrupt", bad.readText())
            assertTrue(unreadable.isDirectory)
        }
    }

    @Test
    fun `oversized and excessive transaction evidence is unavailable rather than absent`() {
        withFixture("inspect-limits") { fixture ->
            val large = File(fixture.modsDir, "_dml_transaction_large.properties")
            java.io.RandomAccessFile(large, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
            val recovery = InstallReplacementRecovery()
            assertTrue(recovery.inspectAll(fixture.modsDir).single().problem != null)
            assertFalse(fixture.recover().single().recovered)
            assertEquals(8L * 1024 * 1024 + 1, large.length())
            check(large.delete())
            repeat(1_001) { File(fixture.modsDir, "_dml_transaction_$it.properties").createNewFile() }
            assertTrue(recovery.inspectAll(fixture.modsDir).single().problem!!.contains("1,000 transaction limit"))
            assertEquals(1_001, fixture.transactions().size)
        }
    }

    private fun transaction(
        state: InstallReplacementState,
        hadExistingInstall: Boolean
    ): InstallReplacementTransaction {
        return InstallReplacementTransaction(
            id = "test",
            state = state,
            finalDirectoryName = "ExampleMod",
            stagedDirectoryName =
                "_installing_ExampleMod_test",
            backupDirectoryName =
                "_dml_backup_ExampleMod_test",
            hadExistingInstall = hadExistingInstall
        )
    }

    private fun withFixture(
        name: String,
        action: (Fixture) -> Unit
    ) {
        val root = Files.createTempDirectory(
            "dml-recovery-$name"
        ).toFile()
        val modsDir = File(root, "mods").apply {
            check(mkdirs())
        }

        try {
            action(
                Fixture(
                    modsDir = modsDir,
                    store = store
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val modsDir: File,
        val store: InstallReplacementTransactionStore
    ) {
        fun directory(
            name: String,
            marker: String
        ): File {
            return File(modsDir, name).apply {
                check(mkdirs())
                File(this, "marker.txt").writeText(marker)
            }
        }

        fun persist(
            transaction: InstallReplacementTransaction
        ) {
            store.create(
                modsDir = modsDir,
                transaction = transaction
            )
        }

        fun recover():
                List<InstallReplacementRecoveryResult> {
            return InstallReplacementRecovery()
                .recoverAll(modsDir)
        }

        fun transactions(): List<File> {
            return store.listTransactionFiles(modsDir)
        }
    }
}