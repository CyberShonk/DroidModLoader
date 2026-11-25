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

class SharedLibraryInstallTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { "not-the-requester" }, { "not-the-game" }) }
    private val repository by lazy { GameModLibraryRepository(paths, gameId) }
    private var profileIds = listOf("A", "B")
    private var failProfileSave = false
    private var profileSaveReportsFailureAfterMove = false
    private var failIndex = false
    private var failInvalidation = false
    private var beforeCopy: () -> Unit = {}
    private var availableBytes: Long? = Long.MAX_VALUE
    private var currentContext = true
    private var afterLibraryCommit: () -> Unit = {}
    private val invalidations = mutableListOf<Set<String>>()
    private var rebuilds = 0
    private val replacement by lazy { LibraryInstallReplacement(paths, gameId) }
    private val profileOperations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
        override fun promote(source: File, target: File) {
            if (failProfileSave && target == paths.getProfileMembershipFile(gameId, "A")) throw IOException("profile save failed")
            SystemCriticalMetadataFileOperations.promote(source, target)
            if (profileSaveReportsFailureAfterMove && target == paths.getProfileMembershipFile(gameId, "A")) {
                throw IOException("profile move succeeded before error reporting")
            }
        }
    }
    private val installer by lazy {
        PreparedArchiveInstaller(File(temporaryFolder.root, "temp"), File(temporaryFolder.root, "legacy-mods"),
            InstalledModDirectoryReplacer(), { "fixture" }, {},
            { source, target, signal ->
                beforeCopy()
                InstallFileCopier.copyRecursively(source, target, signal, usableSpaceBytes = { Long.MAX_VALUE })
            })
    }
    private val coordinator by lazy {
        SharedLibraryInstall(paths, gameId, installer, { profileIds }, GameModLibraryRepository(paths, gameId, profileOperations), replacement,
            availableBytes = { availableBytes },
            invalidateDerivedViews = { _, affected ->
                invalidations.add(affected)
                if (failInvalidation) throw IOException("invalidation failed")
            },
            rebuildIndex = { rebuilds++; if (failIndex) throw IOException("index failed") })
    }

    private fun prepareParents() {
        paths.getGameLibraryCatalogueFile(gameId).parentFile!!.mkdirs()
        paths.getGameLibraryEntryDir(gameId, "entry").parentFile!!.mkdirs()
        profileIds.forEach { paths.getProfileMembershipFile(gameId, it).parentFile!!.mkdirs() }
    }

    private fun metadata(id: String = "entry") = InstalledModRecord(id, "Same display name",
        paths.getGameLibraryEntryDir(gameId, id).absolutePath, "imported_zip", "source.zip", 10L,
        sourceUrl = "https://example.test/original", nexusModId = 42L)

    private fun prepared(marker: String, action: ExistingModAction = ExistingModAction.REPLACE): PreparedArchiveInstall {
        val session = temporaryFolder.newFolder()
        val source = File(session, "raw").also { it.mkdir() }
        File(source, "marker.txt").writeText(marker)
        return PreparedArchiveInstall("unknown-source.zip", "source.zip", "Same display name", session.absolutePath,
            source.absolutePath, source.absolutePath,
            InstallerPlan(InstallerType.SIMPLE, "Same display name", source.absolutePath,
                listOf(InstallerGroup("main", "Main", InstallerGroupType.SELECT_ANY,
                    listOf(InstallerOption("main", "Main", sourcePath = ".", destinationPath = ".", required = true))))),
            confirmation = ArchiveInstallConfirmation("Same display name", existingModAction = action))
    }

    private fun install(id: String = "entry", choice: ProfileModChoice? = ProfileModChoice(id, true, 1),
                        incoming: InstalledModRecord = metadata(id), review: LibraryInstallReview = coordinator.review(id),
                        signal: InstallCancellationSignal = InstallCancellationSignal.NONE,
                        action: ExistingModAction = ExistingModAction.REPLACE): SharedLibraryInstallResult {
        var checks = 0
        return coordinator.install("operation", review, "A", incoming, prepared("new", action), InstallerSelection(emptySet()), choice,
            contextStillCurrent = { checks++; if (checks == 4) afterLibraryCommit(); currentContext }, signal = signal)
    }

    @Test fun `first explicit install selects only requester and never touches targets or legacy state`() {
        prepareParents()
        val unrelated = temporaryFolder.newFile("target-evidence")
        unrelated.writeText("target stays unchanged")
        val other = ProfileModMembership(gameId, "B", 1, emptyList())
        repository.saveProfile("B", other, null)
        val result = install()
        assertTrue(result.profileSelectionSaved); assertTrue(result.derivedViewsReady)
        assertEquals(listOf(ProfileModChoice("entry", true, 1)), repository.loadProfile("A")!!.choices)
        assertEquals(other, repository.loadProfile("B"))
        assertEquals(1L, repository.loadLibrary()!!.entries.single().payloadRevision)
        assertEquals("target stays unchanged", unrelated.readText())
        assertFalse(File(temporaryFolder.root, "legacy-mods").exists())
        assertFalse(File(temporaryFolder.root, "profiles").exists())
    }

    @Test fun `profile failure retains committed library and explicit later selection reuses identity`() {
        prepareParents(); failProfileSave = true
        val result = install()
        assertFalse(result.profileSelectionSaved)
        assertEquals("Installed in library; profile selection not saved", result.message)
        assertNotNull(result.profileFailure)
        assertNull(repository.loadProfile("A"))
        assertEquals(result.entry, repository.loadLibrary()!!.entries.single())
        failProfileSave = false
        coordinator.selectExisting("A", null, ProfileModChoice("entry", true, 7))
        assertEquals(7, repository.loadProfile("A")!!.choices.single().priority)
        assertEquals(1L, repository.loadLibrary()!!.revision)
        assertEquals(1, rebuilds)
    }

    @Test fun `shared update review includes disabled references and preserves choices source facts and Overwrite`() {
        prepareParents(); install(); coordinator.finishPrivateInstallCleanup()
        val originalA = repository.loadProfile("A")!!
        val originalB = ProfileModMembership(gameId, "B", 1, listOf(ProfileModChoice("entry", false, 7)))
        repository.saveProfile("B", originalB, null)
        val overwrite = temporaryFolder.newFile("profile-overwrite")
        overwrite.writeText("keep these bytes")
        val review = coordinator.review("entry")
        assertEquals(setOf("A", "B"), review.affectedProfileIds)
        val result = install(choice = null, review = review, incoming = metadata().copy(sourceUrl = null, nexusModId = null))
        assertEquals(2L, result.entry.payloadRevision)
        assertEquals("entry", result.entry.installationId)
        assertEquals(42L, result.entry.installedMetadata.nexusModId)
        assertEquals("https://example.test/original", result.entry.installedMetadata.sourceUrl)
        assertEquals(originalA, repository.loadProfile("A")); assertEquals(originalB, repository.loadProfile("B"))
        assertEquals("keep these bytes", overwrite.readText())
        assertEquals(setOf("A", "B"), invalidations.last())
    }

    @Test fun `same display name variants have distinct identity and independent payloads`() {
        prepareParents(); install(); coordinator.finishPrivateInstallCleanup()
        val result = install("variant", ProfileModChoice("variant", false, 2))
        assertEquals("variant", result.entry.installationId)
        val entries = repository.loadLibrary()!!.entries
        assertEquals(setOf("entry", "variant"), entries.map { it.installationId }.toSet())
        assertEquals(1, entries.map { it.installedMetadata.displayName }.distinct().size)
        assertNotEquals(entries[0].installedMetadata.installPath, entries[1].installedMetadata.installPath)
        assertTrue(repository.loadProfile("A")!!.choices.single { it.installationId == "entry" }.enabled)
        assertFalse(repository.loadProfile("A")!!.choices.single { it.installationId == "variant" }.enabled)
    }

    @Test fun `stale confirmation and duplicate request cannot perform another installation`() {
        prepareParents()
        val review = coordinator.review("entry")
        install(review = review)
        val catalogueBytes = paths.getGameLibraryCatalogueFile(gameId).readBytes()
        assertThrows(Exception::class.java) { install(review = review) }
        coordinator.finishPrivateInstallCleanup()
        assertThrows(Exception::class.java) { install(review = review) }
        assertArrayEquals(catalogueBytes, paths.getGameLibraryCatalogueFile(gameId).readBytes())
    }

    @Test fun `changed affected profile scope and unreadable membership stop updates`() {
        prepareParents(); install(); coordinator.finishPrivateInstallCleanup()
        repository.saveProfile("B", ProfileModMembership(gameId, "B", 1, emptyList()), null)
        val review = coordinator.review("entry")
        repository.saveProfile("B", ProfileModMembership(gameId, "B", 2, listOf(ProfileModChoice("entry", false, 1))), 1)
        assertThrows(Exception::class.java) { install(review = review) }
        paths.getProfileMembershipFile(gameId, "B").writeText("corrupt")
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
    }

    @Test fun `missing membership and duplicate inventory are not an incomplete update confirmation`() {
        prepareParents(); install(); coordinator.finishPrivateInstallCleanup()
        assertThrows(Exception::class.java) { coordinator.review("entry") }
        profileIds = listOf("A", "A")
        assertThrows(Exception::class.java) { coordinator.review("entry") }
    }

    @Test fun `index rebuild failure leaves source metadata and saved profile intact`() {
        prepareParents(); failIndex = true
        val result = install()
        assertTrue(result.profileSelectionSaved); assertFalse(result.derivedViewsReady)
        assertNotNull(result.indexFailure)
        assertEquals(result.entry, replacement.loadPublishedLibrary()!!.entries.single())
        assertEquals("source.zip", result.entry.installedMetadata.sourceArchiveName)
        assertTrue(repository.loadProfile("A")!!.choices.single().enabled)
        assertEquals(listOf(setOf("A")), invalidations)
    }

    @Test fun `changed context after commit reports library success without saving profile`() {
        prepareParents(); afterLibraryCommit = { currentContext = false }
        val result = install()
        assertFalse(result.profileSelectionSaved)
        assertNotNull(replacement.loadPublishedLibrary())
        assertNull(repository.loadProfile("A"))
    }

    @Test fun `cancel before work leaves no private transaction or catalogue`() {
        prepareParents()
        val controller = InstallCancellationController().also { it.cancel() }
        assertThrows(InstallCancelledException::class.java) { install(signal = controller.signal) }
        assertNull(repository.loadLibrary())
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
    }

    @Test fun `late cancellation keeps committed result and never replays membership during recovery`() {
        prepareParents()
        val controller = InstallCancellationController()
        afterLibraryCommit = { controller.cancel() }
        val result = install(signal = controller.signal)
        assertFalse(result.profileSelectionSaved)
        assertTrue(coordinator.recoverInterruptedInstall())
        assertNull(repository.loadProfile("A"))
        assertEquals(result.entry, repository.loadLibrary()!!.entries.single())
    }

    @Test fun `cancellation while copying removes only this unpublished staging and permits a later explicit install`() {
        prepareParents()
        beforeCopy = { throw InstallCancelledException() }
        assertThrows(InstallCancelledException::class.java) { install() }
        assertNull(repository.loadLibrary())
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
        beforeCopy = {}
        assertTrue(install().profileSelectionSaved)
    }

    @Test fun `context changed while copying discards unpublished bytes without retargeting`() {
        prepareParents()
        beforeCopy = { currentContext = false }
        assertThrows(Exception::class.java) { install() }
        assertNull(repository.loadLibrary()); assertNull(repository.loadProfile("A")); assertNull(repository.loadProfile("B"))
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
    }

    @Test fun `invalid explicit profile priority fails before staging or library commit`() {
        prepareParents()
        assertThrows(Exception::class.java) { install(choice = ProfileModChoice("entry", true, 0)) }
        assertNull(repository.loadLibrary())
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
    }

    @Test fun `uncertain profile save is not replayed and never removes committed bytes`() {
        prepareParents(); profileSaveReportsFailureAfterMove = true
        val result = install()
        assertFalse(result.profileSelectionSaved)
        assertTrue(repository.loadProfile("A")!!.choices.single().enabled)
        val savedBytes = paths.getProfileMembershipFile(gameId, "A").readBytes()
        assertTrue(coordinator.recoverInterruptedInstall())
        assertArrayEquals(savedBytes, paths.getProfileMembershipFile(gameId, "A").readBytes())
        assertEquals(result.entry, replacement.loadPublishedLibrary()!!.entries.single())
    }

    @Test fun `failed derived invalidation cannot report ready views or trigger a misleading index rebuild`() {
        prepareParents(); failInvalidation = true
        val result = install()
        assertFalse(result.derivedViewsReady); assertNotNull(result.indexFailure)
        assertEquals(0, rebuilds)
        assertEquals(result.entry, replacement.loadPublishedLibrary()!!.entries.single())
    }

    @Test fun `add update overlays checked old files without changing profile choices`() {
        prepareParents(); install(); coordinator.finishPrivateInstallCleanup()
        repository.saveProfile("B", ProfileModMembership(gameId, "B", 1, emptyList()), null)
        val oldPayload = paths.getGameLibraryEntryDir(gameId, "entry")
        File(oldPayload, "retained.txt").writeText("old extra file")
        val result = install(choice = null, action = ExistingModAction.ADD_UPDATE)
        assertEquals(2L, result.entry.payloadRevision)
        assertEquals("old extra file", File(oldPayload, "retained.txt").readText())
        assertEquals("new", File(oldPayload, "marker.txt").readText())
    }

    @Test fun `wrong game and A B A context generation cannot retarget a staged install`() {
        prepareParents()
        val review = coordinator.review("entry")
        assertThrows(Exception::class.java) {
            coordinator.install("operation", review.copy(gameId = "other-game"), "A", metadata(), prepared("new"),
                InstallerSelection(emptySet()), ProfileModChoice("entry", true, 1), { true })
        }
        var selectedProfile = "A"
        var generation = 1
        val admittedGeneration = generation
        beforeCopy = {
            selectedProfile = "B"; generation++
            selectedProfile = "A"; generation++
        }
        assertThrows(Exception::class.java) {
            coordinator.install("operation", review, "A", metadata(), prepared("new"), InstallerSelection(emptySet()),
                ProfileModChoice("entry", true, 1), { selectedProfile == "A" && generation == admittedGeneration })
        }
        assertEquals("A", selectedProfile)
        assertNull(repository.loadLibrary())
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
    }

    @Test fun `storage preflight counts repeated source mappings before any payload is copied`() {
        prepareParents()
        val original = prepared("12345678")
        val repeated = original.copy(plan = original.plan.copy(groups = listOf(
            InstallerGroup("main", "Main", InstallerGroupType.SELECT_ANY, listOf(
                InstallerOption("one", "One", sourcePath = ".", destinationPath = "one", required = true),
                InstallerOption("two", "Two", sourcePath = ".", destinationPath = "two", required = true)
            ))
        )))
        assertEquals(16L, installer.libraryCopyBytes(repeated, InstallerSelection(emptySet()), null, InstallCancellationSignal.NONE))
        availableBytes = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES + 8L * GameModLibraryRepository.MAX_RECORD_BYTES + 10L
        val review = coordinator.review("entry")
        assertThrows(Exception::class.java) {
            coordinator.install("operation", review, "A", metadata(), repeated, InstallerSelection(emptySet()),
                ProfileModChoice("entry", true, 1), { true })
        }
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
        assertNull(repository.loadLibrary())
        availableBytes = Long.MAX_VALUE
        val result = coordinator.install("operation", review, "A", metadata(), repeated, InstallerSelection(emptySet()),
            ProfileModChoice("entry", true, 1), { true })
        val directory = File(result.entry.installedMetadata.installPath)
        assertEquals("12345678", File(directory, "one/marker.txt").readText())
        assertEquals("12345678", File(directory, "two/marker.txt").readText())
    }

    @Test fun `unknown or insufficient storage blocks before staging and does not reclaim old bytes`() {
        prepareParents()
        availableBytes = null
        assertThrows(Exception::class.java) { install() }
        availableBytes = 0L
        assertThrows(Exception::class.java) { install() }
        assertFalse(paths.getGameLibraryInstallTransactionDir(gameId).exists())
        assertNull(repository.loadLibrary())
    }
}
