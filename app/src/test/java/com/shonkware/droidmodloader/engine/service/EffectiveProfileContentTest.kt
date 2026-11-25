package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.LibraryInstallReplacement
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class EffectiveProfileContentTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gameId = "fallout_nv"
    private var activeProfile = "A"
    private var selectedGame = gameId
    private val paths by lazy { ProfileStoragePaths(temporaryFolder.root, { activeProfile }, { selectedGame }) }
    private val libraryRepository by lazy { GameModLibraryRepository(paths, gameId) }
    private val service by lazy {
        fun file(name: String) = File(temporaryFolder.root, name)
        ModLibraryService(file("legacy_temp"), file("legacy_mods"), file("legacy_state.json"), file("legacy_manifest.json"),
            file("legacy_deploy"), file("legacy_game.json"), file("legacy_plugins.json"), file("legacy_plugins.txt"), file("legacy_loadorder.txt"))
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun initializeEmptyLibrary() {
        paths.getGameLibraryCatalogueFile(gameId).parentFile!!.mkdirs()
        libraryRepository.saveLibrary(GameModLibrary(gameId, 1, emptyList()), null)
    }
    private fun install(id: String, relativePath: String, text: String, displayName: String = "Mod $id") {
        paths.getGameLibraryCatalogueFile(gameId).parentFile!!.mkdirs()
        paths.getGameLibraryEntryDir(gameId, id).parentFile!!.mkdirs()
        val old = libraryRepository.loadLibrary()
        val replacement = LibraryInstallReplacement(paths, gameId)
        val stage = replacement.begin().also { it.mkdir() }
        val file = File(stage, relativePath); file.parentFile!!.mkdirs(); file.writeText(text)
        val entry = GameLibraryEntry(id, 1, InstalledModRecord(id, displayName,
            paths.getGameLibraryEntryDir(gameId, id).absolutePath, "imported_zip", "$id.zip", 1))
        val library = GameModLibrary(gameId, (old?.revision ?: 0) + 1, old?.entries.orEmpty() + entry)
        replacement.publish("fixture-$id", old, library, entry, InstallCancellationSignal.NONE)
        replacement.finish()
    }
    private fun choices(profile: String, vararg choices: ProfileModChoice) {
        paths.getProfileMembershipFile(gameId, profile).parentFile!!.mkdirs()
        val old = libraryRepository.loadProfile(profile)
        libraryRepository.saveProfile(profile, ProfileModMembership(gameId, profile, (old?.revision ?: 0) + 1, choices.toList()), old?.revision)
    }
    private fun overwrite(profile: String, path: String, text: String, role: DeployScope = DeployScope.DATA) {
        val repository = ProfileOverwriteRepository(paths, gameId, profile)
        val owner = paths.getProfileOverwriteOwnerFile(gameId, profile)
        owner.parentFile!!.mkdirs()
        listOf(DeployScope.DATA, DeployScope.GAME_ROOT).forEach { paths.getProfileOverwriteRoleDirectory(gameId, profile, it).mkdir() }
        val old = repository.loadRecord() ?: ProfileOverwriteRecord(gameId, profile, 1, 1, emptyList()).also { repository.save(it, null) }
        val pending = old.copy(recordRevision = old.recordRevision + 1, pendingBatch = PendingOverwriteBatch("fixture", OverwriteBatchPhase.APPLYING))
        repository.save(pending, old.recordRevision)
        val file = File(paths.getProfileOverwriteRoleDirectory(gameId, profile, role), path)
        file.parentFile!!.mkdirs(); file.writeText(text)
        val entry = ProfileOverwriteFile(role, path, hash(text), text.toByteArray().size.toLong())
        repository.save(pending.copy(recordRevision = pending.recordRevision + 1, contentRevision = old.contentRevision + 1,
            files = old.files.filterNot { it.role == role && it.originalPath == path } + entry, pendingBatch = null), pending.recordRevision)
    }
    private fun view(profile: String = "A", current: () -> Boolean = { true },
                     signal: InstallCancellationSignal = InstallCancellationSignal.NONE) =
        service.stagedEffectiveSources(paths, gameId, profile, current, signal)

    @Test fun `profile Overwrite wins after maximum library priority without a fake enabled mod`() {
        install("lower", "SomeMod/Settings.ini", "lower bytes")
        install("higher", "SomeMod/Settings.ini", "higher bytes")
        choices("A", ProfileModChoice("lower", true, 1), ProfileModChoice("higher", true, Int.MAX_VALUE))
        overwrite("A", "SomeMod/Settings.ini", "profile bytes")
        val resolved = view().paths.single()
        assertEquals(3, resolved.allProviders.size)
        assertEquals(listOf("Mod lower", "Mod higher"), resolved.displacedProviders.map { it.provider.displayName })
        assertTrue(resolved.winner.provider.identity is EffectiveSourceIdentity.ProfileOverwrite)
        assertNull(resolved.winner.provider.libraryPriority)
        assertEquals(hash("profile bytes"), resolved.winner.indexEntry.hash)
        assertEquals("SomeMod/Settings.ini", resolved.winner.indexEntry.originalPath)
        assertEquals("profile bytes", File(resolved.winner.sourceFilePath).readText())
        assertEquals("higher.zip", resolved.displacedProviders.last().provider.sourceArchiveName)
        assertEquals(setOf("lower", "higher"), libraryRepository.loadLibrary()!!.entries.map { it.installationId }.toSet())
    }

    @Test fun `two profiles keep separate Overwrite and choices even when mutable UI selection changes`() {
        install("shared", "SomeMod/Settings.ini", "shared bytes")
        choices("A", ProfileModChoice("shared", true, 3)); choices("B", ProfileModChoice("shared", false, 8))
        overwrite("A", "SomeMod/Settings.ini", "A bytes"); overwrite("B", "SomeMod/Settings.ini", "B bytes")
        activeProfile = "B"; selectedGame = "other-game"
        val first = view("A"); val second = view("B")
        assertEquals("A bytes", File(first.deployableFiles.single().sourceFilePath).readText())
        assertEquals("B bytes", File(second.deployableFiles.single().sourceFilePath).readText())
        assertEquals(1, first.paths.single().displacedProviders.size); assertTrue(second.paths.single().displacedProviders.isEmpty())
        assertEquals("A", (first.paths.single().winner.provider.identity as EffectiveSourceIdentity.ProfileOverwrite).profileId)
        assertFalse(libraryRepository.loadProfile("B")!!.choices.single().enabled)
    }

    @Test fun `empty library and valid empty profile still expose Overwrite-only plugin inputs without activation or target writes`() {
        initializeEmptyLibrary(); choices("A")
        overwrite("A", "GeneratedPatch.ESP", "plugin fixture bytes")
        val pluginState = temporaryFolder.newFile("legacy_plugins.json").also { it.writeText("unchanged saved plugin choices") }
        val target = temporaryFolder.newFile("game-target.ini").also { it.writeText("unchanged physical file") }
        val membership = paths.getProfileMembershipFile(gameId, "A").readBytes()
        val result = view()
        assertEquals(1, result.pluginCandidates.size)
        assertEquals("GeneratedPatch.ESP", result.pluginCandidates.single().indexEntry.originalPath)
        assertTrue(result.pluginCandidates.single().provider.identity is EffectiveSourceIdentity.ProfileOverwrite)
        assertArrayEquals(membership, paths.getProfileMembershipFile(gameId, "A").readBytes())
        assertEquals("unchanged saved plugin choices", pluginState.readText()); assertEquals("unchanged physical file", target.readText())
        assertFalse(File(temporaryFolder.root, "mod_file_indexes").exists())
    }

    @Test fun `Data source name cannot acquire Root authority or mask another role`() {
        install("root-provider", "ENBLocal.ini", "root library config")
        choices("A", ProfileModChoice("root-provider", true, 1))
        overwrite("A", "ENBLocal.ini", "Data name only")
        val first = view()
        assertEquals(2, first.paths.count { it.path.normalizedPath == "enblocal.ini" })
        assertFalse(first.paths.single { it.path.role == DeployScope.DATA }.winner.contentEntry.isDeployable)
        assertEquals("root library config", File(first.deployableFiles.single().sourceFilePath).readText())
        assertEquals(DeployScope.GAME_ROOT, first.deployableFiles.single().role)
        overwrite("A", "ENBLocal.ini", "explicit private Root source", DeployScope.GAME_ROOT)
        val root = view().deployableFiles.single()
        assertEquals(DeployScope.GAME_ROOT, root.role)
        assertEquals("explicit private Root source", File(root.sourceFilePath).readText())
    }

    @Test fun `literal Data folder inside role-qualified Overwrite is not flattened twice`() {
        install("library", "Data/Settings.ini", "archive Data wrapper")
        choices("A", ProfileModChoice("library", true, 1))
        overwrite("A", "Data/Settings.ini", "literal nested Data folder")
        val result = view()
        assertEquals(setOf("settings.ini", "data/settings.ini"), result.paths.map { it.path.normalizedPath }.toSet())
        assertTrue(result.paths.all { it.displacedProviders.isEmpty() })
        assertEquals("literal nested Data folder", File(result.paths.single { it.path.normalizedPath == "data/settings.ini" }.winner.sourceFilePath).readText())
    }

    @Test fun `canonical Unicode path aliases share one conflict key across library and Overwrite`() {
        install("library", "Config/cafe\u0301.ini", "library bytes")
        choices("A", ProfileModChoice("library", true, 1))
        overwrite("A", "Config/caf\u00e9.ini", "Overwrite bytes")
        val resolved = view().paths.single()
        assertEquals("config/caf\u00e9.ini", resolved.path.normalizedPath)
        assertEquals("Config/cafe\u0301.ini", resolved.displacedProviders.single().indexEntry.originalPath)
        assertEquals("Config/caf\u00e9.ini", resolved.winner.indexEntry.originalPath)
        assertEquals("Overwrite bytes", File(resolved.winner.sourceFilePath).readText())
    }

    @Test fun `real text config payload remains deployable and uses the same conflict projection`() {
        install("uio", "uio/supported.txt", "normal mod data")
        choices("A", ProfileModChoice("uio", true, 1)); overwrite("A", "uio/supported.txt", "profile mod data")
        val resolved = view().paths.single()
        assertTrue(resolved.winner.contentEntry.isDeployable)
        assertEquals(DeployScope.DATA, resolved.path.role)
        assertEquals(hash("profile mod data"), resolved.winner.indexEntry.hash)
        assertEquals(hash("normal mod data"), resolved.displacedProviders.single().indexEntry.hash)
    }

    @Test fun `fresh empty Overwrite is distinct and does not initialize or discover a normal mod named Overwrite`() {
        install("overwrite", "Config.ini", "a regular installation", displayName = "Overwrite")
        choices("A", ProfileModChoice("overwrite", true, 1))
        val result = view()
        assertNull(result.overwriteRecordRevision); assertEquals(0L, result.overwriteContentRevision)
        assertTrue(result.deployableFiles.single().provider.identity is EffectiveSourceIdentity.LibraryInstallation)
        assertFalse(paths.getProfileOverwriteDirectory(gameId, "A").exists())
        overwrite("A", "Config.ini", "actual profile source")
        val next = view()
        assertTrue(next.deployableFiles.single().provider.identity is EffectiveSourceIdentity.ProfileOverwrite)
        assertTrue(next.paths.single().displacedProviders.single().provider.identity is EffectiveSourceIdentity.LibraryInstallation)
    }

    @Test fun `missing invalid or pending source evidence cannot return partial or empty winners`() {
        initializeEmptyLibrary(); choices("A")
        overwrite("A", "Config.ini", "known source")
        val repository = ProfileOverwriteRepository(paths, gameId, "A")
        val original = repository.loadRecord()!!
        repository.save(original.copy(recordRevision = original.recordRevision + 1,
            pendingBatch = PendingOverwriteBatch("unfinished", OverwriteBatchPhase.APPLYING)), original.recordRevision)
        assertThrows(Exception::class.java) { view() }
        val owner = paths.getProfileOverwriteOwnerFile(gameId, "A")
        owner.writeText("invalid metadata")
        assertThrows(Exception::class.java) { view() }
        owner.delete()
        assertThrows(Exception::class.java) { view() } // Nonempty private files are not adopted.
        assertEquals("known source", File(paths.getProfileOverwriteRoleDirectory(gameId, "A", DeployScope.DATA), "Config.ini").readText())
    }

    @Test fun `enabled missing reference blocks incomplete projection but valid empty never enables discovered folders`() {
        initializeEmptyLibrary(); choices("A", ProfileModChoice("missing", true, 1))
        assertThrows(Exception::class.java) { view() }
        choices("A")
        val unknown = paths.getGameLibraryEntryDir(gameId, "unknown"); unknown.mkdirs(); File(unknown, "Unknown.esp").writeText("unowned")
        assertTrue(view().paths.isEmpty())
        assertEquals(emptyList<ProfileModChoice>(), libraryRepository.loadProfile("A")!!.choices)
    }

    @Test fun `unsafe or partially unreadable enabled library cannot become an empty index and old caches are not trusted`() {
        install("library", "Config.ini", "actual payload")
        choices("A", ProfileModChoice("library", true, 1))
        val cache = File(temporaryFolder.root, "mod_file_indexes/library.json").also { it.parentFile!!.mkdirs(); it.writeText("fake cache") }
        assertEquals(hash("actual payload"), view().deployableFiles.single().indexEntry.hash)
        assertEquals("fake cache", cache.readText())
        val outside = temporaryFolder.newFile("outside.ini").also { it.writeText("outside") }
        Files.createSymbolicLink(File(paths.getGameLibraryEntryDir(gameId, "library"), "linked.ini").toPath(), outside.toPath())
        assertThrows(Exception::class.java) { view() }
        assertEquals("fake cache", cache.readText()); assertEquals("outside", outside.readText())
    }

    @Test fun `ambiguous normalized library paths cannot silently replace one another`() {
        install("library", "Data/Config.ini", "wrapped")
        File(paths.getGameLibraryEntryDir(gameId, "library"), "Config.ini").writeText("unwrapped")
        choices("A", ProfileModChoice("library", true, 1))
        assertThrows(Exception::class.java) { view() }
    }

    @Test fun `pure projection rejects foreign and stale Overwrite provider identities`() {
        initializeEmptyLibrary(); choices("A"); overwrite("A", "Config.ini", "A bytes")
        val captured = view(); val file = captured.deployableFiles.single()
        val identities = listOf(EffectiveSourceIdentity.ProfileOverwrite(gameId, "B", captured.overwriteContentRevision),
            EffectiveSourceIdentity.ProfileOverwrite("other-game", "A", captured.overwriteContentRevision),
            EffectiveSourceIdentity.ProfileOverwrite(gameId, "A", captured.overwriteContentRevision + 1))
        identities.forEach { identity ->
            assertThrows(Exception::class.java) {
                com.shonkware.droidmodloader.engine.resolve.ResolvedDataGraphBuilder().buildEffectiveSources(gameId, "A",
                    captured.libraryRevision, captured.membershipRevision, captured.overwriteRecordRevision,
                    captured.overwriteContentRevision, listOf(file.copy(provider = file.provider.copy(identity = identity))))
            }
        }
    }

    @Test fun `A B A generation change cancellation and missing profile cannot retarget source work`() {
        initializeEmptyLibrary(); choices("A"); overwrite("A", "Config.ini", "A bytes")
        var generation = 1; var checks = 0
        val owner = paths.getProfileOverwriteOwnerFile(gameId, "A").readBytes()
        assertThrows(Exception::class.java) { view(current = { checks++; if (checks == 2) generation += 2; generation == 1 }) }
        val controller = InstallCancellationController().also { it.cancel() }
        assertThrows(Exception::class.java) { view(signal = controller.signal) }
        assertThrows(Exception::class.java) { view("B") }
        assertArrayEquals(owner, paths.getProfileOverwriteOwnerFile(gameId, "A").readBytes())
        assertFalse(paths.getProfileOverwriteDirectory(gameId, "B").exists())
    }
}
