package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.data.*
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LibraryInstallReplacementTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { "unrelated" }, { "other-game" }) }
    private val repository by lazy { GameModLibraryRepository(paths, gameId) }
    private val catalogue get() = paths.getGameLibraryCatalogueFile(gameId)
    private val finalDirectory get() = paths.getGameLibraryEntryDir(gameId, "entry")
    private val transactionDirectory get() = paths.getGameLibraryInstallTransactionDir(gameId)

    private fun prepareParents() {
        catalogue.parentFile!!.mkdirs()
        finalDirectory.parentFile!!.mkdirs()
    }

    private fun entry(revision: Long) = GameLibraryEntry("entry", revision, InstalledModRecord(
        modId = "entry", displayName = "Same name", installPath = finalDirectory.absolutePath,
        sourceType = "imported_zip", sourceArchiveName = "original.zip", installedAtEpochMillis = revision,
        sourceUrl = "https://example.test/source"
    ))

    private fun stage(replacement: LibraryInstallReplacement, marker: String): File = replacement.begin().also {
        it.mkdir(); File(it, "marker.txt").writeText(marker)
    }

    private fun installOld(): GameModLibrary {
        prepareParents()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "old")
        val old = GameModLibrary(gameId, 1, listOf(entry(1)))
        replacement.publish("first", null, old, old.entries.single(), InstallCancellationSignal.NONE)
        replacement.finish()
        return old
    }

    @Test fun `first install commits matched bytes and retains terminal evidence until explicit cleanup`() {
        prepareParents()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val library = GameModLibrary(gameId, 1, listOf(entry(1)))
        replacement.publish("first", null, library, library.entries.single(), InstallCancellationSignal.NONE)
        assertEquals(library, replacement.loadPublishedLibrary())
        assertEquals("new", File(finalDirectory, "marker.txt").readText())
        assertTrue(transactionDirectory.isDirectory)
        assertTrue(replacement.recover())
        assertThrows(Exception::class.java) { replacement.begin() }
        replacement.finish()
        assertFalse(transactionDirectory.exists())
        assertEquals(library, replacement.loadPublishedLibrary())
    }

    @Test fun `update retains old payload and catalogue through commit and keeps stable identity`() {
        val old = installOld()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE)
        assertEquals("old", File(transactionDirectory, "backup/marker.txt").readText())
        assertEquals(old, repository.decodeLibrary(File(transactionDirectory, "old.json").readText()))
        assertEquals(new, replacement.loadPublishedLibrary())
        replacement.finish()
        assertEquals("new", File(finalDirectory, "marker.txt").readText())
    }

    @Test fun `each move and catalogue interruption restores exact old bytes on repeated recovery`() {
        // One independent managed root per interruption; real host atomic moves plus injected error reporting.
        listOf("backup-before", "backup-after", "promote-before", "promote-after", "catalogue-before", "catalogue-after").forEach { failurePoint ->
            val root = temporaryFolder.newFolder(failurePoint)
            val scopedPaths = ProfileStoragePaths(root, { null }, { gameId })
            val scopedRepository = GameModLibraryRepository(scopedPaths, gameId)
            val scopedCatalogue = scopedPaths.getGameLibraryCatalogueFile(gameId)
            val scopedFinal = scopedPaths.getGameLibraryEntryDir(gameId, "entry")
            scopedCatalogue.parentFile!!.mkdirs(); scopedFinal.parentFile!!.mkdirs()
            val oldEntry = entry(1).copy(installedMetadata = entry(1).installedMetadata.copy(installPath = scopedFinal.absolutePath))
            val old = GameModLibrary(gameId, 1, listOf(oldEntry))
            val initial = LibraryInstallReplacement(scopedPaths, gameId)
            val initialStage = initial.begin().also { it.mkdir() }
            File(initialStage, "marker.txt").writeText("old")
            initial.publish("first", null, old, oldEntry, InstallCancellationSignal.NONE); initial.finish()
            val oldCatalogueBytes = scopedCatalogue.readBytes()
            val oldMetadataBytes = File(scopedFinal, LibraryInstallReplacement.ENTRY_RECORD).readBytes()
            var armed = true
            val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
                override fun move(source: File, destination: File) {
                    val point = if (destination.name == "backup") "backup" else "promote"
                    if (armed && failurePoint == "$point-before") { armed = false; throw IOException("injected $failurePoint") }
                    SystemCriticalDirectoryMoveOperations.move(source, destination)
                    if (armed && failurePoint == "$point-after") { armed = false; throw IOException("injected $failurePoint") }
                }
            }
            val metadataOperations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun promote(source: File, target: File) {
                    if (armed && target == scopedCatalogue && failurePoint == "catalogue-before") { armed = false; throw IOException("injected $failurePoint") }
                    SystemCriticalMetadataFileOperations.promote(source, target)
                    if (armed && target == scopedCatalogue && failurePoint == "catalogue-after") { armed = false; throw IOException("injected $failurePoint") }
                }
            }
            val replacement = LibraryInstallReplacement(scopedPaths, gameId, metadataOperations, CriticalDirectoryMove(operations))
            val staged = replacement.begin().also { it.mkdir() }
            File(staged, "marker.txt").writeText("new")
            val newEntry = oldEntry.copy(payloadRevision = 2)
            val new = GameModLibrary(gameId, 2, listOf(newEntry))
            assertThrows(Exception::class.java) { replacement.publish("update", old, new, newEntry, InstallCancellationSignal.NONE) }
            assertFalse("Failure injection was not reached: $failurePoint", armed)
            assertThrows(Exception::class.java) { replacement.loadPublishedLibrary() }
            assertFalse(replacement.recover())
            assertFalse(replacement.recover())
            assertArrayEquals(oldCatalogueBytes, scopedCatalogue.readBytes())
            assertArrayEquals(oldMetadataBytes, File(scopedFinal, LibraryInstallReplacement.ENTRY_RECORD).readBytes())
            assertEquals("old", File(scopedFinal, "marker.txt").readText())
            assertEquals(old, scopedRepository.loadLibrary())
            replacement.finish()
            assertEquals(old, replacement.loadPublishedLibrary())
        }
    }

    @Test fun `first install catalogue failure restores absence without discovering folders`() {
        prepareParents()
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                SystemCriticalMetadataFileOperations.promote(source, target)
                if (armed && target == catalogue) { armed = false; throw IOException("after catalogue move") }
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 1, listOf(entry(1)))
        assertThrows(Exception::class.java) { replacement.publish("first", null, new, new.entries.single(), InstallCancellationSignal.NONE) }
        assertFalse(replacement.recover())
        assertFalse(catalogue.exists()); assertFalse(finalDirectory.exists())
        assertEquals("new", File(transactionDirectory, "quarantine/marker.txt").readText())
        replacement.finish()
        assertNull(replacement.loadPublishedLibrary())
    }

    @Test fun `commit marker promotion before error is reconciled as committed not rolled back`() {
        val old = installOld()
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                val commit = target.name == "control.json" && source.readText().contains("COMMITTED")
                SystemCriticalMetadataFileOperations.promote(source, target)
                if (armed && commit) { armed = false; throw IOException("commit move reported failure") }
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        assertThrows(Exception::class.java) { replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE) }
        assertFalse(armed)
        assertTrue(replacement.recover())
        assertEquals(new, replacement.loadPublishedLibrary())
        assertEquals("old", File(transactionDirectory, "backup/marker.txt").readText())
    }

    @Test fun `changed payload and unknown transaction fields block without deleting evidence`() {
        prepareParents()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val library = GameModLibrary(gameId, 1, listOf(entry(1)))
        replacement.publish("first", null, library, library.entries.single(), InstallCancellationSignal.NONE)
        File(finalDirectory, "marker.txt").writeText("changed")
        assertThrows(Exception::class.java) { replacement.recover() }
        assertThrows(Exception::class.java) { replacement.finish() }
        assertTrue(File(transactionDirectory, "control.json").isFile)
        val control = File(transactionDirectory, "control.json")
        control.writeText(control.readText().dropLast(1) + ",\"unknown\":true}")
        assertThrows(Exception::class.java) { replacement.recover() }
        assertEquals("changed", File(finalDirectory, "marker.txt").readText())
    }

    @Test fun `metadata and snapshot failures cannot publish payload without authoritative records`() {
        val old = installOld()
        val oldBytes = catalogue.readBytes()
        listOf(LibraryInstallReplacement.ENTRY_RECORD, "old.json", "new.json").forEach { failedName ->
            var armed = true
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun promote(source: File, target: File) {
                    if (armed && target.name == failedName) { armed = false; throw IOException("injected metadata failure") }
                    SystemCriticalMetadataFileOperations.promote(source, target)
                }
            }
            val replacement = LibraryInstallReplacement(paths, gameId, operations)
            stage(replacement, "new")
            val new = GameModLibrary(gameId, 2, listOf(entry(2)))
            assertThrows(Exception::class.java) { replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE) }
            assertFalse(armed)
            assertArrayEquals(oldBytes, catalogue.readBytes())
            assertEquals("old", File(finalDirectory, "marker.txt").readText())
            assertThrows(Exception::class.java) { replacement.recover() }
            assertThrows(Exception::class.java) { replacement.loadPublishedLibrary() }
            // This fixture owns its whole synthetic root; manually remove only its failed private stage
            // to exercise the next independent injection, not as an application recovery action.
            assertTrue(transactionDirectory.deleteRecursively())
        }
    }

    @Test fun `failure before committed marker rolls back catalogue as well as payload`() {
        val old = installOld()
        val oldBytes = catalogue.readBytes()
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                if (armed && target.name == "control.json" && source.readText().contains("COMMITTED")) {
                    armed = false; throw IOException("commit marker was not promoted")
                }
                SystemCriticalMetadataFileOperations.promote(source, target)
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        assertThrows(Exception::class.java) { replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE) }
        assertFalse(armed)
        assertEquals(new, repository.loadLibrary()) // Not reader authority without the committed marker.
        assertFalse(replacement.recover())
        assertArrayEquals(oldBytes, catalogue.readBytes())
        assertEquals("old", File(finalDirectory, "marker.txt").readText())
    }

    @Test fun `post commit directory barrier failure requires successful reconciliation before success`() {
        val old = installOld()
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
                if (armed && directory == transactionDirectory && File(directory, "control.json").let {
                    it.isFile && it.readText().contains("COMMITTED")
                }) { armed = false; throw IOException("commit parent barrier failed") }
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        val failure = assertThrows(CriticalStorageException::class.java) {
            replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE)
        }
        assertTrue(failure.mutationMayHaveOccurred); assertFalse(armed)
        assertTrue(replacement.recover())
        assertEquals(new, replacement.loadPublishedLibrary())
        assertEquals("old", File(transactionDirectory, "backup/marker.txt").readText())
    }

    @Test fun `cancellation after payload promotion restores old state and preserves new evidence`() {
        val old = installOld()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        val signal = InstallCancellationSignal {
            if (File(transactionDirectory, "backup").isDirectory && finalDirectory.isDirectory) throw InstallCancelledException()
        }
        assertThrows(InstallCancelledException::class.java) { replacement.publish("update", old, new, new.entries.single(), signal) }
        assertFalse(replacement.recover())
        assertEquals(old, repository.loadLibrary())
        assertEquals("old", File(finalDirectory, "marker.txt").readText())
        assertEquals("new", File(transactionDirectory, "quarantine/marker.txt").readText())
    }

    @Test fun `interruption during rollback can be resumed without overwriting old or quarantined bytes`() {
        val old = installOld()
        var failPromotion = true
        var failRestoration = true
        val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun move(source: File, destination: File) {
                SystemCriticalDirectoryMoveOperations.move(source, destination)
                if (source.name == "stage" && failPromotion) { failPromotion = false; throw IOException("promotion interrupted") }
                if (source.name == "backup" && failRestoration) { failRestoration = false; throw IOException("restoration interrupted") }
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, directoryMove = CriticalDirectoryMove(operations))
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        assertThrows(Exception::class.java) { replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE) }
        assertThrows(Exception::class.java) { replacement.recover() }
        assertFalse(replacement.recover())
        assertEquals(old, repository.loadLibrary())
        assertEquals("old", File(finalDirectory, "marker.txt").readText())
        assertEquals("new", File(transactionDirectory, "quarantine/marker.txt").readText())
    }

    @Test fun `uncertain changed backup and unknown cleanup content block without erasing any evidence`() {
        val old = installOld()
        var armed = true
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                if (armed && target == catalogue) { armed = false; throw IOException("catalogue failure") }
                SystemCriticalMetadataFileOperations.promote(source, target)
            }
        }
        val replacement = LibraryInstallReplacement(paths, gameId, operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        assertThrows(Exception::class.java) { replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE) }
        File(transactionDirectory, "backup/marker.txt").writeText("unknown old bytes")
        assertThrows(Exception::class.java) { replacement.recover() }
        assertEquals("new", File(finalDirectory, "marker.txt").readText())
        assertTrue(File(transactionDirectory, "control.json").isFile)
        assertEquals(old, repository.loadLibrary())
    }

    @Test fun `failed backup cleanup leaves committed install and retained evidence not a rollback`() {
        val old = installOld()
        var failCleanup = true
        val operations = object : InstalledModDirectoryOperations by DefaultInstalledModDirectoryOperations {
            override fun deleteRecursively(target: File): Boolean =
                if (failCleanup) false else DefaultInstalledModDirectoryOperations.deleteRecursively(target)
        }
        val replacement = LibraryInstallReplacement(paths, gameId, directoryOperations = operations)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE)
        assertThrows(Exception::class.java) { replacement.finish() }
        assertEquals(new, replacement.loadPublishedLibrary())
        assertEquals("old", File(transactionDirectory, "backup/marker.txt").readText())
        assertTrue(File(transactionDirectory, "old.json").isFile)
        failCleanup = false
        replacement.finish()
        assertEquals(new, replacement.loadPublishedLibrary())
    }

    @Test fun `unknown private cleanup content is preserved rather than treated as owned staging`() {
        val old = installOld()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val new = GameModLibrary(gameId, 2, listOf(entry(2)))
        replacement.publish("update", old, new, new.entries.single(), InstallCancellationSignal.NONE)
        File(transactionDirectory, "unknown").writeText("preserve")
        assertThrows(Exception::class.java) { replacement.finish() }
        assertEquals("preserve", File(transactionDirectory, "unknown").readText())
        assertEquals("old", File(transactionDirectory, "backup/marker.txt").readText())
        assertEquals(new, replacement.loadPublishedLibrary())
    }

    @Test fun `oversized and unknown control records remain blocked and unchanged`() {
        prepareParents()
        val replacement = LibraryInstallReplacement(paths, gameId)
        stage(replacement, "new")
        val control = File(transactionDirectory, "control.json")
        control.outputStream().use { output ->
            val block = ByteArray(1024) { 'x'.code.toByte() }
            repeat(GameModLibraryRepository.MAX_RECORD_BYTES / 1024 + 1) { output.write(block) }
        }
        val length = control.length()
        assertThrows(Exception::class.java) { replacement.recover() }
        assertEquals(length, control.length())
        assertFalse(catalogue.exists())
    }

    @Test fun `linked private paths are preserved and blocked`() {
        prepareParents()
        val outside = temporaryFolder.newFolder("outside")
        Files.createSymbolicLink(transactionDirectory.toPath(), outside.toPath())
        val replacement = LibraryInstallReplacement(paths, gameId)
        assertThrows(Exception::class.java) { replacement.recover() }
        assertTrue(outside.isDirectory)
    }
}
