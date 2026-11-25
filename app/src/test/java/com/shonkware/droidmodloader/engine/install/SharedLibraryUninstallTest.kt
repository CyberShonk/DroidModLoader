package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.data.*
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SharedLibraryUninstallTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { "unrelated-profile" }, { "other-game" }) }
    private val repository by lazy { GameModLibraryRepository(paths, gameId) }
    private val catalogue get() = paths.getGameLibraryCatalogueFile(gameId)
    private val finalDirectory get() = paths.getGameLibraryEntryDir(gameId, "entry")
    private val transactionDirectory get() = paths.getGameLibraryInstallTransactionDir(gameId)
    private var profileIds = listOf("A", "B")
    private var failurePoint: String? = null
    private var failCleanup = false
    private var partiallyDelete = false
    private var deletionAttempts = 0
    private var failIndex = false
    private var availableBytes: Long? = Long.MAX_VALUE
    private var contextGeneration = 1
    private var contextChecks = 0
    private var changeContextAt: Int? = null
    private var afterCommit: () -> Unit = {}
    private var afterQuarantine: () -> Unit = {}
    private var beforeInvalidation: () -> Unit = {}
    private val invalidations = mutableListOf<Set<String>>()
    private val preservedFiles = linkedMapOf<File, ByteArray>()

    private fun maybeFail(point: String) {
        if (failurePoint == point) { failurePoint = null; throw IOException("Injected $point") }
    }

    private val metadataOperations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
        override fun promote(source: File, target: File) {
            val committed = target.name == "control.json" && source.readText().contains("COMMITTED")
            if (target == catalogue) maybeFail("catalogue-before")
            if (committed) maybeFail("commit-before")
            SystemCriticalMetadataFileOperations.promote(source, target)
            if (target == catalogue) maybeFail("catalogue-after")
            if (committed) { afterCommit(); maybeFail("commit-after") }
        }
        override fun syncDirectory(directory: File) {
            SystemCriticalMetadataFileOperations.syncDirectory(directory)
            if (directory == transactionDirectory) {
                val control = File(directory, "control.json")
                if (control.isFile && control.readText().contains("COMMITTED")) {
                    maybeFail("commit-barrier"); maybeFail("cleanup-sync")
                }
            }
        }
    }
    private val moveOperations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
        override fun move(source: File, destination: File) {
            val step = if (destination.name == "quarantine") "quarantine" else "restore"
            maybeFail("$step-before")
            SystemCriticalDirectoryMoveOperations.move(source, destination)
            if (step == "quarantine") afterQuarantine()
            maybeFail("$step-after")
        }
    }
    private val deleteOperations = object : InstalledModDirectoryOperations by DefaultInstalledModDirectoryOperations {
        override fun deleteRecursively(target: File): Boolean {
            deletionAttempts++
            if (partiallyDelete) { File(target, "payload.txt").delete(); return false }
            return !failCleanup && DefaultInstalledModDirectoryOperations.deleteRecursively(target)
        }
    }
    private val replacement by lazy { LibraryInstallReplacement(paths, gameId, metadataOperations,
        CriticalDirectoryMove(moveOperations), deleteOperations) }
    private val coordinator by lazy { SharedLibraryUninstall(paths, gameId, { profileIds }, repository, replacement,
        { availableBytes }, invalidateDerivedViews = { _, affected ->
            beforeInvalidation(); invalidations.add(affected)
            if (failIndex) throw IOException("Derived invalidation failed")
        }) }

    private fun prepare(): GameModLibrary {
        catalogue.parentFile!!.mkdirs(); finalDirectory.parentFile!!.mkdirs()
        var library: GameModLibrary? = null
        listOf("entry", "variant").forEachIndexed { index, id ->
            val initial = LibraryInstallReplacement(paths, gameId)
            val stage = initial.begin().also { it.mkdir() }
            File(stage, "payload.txt").writeText("$id payload")
            File(stage, "nested").mkdir(); File(stage, "empty").mkdir()
            File(stage, "nested/config.txt").writeText("$id nested content")
            val entry = GameLibraryEntry(id, 1, InstalledModRecord(id, "Same name",
                paths.getGameLibraryEntryDir(gameId, id).absolutePath, "imported_zip", "original.zip", 1,
                sourceUrl = "https://example.test/source"))
            val newLibrary = GameModLibrary(gameId, index + 1L, library?.entries.orEmpty() + entry)
            initial.publish("initial-$id", library, newLibrary, entry, InstallCancellationSignal.NONE)
            initial.finish(); library = newLibrary
        }
        profileIds.forEach { id ->
            paths.getProfileMembershipFile(gameId, id).parentFile!!.mkdirs()
            repository.saveProfile(id, ProfileModMembership(gameId, id, 1,
                listOf(ProfileModChoice("entry", id == "A", 7), ProfileModChoice("variant", true, 12))), null)
            preserve(paths.getProfileMembershipFile(gameId, id))
        }
        listOf("profiles/A/Overwrite/generated.txt", "profiles/B/Overwrite/generated.txt", "profiles/A/plugins.txt",
            "profiles/B/game.ini", "game-target/Data/payload.txt", "target-state/manifest.json", "target-state/backup/original.txt",
            "archives/original.zip", "other-game/mods/payload.txt").forEach { path ->
            val file = File(temporaryFolder.root, path); file.parentFile!!.mkdirs(); file.writeText("preserve $path"); preserve(file)
        }
        return checkNotNull(library)
    }

    private fun preserve(file: File) { preservedFiles[file] = file.readBytes() }
    private fun assertPreserved() { preservedFiles.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) } }
    private fun contextCurrent(): Boolean {
        contextChecks++
        if (contextChecks == changeContextAt) contextGeneration += 2 // A -> B -> A, name still A.
        return contextGeneration == 1
    }
    private fun uninstall(review: SharedLibraryRemovalReview = coordinator.review("entry"),
                          signal: InstallCancellationSignal = InstallCancellationSignal.NONE) =
        coordinator.uninstall("remove-entry", review, "A", ::contextCurrent, signal)

    @Test fun `shared removal confirms disabled references and preserves profile choices targets archives and variants`() {
        val old = prepare()
        val variantDirectory = paths.getGameLibraryEntryDir(gameId, "variant")
        val variantMetadata = File(variantDirectory, LibraryInstallReplacement.ENTRY_RECORD).readBytes()
        val review = coordinator.review("entry")
        assertEquals(setOf("A", "B"), review.affectedProfileIds)
        val result = uninstall(review)
        assertEquals("Uninstalled", result.message)
        assertFalse(result.cleanupPending); assertTrue(result.derivedViewsReady)
        assertEquals("entry", result.removedEntry.installationId)
        assertEquals(old.revision + 1, repository.loadLibrary()!!.revision)
        assertEquals(listOf("variant"), replacement.loadPublishedLibrary()!!.entries.map { it.installationId })
        assertFalse(finalDirectory.exists()); assertFalse(transactionDirectory.exists())
        assertEquals(listOf(setOf("A", "B")), invalidations)
        assertArrayEquals(variantMetadata, File(variantDirectory, LibraryInstallReplacement.ENTRY_RECORD).readBytes())
        assertEquals("variant payload", File(variantDirectory, "payload.txt").readText())
        val missing = repository.joinProfileRecords("B", repository.loadLibrary()!!, repository.loadProfile("B")!!)
            .single { it.choice.installationId == "entry" }
        assertNull(missing.libraryEntry); assertFalse(missing.choice.enabled); assertEquals(7, missing.choice.priority)
        assertPreserved()
    }

    @Test fun `last uninstall leaves an authoritative empty library without adopting or deleting unknown folders`() {
        prepare()
        val orphan = paths.getGameLibraryEntryDir(gameId, "uncatalogued")
        orphan.mkdir(); File(orphan, "keep.txt").writeText("unknown content")
        uninstall()
        val result = coordinator.uninstall("remove-variant", coordinator.review("variant"), "A", { true })
        assertFalse(result.cleanupPending)
        assertNotNull(repository.loadLibrary()); assertTrue(replacement.loadPublishedLibrary()!!.entries.isEmpty())
        assertEquals("unknown content", File(orphan, "keep.txt").readText())
        assertTrue(repository.joinProfileRecords("A", repository.loadLibrary()!!, repository.loadProfile("A")!!)
            .all { it.libraryEntry == null })
        assertPreserved()
    }

    @Test fun `new installation cannot reuse removed identity and silently reconnect missing profile references`() {
        prepare(); val removed = uninstall().removedEntry
        val session = temporaryFolder.newFolder("prepared")
        val raw = File(session, "raw").also { it.mkdir() }; File(raw, "payload.txt").writeText("different installation")
        val prepared = PreparedArchiveInstall("source.zip", "source.zip", "Same name", session.absolutePath,
            raw.absolutePath, raw.absolutePath,
            InstallerPlan(InstallerType.SIMPLE, "Same name", raw.absolutePath,
                listOf(InstallerGroup("main", "Main", InstallerGroupType.SELECT_ANY,
                    listOf(InstallerOption("main", "Main", sourcePath = ".", destinationPath = ".", required = true))))))
        val installer = PreparedArchiveInstaller(File(temporaryFolder.root, "temp"), File(temporaryFolder.root, "legacy-mods"))
        val installs = SharedLibraryInstall(paths, gameId, installer, { profileIds },
            availableBytes = { Long.MAX_VALUE }, invalidateDerivedViews = { _, _ -> }, rebuildIndex = {})
        val oldCatalogueBytes = catalogue.readBytes()
        val failure = assertThrows(IllegalStateException::class.java) {
            installs.install("another-installation", installs.review("entry"), "A", removed.installedMetadata,
                prepared, InstallerSelection(emptySet()), ProfileModChoice("entry", true, 7), { true })
        }
        assertTrue(failure.message!!.contains("fresh identity"))
        assertEquals("fresh", installs.review("fresh").installationId)
        assertArrayEquals(oldCatalogueBytes, catalogue.readBytes())
        assertFalse(finalDirectory.exists()); assertFalse(transactionDirectory.exists()); assertPreserved()
    }

    @Test fun `each precommit interruption restores exact old bytes and resumes repeated recovery`() {
        listOf("quarantine-before", "quarantine-after", "catalogue-before", "catalogue-after", "commit-before").forEach { point ->
            val old = prepare()
            val oldBytes = catalogue.readBytes()
            val oldMetadata = File(finalDirectory, LibraryInstallReplacement.ENTRY_RECORD).readBytes()
            failurePoint = point
            assertThrows(Exception::class.java) { uninstall() }
            assertNull("Injection was not reached: $point", failurePoint)
            assertThrows(Exception::class.java) { replacement.loadPublishedLibrary() }
            assertFalse(coordinator.recoverInterruptedRemoval()); assertFalse(coordinator.recoverInterruptedRemoval())
            assertArrayEquals(oldBytes, catalogue.readBytes())
            assertArrayEquals(oldMetadata, File(finalDirectory, LibraryInstallReplacement.ENTRY_RECORD).readBytes())
            assertEquals("entry payload", File(finalDirectory, "payload.txt").readText())
            assertEquals("entry nested content", File(finalDirectory, "nested/config.txt").readText())
            assertTrue(File(finalDirectory, "empty").isDirectory)
            assertEquals(old, repository.loadLibrary()); assertPreserved()
            coordinator.finishPrivateRemovalCleanup()
            // Independent synthetic fixture for the next interruption, not an application cleanup route.
            temporaryFolder.root.listFiles()!!.forEach { assertTrue(it.deleteRecursively()) }
            preservedFiles.clear(); contextChecks = 0
        }
    }

    @Test fun `commit promotion before error is removal not restoration and never rewrites profiles`() {
        prepare(); failurePoint = "commit-after"
        assertThrows(Exception::class.java) { uninstall() }
        assertTrue(coordinator.recoverInterruptedRemoval()); assertTrue(coordinator.recoverInterruptedRemoval())
        assertFalse(finalDirectory.exists()); assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile)
        assertEquals(listOf("variant"), replacement.loadPublishedLibrary()!!.entries.map { it.installationId })
        assertPreserved()
        coordinator.finishPrivateRemovalCleanup()
        assertFalse(transactionDirectory.exists())
    }

    @Test fun `committed marker barrier failure is reconciled without reinstalling removed mod`() {
        prepare(); failurePoint = "commit-barrier"
        val failure = assertThrows(Exception::class.java) { uninstall() }
        assertNotNull(failure)
        assertNull(failurePoint)
        assertTrue(coordinator.recoverInterruptedRemoval())
        assertFalse(finalDirectory.exists()); assertPreserved()
    }

    @Test fun `failed cleanup reports removal with cleanup pending and blocks another mutation`() {
        prepare(); failCleanup = true
        val result = uninstall()
        assertEquals("Uninstalled; cleanup pending", result.message)
        assertTrue(result.cleanupPending); assertNotNull(result.cleanupFailure)
        assertFalse(finalDirectory.exists()); assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile)
        assertEquals(listOf("variant"), replacement.loadPublishedLibrary()!!.entries.map { it.installationId })
        assertThrows(Exception::class.java) { replacement.begin() }
        assertTrue(coordinator.recoverInterruptedRemoval()); assertPreserved()
        failCleanup = false; coordinator.finishPrivateRemovalCleanup()
        assertFalse(transactionDirectory.exists())
    }

    @Test fun `unknown cleanup content is preserved after committed removal`() {
        prepare()
        afterCommit = { File(transactionDirectory, "unknown.txt").writeText("preserve this evidence") }
        val result = uninstall()
        assertTrue(result.cleanupPending)
        assertEquals("preserve this evidence", File(transactionDirectory, "unknown.txt").readText())
        assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile)
        assertTrue(coordinator.recoverInterruptedRemoval()); assertFalse(finalDirectory.exists()); assertPreserved()
    }

    @Test fun `partial cleanup stays pending and does not authorize restoration or deleting changed evidence`() {
        prepare(); partiallyDelete = true
        val result = uninstall()
        assertTrue(result.cleanupPending)
        assertTrue(coordinator.recoverInterruptedRemoval())
        assertFalse(finalDirectory.exists())
        assertFalse(File(transactionDirectory, "quarantine/payload.txt").exists())
        assertTrue(File(transactionDirectory, "quarantine/nested/config.txt").isFile)
        partiallyDelete = false
        assertThrows(Exception::class.java) { coordinator.finishPrivateRemovalCleanup() }
        assertTrue(File(transactionDirectory, "control.json").isFile); assertPreserved()
    }

    @Test fun `cleanup first reestablishes committed-marker persistence before deleting quarantine`() {
        prepare(); beforeInvalidation = { failurePoint = "cleanup-sync" }
        val result = uninstall()
        assertTrue(result.cleanupPending); assertEquals(0, deletionAttempts)
        assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile)
        assertEquals(listOf("variant"), replacement.loadPublishedLibrary()!!.entries.map { it.installationId })
        assertPreserved()
    }

    @Test fun `derived failure leaves removal committed without erasing source or profile evidence elsewhere`() {
        prepare(); failIndex = true
        val result = uninstall()
        assertFalse(result.derivedViewsReady); assertNotNull(result.indexFailure)
        assertFalse(result.cleanupPending); assertFalse(finalDirectory.exists())
        assertEquals(listOf("variant"), repository.loadLibrary()!!.entries.map { it.installationId })
        assertPreserved()
    }

    @Test fun `changed disabled profile or added profile invalidates confirmation before any mutation`() {
        prepare()
        val review = coordinator.review("entry")
        repository.saveProfile("B", repository.loadProfile("B")!!.copy(revision = 2,
            choices = listOf(ProfileModChoice("entry", false, 9))), 1)
        val old = catalogue.readBytes()
        assertThrows(Exception::class.java) { uninstall(review) }
        assertArrayEquals(old, catalogue.readBytes()); assertTrue(finalDirectory.isDirectory); assertFalse(transactionDirectory.exists())
        profileIds = listOf("A", "B", "C")
        assertThrows(Exception::class.java) { coordinator.review("entry") }
    }

    @Test fun `corrupt missing unsupported or duplicate profile scope cannot produce partial confirmation`() {
        prepare()
        val membership = paths.getProfileMembershipFile(gameId, "B")
        val original = membership.readText()
        membership.writeText("corrupt")
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        membership.writeText(JSONObject(original).put("schemaVersion", 2).toString())
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        membership.delete()
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        membership.writeText(original); profileIds = listOf("A", "A")
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        assertTrue(finalDirectory.isDirectory); assertFalse(transactionDirectory.exists())
    }

    @Test fun `duplicate stale and wrong-game requests never delete another installation or replay uninstall`() {
        prepare()
        val review = coordinator.review("entry")
        assertThrows(Exception::class.java) { uninstall(review.copy(gameId = "other")) }
        uninstall(review)
        val committedBytes = catalogue.readBytes(); val attempts = deletionAttempts
        assertThrows(Exception::class.java) { uninstall(review) }
        assertArrayEquals(committedBytes, catalogue.readBytes()); assertEquals(attempts, deletionAttempts)
        assertTrue(paths.getGameLibraryEntryDir(gameId, "variant").isDirectory); assertPreserved()
    }

    @Test fun `A B A context before commit stops removal and restores old state after quarantine`() {
        listOf(2, 3, 4, 5).forEach { checkNumber ->
            val old = prepare(); changeContextAt = checkNumber
            assertThrows(Exception::class.java) { uninstall() }
            if (transactionDirectory.exists()) {
                assertFalse(coordinator.recoverInterruptedRemoval()); coordinator.finishPrivateRemovalCleanup()
            }
            assertEquals(old, repository.loadLibrary()); assertTrue(finalDirectory.isDirectory); assertPreserved()
            temporaryFolder.root.listFiles()!!.forEach { assertTrue(it.deleteRecursively()) }
            preservedFiles.clear(); contextChecks = 0; contextGeneration = 1
        }
    }

    @Test fun `profile changes after quarantine stop commit and recovery preserves the new profile bytes`() {
        val old = prepare()
        afterQuarantine = {
            val profile = repository.loadProfile("B")!!
            repository.saveProfile("B", profile.copy(revision = 2,
                choices = listOf(ProfileModChoice("entry", false, 21))), 1)
            preserve(paths.getProfileMembershipFile(gameId, "B"))
        }
        assertThrows(Exception::class.java) { uninstall() }
        assertFalse(coordinator.recoverInterruptedRemoval())
        assertEquals(old, repository.loadLibrary()); assertEquals(21, repository.loadProfile("B")!!.choices.single().priority)
        assertTrue(finalDirectory.isDirectory); assertPreserved()
    }

    @Test fun `cancellation before work does not create intent or change the library`() {
        val old = prepare()
        val controller = InstallCancellationController().also { it.cancel() }
        assertThrows(InstallCancelledException::class.java) { uninstall(signal = controller.signal) }
        assertFalse(transactionDirectory.exists()); assertEquals(old, repository.loadLibrary()); assertPreserved()
    }

    @Test fun `cancellation after quarantine restores old installation before commit`() {
        val old = prepare()
        val signal = InstallCancellationSignal { if (File(transactionDirectory, "quarantine").isDirectory) throw InstallCancelledException() }
        assertThrows(InstallCancelledException::class.java) { uninstall(signal = signal) }
        assertFalse(coordinator.recoverInterruptedRemoval())
        assertEquals(old, repository.loadLibrary()); assertTrue(finalDirectory.isDirectory); assertPreserved()
    }

    @Test fun `late cancellation does not turn committed uninstall into cancelled or restored`() {
        prepare()
        val controller = InstallCancellationController()
        afterCommit = { controller.cancel(); contextGeneration += 2 }
        val result = uninstall(signal = controller.signal)
        assertEquals("Uninstalled", result.message); assertFalse(result.cleanupPending)
        assertFalse(finalDirectory.exists()); assertPreserved()
    }

    @Test fun `interrupted restoration after move can be resumed without lost or duplicate payload`() {
        val old = prepare(); failurePoint = "catalogue-after"
        assertThrows(Exception::class.java) { uninstall() }
        failurePoint = "restore-after"
        assertThrows(Exception::class.java) { coordinator.recoverInterruptedRemoval() }
        assertFalse(coordinator.recoverInterruptedRemoval())
        assertEquals(old, repository.loadLibrary()); assertEquals("entry payload", File(finalDirectory, "payload.txt").readText())
        assertPreserved()
    }

    @Test fun `changed quarantined payload blocks precommit recovery and preserves evidence`() {
        prepare(); failurePoint = "catalogue-before"
        assertThrows(Exception::class.java) { uninstall() }
        File(transactionDirectory, "quarantine/payload.txt").writeText("unknown changed bytes")
        assertThrows(Exception::class.java) { coordinator.recoverInterruptedRemoval() }
        assertThrows(Exception::class.java) { replacement.loadPublishedLibrary() }
        assertFalse(finalDirectory.exists()); assertTrue(File(transactionDirectory, "control.json").isFile); assertPreserved()
    }

    @Test fun `linked payload is refused without moving or deleting the outside directory`() {
        prepare()
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "keep.txt").writeText("outside")
        assertTrue(finalDirectory.deleteRecursively())
        Files.createSymbolicLink(finalDirectory.toPath(), outside.toPath())
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        assertEquals("outside", File(outside, "keep.txt").readText()); assertFalse(transactionDirectory.exists()); assertPreserved()
    }

    @Test fun `old install-only transaction version remains readable after removal format extension`() {
        prepare()
        val initial = LibraryInstallReplacement(paths, gameId)
        val old = repository.loadLibrary()!!
        val stage = initial.begin().also { it.mkdir() }; File(stage, "payload.txt").writeText("third payload")
        val metadata = old.entries.first().installedMetadata.copy(modId = "third",
            installPath = paths.getGameLibraryEntryDir(gameId, "third").absolutePath)
        val entry = GameLibraryEntry("third", 1, metadata)
        val new = old.copy(revision = old.revision + 1, entries = old.entries + entry)
        initial.publish("old-format", old, new, entry, InstallCancellationSignal.NONE)
        val control = File(transactionDirectory, "control.json")
        val json = JSONObject(control.readText()).put("schemaVersion", 1); json.remove("operation"); control.writeText(json.toString())
        assertTrue(initial.recover()); assertEquals(new, initial.loadPublishedLibrary()); initial.finish(); assertPreserved()
    }

    @Test fun `operation-specific recovery and cleanup cannot misreport removal as installation`() {
        prepare(); failCleanup = true; uninstall()
        val controlBytes = File(transactionDirectory, "control.json").readBytes()
        assertThrows(Exception::class.java) { replacement.recover(LibraryInstallReplacement.Operation.INSTALL) }
        assertThrows(Exception::class.java) { replacement.finish(LibraryInstallReplacement.Operation.INSTALL) }
        assertArrayEquals(controlBytes, File(transactionDirectory, "control.json").readBytes())
        assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile)
        assertTrue(coordinator.recoverInterruptedRemoval()); assertPreserved()
    }

    @Test fun `unknown operation is blocked rather than treated as install or removal`() {
        prepare(); failCleanup = true; uninstall()
        val control = File(transactionDirectory, "control.json")
        control.writeText(JSONObject(control.readText()).put("operation", "OTHER").toString())
        assertThrows(Exception::class.java) { coordinator.recoverInterruptedRemoval() }
        assertTrue(File(transactionDirectory, "quarantine/payload.txt").isFile); assertFalse(finalDirectory.exists()); assertPreserved()
    }

    @Test fun `capacity preflight counts retained raw snapshot and rollback sibling without crediting deleted payload`() {
        val old = prepare()
        catalogue.appendText(" ".repeat(128 * 1024)) // Valid larger raw bytes must be retained exactly.
        val new = old.copy(revision = old.revision + 1, entries = old.entries.filterNot { it.installationId == "entry" })
        val oldBytes = catalogue.length(); val newBytes = repository.encodeLibrary(new).toByteArray().size.toLong()
        val required = oldBytes + newBytes + maxOf(oldBytes, newBytes) + 16L * 1024
        availableBytes = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES + required - 1
        assertThrows(Exception::class.java) { uninstall() }
        assertFalse(transactionDirectory.exists()); assertTrue(finalDirectory.isDirectory)
        availableBytes = null
        assertThrows(Exception::class.java) { uninstall() }
        assertFalse(transactionDirectory.exists())
        availableBytes = Long.MAX_VALUE
        assertFalse(uninstall().cleanupPending); assertPreserved()
    }
}
