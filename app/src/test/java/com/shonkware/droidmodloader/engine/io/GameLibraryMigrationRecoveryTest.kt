package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMoveOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.SystemCriticalDirectoryMoveOperations
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import com.shonkware.droidmodloader.engine.install.LibraryInstallReplacement
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class GameLibraryMigrationRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `receipt faults before and after each durable milestone resume without repeated payload relocation`() {
        val milestones = listOf<Pair<String, (JSONObject) -> Boolean>>(
            "prepared" to { it.getString("phase") == "PREPARED" },
            "move intent" to { it.getString("phase") == "MOVING" && it.getJSONArray("entries").getJSONObject(0).let { entry -> entry.getBoolean("moveIntended") && !entry.getBoolean("moveVerified") } },
            "move verified" to { it.getString("phase") == "MOVING" && it.getJSONArray("entries").getJSONObject(0).let { entry -> entry.getBoolean("moveVerified") && !entry.getBoolean("metadataIntended") } },
            "metadata intent" to { it.getString("phase") == "MOVING" && it.getJSONArray("entries").getJSONObject(0).let { entry -> entry.getBoolean("metadataIntended") && !entry.getBoolean("metadataVerified") } },
            "metadata verified" to { it.getString("phase") == "MOVING" && it.getJSONArray("entries").getJSONObject(0).getBoolean("metadataVerified") },
            "publication" to { it.getString("phase") == "PUBLISHING" },
            "reload" to { it.getString("phase") == "RELOADING" },
            "complete" to { it.getString("phase") == "COMPLETE" }
        )
        for ((name, matches) in milestones) for (afterPromotion in listOf(false, true)) {
            val fixture = MigrationExecutionTestFixture(temporaryFolder)
            var fired = false
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun promote(source: File, target: File) {
                    if (target == fixture.receipt && !fired && matches(JSONObject(source.readText()))) {
                        fired = true
                        if (afterPromotion) SystemCriticalMetadataFileOperations.promote(source, target)
                        throw IOException("injected $name receipt promotion")
                    }
                    SystemCriticalMetadataFileOperations.promote(source, target)
                }
            }
            assertThrows("$name after=$afterPromotion", Exception::class.java) { fixture.executor(operations = operations).execute() }
            assertTrue(fired)
            val alreadyMoved = name !in setOf("prepared", "move intent")
            val result = fixture.executor(directoryMove = if (alreadyMoved) forbiddenMove() else CriticalDirectoryMove()).execute()
            assertFalse("$name after=$afterPromotion", result.cleanupPending)
            assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
            assertEquals("payload", File(fixture.plan.installations.single().destinationPath, "payload.ini").readText())
            assertProtected(fixture)
        }
    }

    @Test fun `interrupted existing membership directory creation reconfirms its parent before any payload move`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val leaf = fixture.paths.getProfileMembershipFile("game", "profile").parentFile!!
        val parent = leaf.parentFile!!
        var failed = false
        var confirmedOnRetry = false
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                if (directory == leaf && !failed) { failed = true; throw IOException("injected creation barrier failure") }
                if (failed && directory == parent) confirmedOnRetry = true
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
            }
        }
        assertThrows(Exception::class.java) { fixture.executor(operations = operations).execute() }
        assertTrue(leaf.exists())
        assertEquals(MigrationExecutionPhase.PREPARED, fixture.store.load()!!.phase)
        val move = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun move(source: File, destination: File) {
                assertTrue("Uncertain parent creation was not confirmed", confirmedOnRetry)
                SystemCriticalDirectoryMoveOperations.move(source, destination)
            }
        })
        assertFalse(fixture.executor(operations = operations, directoryMove = move).execute().cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `both neither and changed old or new payloads remain ambiguous and preserve current evidence`() {
        for (state in listOf("both", "neither", "old changed", "new changed")) {
            val fixture = MigrationExecutionTestFixture(temporaryFolder)
            val installation = fixture.plan.installations.single()
            val source = File(installation.sourcePath)
            val destination = File(installation.destinationPath)
            val moved = state == "new changed"
            assertThrows(Exception::class.java) { fixture.executor(directoryMove = failingMove(moved)).execute() }
            when (state) {
                "both" -> source.copyRecursively(destination)
                "neither" -> Files.move(source.toPath(), File(temporaryFolder.newFolder(), "retained-evidence").toPath())
                "old changed" -> File(source, "payload.ini").writeText("changed old bytes")
                "new changed" -> File(destination, "payload.ini").writeText("changed new bytes")
            }
            val before = listOf(source, destination).map { if (it.exists()) MigrationTreeVerifier().capture(it) else null }
            assertThrows(Exception::class.java) { fixture.executor(directoryMove = forbiddenMove()).execute() }
            assertEquals(before, listOf(source, destination).map { if (it.exists()) MigrationTreeVerifier().capture(it) else null })
            assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
            assertProtected(fixture)
        }
    }

    @Test fun `cancel after rename preserves new-only recovery and never reports restored old layout`() {
        val beforeStart = MigrationExecutionTestFixture(temporaryFolder)
        val preparation = beforeStart.receipt.readBytes()
        val alreadyCancelled = InstallCancellationController().apply { cancel() }
        assertThrows(InstallCancelledException::class.java) { beforeStart.executor(directoryMove = forbiddenMove()).execute(alreadyCancelled.signal) }
        assertArrayEquals(preparation, beforeStart.receipt.readBytes())
        assertFalse(beforeStart.paths.getGameLibraryMigrationGuardDirectory("game").exists())
        assertEquals("payload", beforeStart.payload.readText())
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val cancellation = InstallCancellationController()
        val move = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun move(source: File, destination: File) {
                SystemCriticalDirectoryMoveOperations.move(source, destination)
                cancellation.cancel()
            }
        })
        assertThrows(InstallCancelledException::class.java) { fixture.executor(directoryMove = move).execute(cancellation.signal) }
        assertEquals(MigrationExecutionPhase.MOVING, fixture.store.load()!!.phase)
        assertFalse(File(fixture.plan.installations.single().sourcePath).exists())
        assertTrue(File(fixture.plan.installations.single().destinationPath).exists())
        assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
        assertFalse(fixture.executor(directoryMove = forbiddenMove()).execute().cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `cancel during committed cleanup is pending cleanup not cancellation of migration`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        assertTrue(fixture.executor(deleteOwnedPath = { throw IOException("cleanup paused") }).execute().cleanupPending)
        val cancellation = InstallCancellationController().apply { cancel() }
        val result = fixture.executor(directoryMove = forbiddenMove()).execute(cancellation.signal)
        assertTrue(result.cleanupPending)
        assertTrue(result.cleanupFailure is InstallCancelledException)
        assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
        assertNotNull(fixture.publishedLibrary())
        assertFalse(fixture.executor(directoryMove = forbiddenMove()).execute().cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `A B A selection with changed admitted generation stops before relocation`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        var selectedProfile = "A"
        var generation = 0
        var changed = false
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                val json = if (target == fixture.receipt) JSONObject(source.readText()) else null
                SystemCriticalMetadataFileOperations.promote(source, target)
                if (!changed && json?.getString("phase") == "MOVING" && json.getJSONArray("entries").getJSONObject(0).getBoolean("moveIntended")) {
                    selectedProfile = "B"; generation++
                    selectedProfile = "A"; generation++
                    changed = true
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.executor(operations = operations, directoryMove = forbiddenMove(), contextStillCurrent = { selectedProfile == "A" && generation == 0 }).execute()
        }
        assertEquals("A", selectedProfile)
        assertEquals(2, generation)
        assertEquals("payload", fixture.payload.readText())
        assertFalse(File(fixture.plan.installations.single().destinationPath).exists())
        assertFalse(fixture.executor().execute().cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `actual reader failure leaves reload pending and restart reloads all profiles without moves`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, listOf("one", "two"))
        assertThrows(IllegalStateException::class.java) {
            fixture.executor(contextStillCurrent = { !insideSharedReader() }).execute()
        }
        assertEquals(MigrationExecutionPhase.RELOADING, fixture.store.load()!!.phase)
        assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
        var actualReaderChecks = 0
        assertFalse(fixture.executor(directoryMove = forbiddenMove(), contextStillCurrent = {
            if (insideSharedReader()) actualReaderChecks++
            true
        }).execute().cleanupPending)
        assertTrue("Every profile must enter and finish the real shared reader", actualReaderChecks >= 4)
        assertProtected(fixture)
    }

    @Test fun `foreign publication or metadata matching bytes without metadata intent cannot be adopted`() {
        val foreignPublication = MigrationExecutionTestFixture(temporaryFolder)
        val catalogue = foreignPublication.paths.getGameLibraryCatalogueFile("game")
        val failed = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                SystemCriticalMetadataFileOperations.promote(source, target)
                if (target == catalogue) throw IOException("injected publication uncertainty")
            }
        }
        assertThrows(Exception::class.java) { foreignPublication.executor(operations = failed).execute() }
        catalogue.writeText("foreign publication; preserve")
        assertThrows(Exception::class.java) { foreignPublication.executor(directoryMove = forbiddenMove()).execute() }
        assertEquals("foreign publication; preserve", catalogue.readText())
        assertProtected(foreignPublication)

        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val beforeIntent = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                if (target == fixture.receipt) {
                    val json = JSONObject(source.readText())
                    if (json.getString("phase") == "MOVING" && json.getJSONArray("entries").getJSONObject(0).getBoolean("metadataIntended")) {
                        throw IOException("injected failure before metadata intent")
                    }
                }
                SystemCriticalMetadataFileOperations.promote(source, target)
            }
        }
        assertThrows(Exception::class.java) { fixture.executor(operations = beforeIntent).execute() }
        val repository = GameModLibraryRepository(fixture.paths, "game")
        val entry = repository.decodeLibrary(fixture.plan.candidateCatalogue).entries.single()
        val sidecar = File(fixture.plan.installations.single().destinationPath, LibraryInstallReplacement.ENTRY_RECORD)
        sidecar.writeText(repository.encodeEntryRecord(entry))
        val original = sidecar.readBytes()
        assertThrows(Exception::class.java) { fixture.executor(directoryMove = forbiddenMove()).execute() }
        assertArrayEquals(original, sidecar.readBytes())
        assertProtected(fixture)
    }

    @Test fun `unsupported rename volume and persistence never fall back to copy delete`() {
        for (kind in listOf("rename", "volume", "sync")) {
            val fixture = MigrationExecutionTestFixture(temporaryFolder)
            val move = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
                override fun sameFileStore(source: File, destinationParent: File): Boolean = kind != "volume"
                override fun move(source: File, destination: File) {
                    if (kind == "rename") throw AtomicMoveNotSupportedException(source.path, destination.path, "injected unsupported operation")
                    SystemCriticalDirectoryMoveOperations.move(source, destination)
                }
                override fun syncDirectory(directory: File) {
                    if (kind == "sync") throw IOException("injected unsupported parent barrier")
                    SystemCriticalDirectoryMoveOperations.syncDirectory(directory)
                }
            })
            assertThrows(Exception::class.java) { fixture.executor(directoryMove = move).execute() }
            assertEquals("payload", fixture.payload.readText())
            assertFalse(File(fixture.plan.installations.single().destinationPath).exists())
            assertProtected(fixture)
        }
    }

    @Test fun `unknown nonempty obsolete directories and same-name replacement metadata survive cleanup`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        val unknown = File(fixture.external, "state/profiles/profile/mod_file_indexes/unknown.evidence")
        val legacyState = File(fixture.external, "state/profiles/profile/installed_mods.json")
        val stateBytes = legacyState.readBytes()
        val result = fixture.executor(deleteOwnedPath = { file ->
            if (file == legacyState) throw IOException("retain old metadata for identity test")
            if (file.parentFile?.name == "mod_file_indexes") unknown.writeText("unknown protected evidence")
            Files.delete(file.toPath())
        }).execute()
        assertTrue(result.cleanupPending)
        // Keep the old inode alive so replacement cannot accidentally reuse its key.
        Files.move(legacyState.toPath(), File(legacyState.parentFile, "retained-original-evidence").toPath())
        legacyState.writeBytes(stateBytes)
        val retry = fixture.executor(directoryMove = forbiddenMove()).execute()
        assertTrue(retry.cleanupPending)
        assertArrayEquals(stateBytes, legacyState.readBytes())
        assertEquals("unknown protected evidence", unknown.readText())
        assertNotNull(fixture.publishedLibrary())
        assertProtected(fixture)
    }

    @Test fun `empty library with profile Overwrite migrates without inventing a normal mod`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, withOverwrite = true, withInstallations = false)
        assertFalse(fixture.executor(directoryMove = forbiddenMove()).execute().cleanupPending)
        assertTrue(fixture.publishedLibrary()!!.entries.isEmpty())
        val view = fixture.sourceReader.stagedEffectiveSources(fixture.paths, "game", "profile", { true })
        assertTrue(view.paths.isNotEmpty())
        assertEquals(1L, view.membershipRevision)
        assertProtected(fixture)
    }

    @Test fun `execution envelope overflow is refused before a payload move or preparation upgrade`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, sourceUrlOverride = "\\".repeat(525_000))
        val before = fixture.receipt.readBytes()
        assertThrows(IllegalArgumentException::class.java) { fixture.executor(directoryMove = forbiddenMove()).execute() }
        assertArrayEquals(before, fixture.receipt.readBytes())
        assertEquals("payload", fixture.payload.readText())
        GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "game")
        assertProtected(fixture)
    }

    @Test fun `lost receipt with retained execution guard blocks reads writes and fresh initialization`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder)
        assertThrows(Exception::class.java) { fixture.executor(directoryMove = failingMove(false)).execute() }
        val retainedReceipt = File(temporaryFolder.newFolder(), "retained-receipt.json")
        Files.move(fixture.receipt.toPath(), retainedReceipt.toPath())
        assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
        assertThrows(IllegalStateException::class.java) { LibraryInstallReplacement(fixture.paths, "game").begin() }
        assertThrows(Exception::class.java) { fixture.executor(directoryMove = forbiddenMove()).execute() }
        assertEquals("payload", fixture.payload.readText())
        GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "other-game")
        Files.move(retainedReceipt.toPath(), fixture.receipt.toPath())
        assertFalse(fixture.executor().execute().cleanupPending)
        assertProtected(fixture)
    }

    @Test fun `downgraded preparation or lost completed guard never silently becomes a fresh layout`() {
        val downgraded = MigrationExecutionTestFixture(temporaryFolder)
        assertThrows(Exception::class.java) { downgraded.executor(directoryMove = failingMove(false)).execute() }
        downgraded.receipt.writeText(GameLibraryMigrationPlanRepository(downgraded.paths, "game", downgraded.inventory).encode(downgraded.plan))
        assertThrows(IllegalStateException::class.java) { downgraded.publishedLibrary() }
        assertThrows(Exception::class.java) { downgraded.executor().execute() }
        assertEquals("payload", downgraded.payload.readText())
        val complete = MigrationExecutionTestFixture(temporaryFolder)
        assertFalse(complete.executor().execute().cleanupPending)
        Files.delete(complete.paths.getGameLibraryMigrationGuardDirectory("game").toPath())
        assertThrows(IllegalStateException::class.java) { complete.publishedLibrary() }
        assertThrows(IllegalStateException::class.java) { complete.executor(directoryMove = forbiddenMove()).execute() }
        assertEquals("payload", File(complete.plan.installations.single().destinationPath, "payload.ini").readText())
        assertProtected(downgraded)
        assertProtected(complete)
    }

    @Test fun `normal source eligibility includes disabled payloads and reserves the added metadata entry`() {
        ModFileIndexService.requireMigrationSidecarCapacity(ModFileIndexService.MAX_SOURCE_ENTRIES - 1)
        for (count in listOf(-1, ModFileIndexService.MAX_SOURCE_ENTRIES, ModFileIndexService.MAX_SOURCE_ENTRIES + 1)) {
            assertThrows(IllegalArgumentException::class.java) { ModFileIndexService.requireMigrationSidecarCapacity(count) }
        }
        val fixture = MigrationExecutionTestFixture(temporaryFolder, payloadFilename = "unsafe:name")
        val before = fixture.receipt.readBytes()
        assertThrows(Exception::class.java) { fixture.executor(directoryMove = forbiddenMove()).execute() }
        assertArrayEquals(before, fixture.receipt.readBytes())
        assertEquals("payload", fixture.payload.readText())
        GameLibraryMigrationAccess.requireOrdinaryAccess(fixture.paths, "game")
        assertProtected(fixture)
    }

    @Test fun `stale legacy index observations are preserved instead of declared safely rebuildable`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, indexHashOverride = "0".repeat(64))
        val index = File(fixture.external, "state/profiles/profile/mod_file_indexes/mod.json")
        val before = index.readBytes()
        val result = fixture.executor(deleteOwnedPath = { file ->
            assertNotEquals("Stale index is not an allowlisted current cache", index, file)
            Files.delete(file.toPath())
        }).execute()
        assertTrue(result.cleanupPending)
        assertEquals(MigrationExecutionPhase.COMPLETE, fixture.store.load()!!.phase)
        assertArrayEquals(before, index.readBytes())
        assertNotNull(fixture.publishedLibrary())
        assertProtected(fixture)
    }

    @Test fun `restart after first durable profile reload still reloads every affected profile`() {
        val fixture = MigrationExecutionTestFixture(temporaryFolder, listOf("one", "two"))
        var interrupted = false
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                val json = if (target == fixture.receipt) JSONObject(source.readText()) else null
                SystemCriticalMetadataFileOperations.promote(source, target)
                if (!interrupted && json?.getString("phase") == "RELOADING" && json.getJSONArray("reloadedProfileIds").length() == 1) {
                    interrupted = true
                    throw IOException("injected interruption after first profile reload flag")
                }
            }
        }
        assertThrows(Exception::class.java) { fixture.executor(operations = operations).execute() }
        assertTrue(interrupted)
        assertEquals(listOf("one"), fixture.store.load()!!.reloadedProfileIds)
        assertThrows(IllegalStateException::class.java) { fixture.publishedLibrary() }
        var actualReaderChecks = 0
        assertFalse(fixture.executor(directoryMove = forbiddenMove(), contextStillCurrent = {
            if (insideSharedReader()) actualReaderChecks++
            true
        }).execute().cleanupPending)
        assertTrue("Persisted flags cannot replace reloads in the new process", actualReaderChecks >= 4)
        assertEquals(setOf("one", "two"), fixture.store.load()!!.reloadedProfileIds.toSet())
        assertProtected(fixture)
    }

    private fun insideSharedReader() = Thread.currentThread().stackTrace.any {
        it.className.endsWith(".ModLibraryService") && it.methodName == "stagedEffectiveSources"
    }

    private fun failingMove(afterMove: Boolean) = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
        override fun move(source: File, destination: File) {
            if (afterMove) SystemCriticalDirectoryMoveOperations.move(source, destination)
            throw IOException("injected rename failure")
        }
    })

    private fun forbiddenMove() = CriticalDirectoryMove(object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
        override fun move(source: File, destination: File) { throw AssertionError("Payload move must not repeat") }
    })

    private fun assertProtected(fixture: MigrationExecutionTestFixture) {
        fixture.protectedFiles.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
    }
}
