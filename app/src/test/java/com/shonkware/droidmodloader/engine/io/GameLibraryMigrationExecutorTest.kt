package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMoveOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.SystemCriticalDirectoryMoveOperations
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GameLibraryMigrationExecutorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `two profiles relocate distinct variants reload choices preserve Overwrite and clean only obsolete records`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, listOf("one", "two"), withOverwrite = true)
        val legacyMetadata = fixture.plan.installations.associate { it.installationId to File(it.sourcePath, ".dml_mod.json").readBytes() }
        val result = fixture.executor().execute()
        assertEquals("game", result.gameId)
        assertFalse(result.cleanupPending)
        assertNull(result.cleanupFailure)
        val complete = fixture.store.load()!!
        assertEquals(MigrationExecutionPhase.COMPLETE, complete.phase)
        assertEquals(setOf("one", "two"), complete.reloadedProfileIds.toSet())
        assertTrue(complete.cleanup.all { it.retired })
        assertEquals(2, fixture.publishedLibrary()!!.entries.size)
        val repository = GameModLibraryRepository(fixture.paths, "game")
        assertFalse(repository.loadProfile("one")!!.choices.single().enabled)
        assertTrue(repository.loadProfile("two")!!.choices.single().enabled)
        assertEquals(11, repository.loadProfile("two")!!.choices.single().priority)
        for (installation in fixture.plan.installations) {
            assertFalse(File(installation.sourcePath).exists())
            assertEquals("payload", File(installation.destinationPath, "payload.ini").readText())
            assertArrayEquals(legacyMetadata.getValue(installation.installationId), File(installation.destinationPath, ".dml_mod.json").readBytes())
            assertTrue(fixture.paths.getLegacyProfileInternalDir(installation.profileId).isDirectory)
            val view = fixture.sourceReader.stagedEffectiveSources(fixture.paths, "game", installation.profileId, { true })
            assertTrue(view.paths.isNotEmpty()) // Overwrite survives even for the disabled profile.
        }
        assertProtected(fixture)
        val retry = fixture.executor(directoryMove = forbiddenMove()).execute()
        assertFalse(retry.cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `unknown or insufficient metadata capacity leaves preparation and old payload usable`() {
        for (bytes in listOf<Long?>(null, 0L)) {
            val fixture = MigrationExecutionTestFixture(temporaryFolder)
            val receipt = fixture.receipt.readBytes()
            assertThrows(Exception::class.java) { fixture.executor(availableBytes = { bytes }).execute() }
            assertArrayEquals(receipt, fixture.receipt.readBytes())
            assertEquals("payload", fixture.payload.readText())
            GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "game")
            assertProtected(fixture)
        }
    }

    @Test fun `failed rename resumes old-only intent and uncertain completed rename resumes new-only without repeating move`() {
        for (moveBeforeFailure in listOf(false, true)) {
            val fixture = MigrationExecutionTestFixture(temporaryFolder)
            val move = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
                override fun move(source: File, destination: File) {
                    if (moveBeforeFailure) SystemCriticalDirectoryMoveOperations.move(source, destination)
                    throw IOException("injected rename boundary failure")
                }
            })
            assertThrows(Exception::class.java) { fixture.executor(directoryMove = move).execute() }
            val pending = fixture.store.load()!!
            assertEquals(MigrationExecutionPhase.MOVING, pending.phase)
            assertTrue(pending.entries.single().moveIntended)
            assertFalse(pending.entries.single().moveVerified)
            assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
            val result = fixture.executor(directoryMove = if (moveBeforeFailure) forbiddenMove() else CriticalDirectoryMove()).execute()
            assertFalse(result.cleanupPending)
            assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
            assertProtected(fixture)
        }
    }

    @Test fun `publication failure preserves closed game and resumes normal records without payload moves`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, listOf("one", "two"))
        val failedProfileFile = fixture.paths.getProfileMembershipFile("game", "two")
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                if (target == failedProfileFile) {
                    assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
                    throw IOException("injected profile publication failure")
                }
                SystemCriticalMetadataFileOperations.promote(source, target)
            }
        }
        assertThrows(Exception::class.java) { fixture.executor(operations = operations).execute() }
        assertEquals(MigrationExecutionPhase.PUBLISHING, fixture.store.load()!!.phase)
        assertTrue(fixture.paths.getGameLibraryCatalogueFile("game").exists())
        assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
        assertFalse(fixture.executor(directoryMove = forbiddenMove()).execute().cleanupPending)
        assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
        assertProtected(fixture)
    }

    @Test fun `changed cleanup candidate stays pending and valid shared layout remains readable`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val legacyState = File(fixture.external, "state/profiles/profile/installed_mods.json")
        val first = fixture.executor(deleteOwnedPath = { file ->
            if (file == legacyState) throw IOException("injected cleanup refusal")
            Files.delete(file.toPath())
        }).execute()
        assertTrue(first.cleanupPending)
        assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
        assertNotNull(fixture.publishedLibrary())
        legacyState.writeText("reused current filename; do not delete")
        val retry = fixture.executor(directoryMove = forbiddenMove()).execute()
        assertTrue(retry.cleanupPending)
        assertEquals("reused current filename; do not delete", legacyState.readText())
        assertNotNull(fixture.publishedLibrary())
        assertProtected(fixture)
    }

    @Test fun `death after exact cleanup deletion resumes absence without repeating deletion or moving payload`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val legacyState = File(fixture.external, "state/profiles/profile/installed_mods.json")
        val first = fixture.executor(deleteOwnedPath = { file ->
            Files.delete(file.toPath())
            if (file == legacyState) throw IOException("injected death after deletion")
        }).execute()
        assertTrue(first.cleanupPending)
        assertFalse(legacyState.exists())
        val retry = fixture.executor(directoryMove = forbiddenMove(), deleteOwnedPath = { throw AssertionError("Retried a completed physical deletion") }).execute()
        assertFalse(retry.cleanupPending)
        assertProtected(fixture)
    }

    private fun forbiddenMove() = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
        override fun move(source: File, destination: File) { throw AssertionError("Payload move must not repeat") }
    })

    private fun assertProtected(fixture: MigrationExecutionTestFixture) {
        fixture.protectedFiles.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
    }
}
