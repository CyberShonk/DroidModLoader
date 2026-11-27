package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.model.ModType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Staged, synchronous preparation only. All access must be serialized under retained
 * session admission, off the UI thread. Never invoked on startup. No writes, normal
 * record publication, recovery execution, folder adoption or target/archive reads.
 * Invalid/unknown/partial evidence throws and is left untouched. */
internal class GameLibraryMigrationInventory(
    private val filesDir: File,
    internal val externalFilesDir: File,
    private val gameId: String,
    private val verifier: MigrationTreeVerifier = MigrationTreeVerifier()
) {
    private val paths = ProfileStoragePaths(filesDir, { null }, { gameId })

    fun prepare(signal: InstallCancellationSignal = InstallCancellationSignal.NONE): GameLibraryMigrationPlan {
        signal.throwIfCancellationRequested()
        requireCriticalDirectory(filesDir)
        requireCriticalDirectory(externalFilesDir)
        paths.getGameLibraryCatalogueFile(gameId)
        val inputs = linkedMapOf<String, MigrationInputFingerprint>()
        var controlBytes = 0L
        fun reserve(bytes: Long) {
            if (bytes > GameLibraryMigrationPlanRepository.MAX_BYTES - controlBytes) {
                throw IOException("Migration mapping exceeds byte limit before payload moves")
            }
            controlBytes += bytes
        }
        fun read(file: File): String? {
            val text = CriticalMetadataFile(checked(file)).read(GameLibraryMigrationPlanRepository.MAX_BYTES)
            inputs[file.absolutePath] = MigrationInputFingerprint(file.absolutePath, if (text == null) "ABSENT" else "FILE",
                text?.let { migrationHash(it.toByteArray(Charsets.UTF_8)) })
            return text
        }
        fun protectTree(directory: File) {
            val attributes = attributesOrNull(directory)
            if (attributes == null) {
                inputs[directory.absolutePath] = MigrationInputFingerprint(directory.absolutePath, "ABSENT", null)
            } else {
                val digest = verifier.capture(directory, signal)
                inputs[directory.absolutePath] = MigrationInputFingerprint(directory.absolutePath, "TREE", digest.sha256)
            }
        }
        fun children(directory: File): List<File> {
            if (attributesOrNull(directory) == null) return emptyList()
            requireCriticalDirectory(directory)
            val result = mutableListOf<File>()
            var nameBytes = 0L
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (child in stream) {
                    signal.throwIfCancellationRequested()
                    val file = checked(child.toFile())
                    nameBytes += 256L + file.path.length * 8L
                    if (result.size >= 200_000 || nameBytes > 32L * 1024 * 1024) throw IOException("Migration namespace exceeds bounds")
                    result.add(file)
                }
            }
            return result.sortedBy { it.name }
        }
        fun namespace(directory: File, expectedNames: Set<String>) {
            val children = children(directory)
            require(children.all { it.name in expectedNames && attributesOrNull(it)?.isDirectory == true }) {
                "Unknown or orphaned profile storage; preserve evidence"
            }
            inputs[directory.absolutePath] = MigrationInputFingerprint(directory.absolutePath,
                if (attributesOrNull(directory) == null) "ABSENT" else "NAMES",
                if (attributesOrNull(directory) == null) null else migrationHash(JSONArray(children.map { it.name }).toString().toByteArray(Charsets.UTF_8)))
        }

        val stateDirectory = File(externalFilesDir, "state")
        val profilesText = read(File(stateDirectory, "profiles.json")) ?: throw IOException("Logical profile inventory is missing")
        reserve(profilesText.toByteArray(Charsets.UTF_8).size.toLong())
        val logicalProfiles = migrationArray(profilesText).objects()
        val profileFields = setOf("profileId", "profileName", "gameId", "gameDisplayName", "targetDataPath", "targetRootPath",
            "realDeployEnabled", "dataPathReselectionRequired", "rootPathReselectionRequired", "iniPresetId", "targetTreeUri", "targetRootTreeUri")
        val logicalIds = logicalProfiles.map { profile ->
            require(profile.keys().asSequence().all { it in profileFields }) { "Unsupported profile fields" }
            profile.requiredString("profileName")
            profile.requiredString("gameDisplayName")
            profile.requiredString("gameId")
            for (field in listOf("realDeployEnabled", "dataPathReselectionRequired", "rootPathReselectionRequired")) {
                if (profile.has(field)) profile.requiredBoolean(field)
            }
            for (field in listOf("targetDataPath", "targetRootPath", "targetTreeUri", "targetRootTreeUri")) {
                if (profile.has(field)) require(profile.get(field) is String)
            }
            if (profile.has("iniPresetId") && !profile.isNull("iniPresetId")) require(profile.get("iniPresetId") is String)
            profile.requiredString("profileId").also { paths.getProfileMembershipFile(profile.requiredString("gameId"), it) }
        }
        require(logicalIds.distinct().size == logicalIds.size) { "Duplicate logical profile identity" }
        val legacyKeys = logicalIds.map { paths.getLegacyProfileInternalDir(it).name }
        require(legacyKeys.distinct().size == legacyKeys.size) { "Legacy profile storage identities collide" }
        namespace(File(filesDir, "profiles"), legacyKeys.toSet())
        namespace(File(stateDirectory, "profiles"), legacyKeys.toSet())
        val selectedProfiles = logicalProfiles.filter { it.requiredString("gameId") == gameId }.sortedBy { it.requiredString("profileId") }
        require(selectedProfiles.isNotEmpty()) { "Game has no logical profiles to migrate" }

        val markerText = read(File(stateDirectory, "profile_storage_migration_v2.json"))
        val globalNames = listOf("installed_mods.json", "plugins.json", "plugins.txt", "loadorder.txt", "game_deployment_configs.json")
        var hasGlobalState = attributesOrNull(File(filesDir, "mods")) != null
        // Inspect every document without retaining all bounded document bodies at
        // once or short-circuiting past unreadable evidence after the first match.
        for (name in globalNames) {
            if (read(File(stateDirectory, name)) != null) hasGlobalState = true
        }
        if (markerText != null) {
            val marker = ModLibraryJsonReader.read(markerText)
            migrationKeys(marker, setOf("schemaVersion", "profileId", "status", "createdAtEpochMillis"))
            require(marker.requiredInt("schemaVersion") == 1)
            require(marker.requiredString("profileId") in logicalIds)
            marker.requiredLong("createdAtEpochMillis")
            val status = marker.requiredString("status")
            require(status in setOf("migrated_to_active_profile", "no_legacy_state_found", "skipped_profile_already_has_state"))
            require(!hasGlobalState || status == "migrated_to_active_profile") { "Unresolved legacy global state" }
        } else require(!hasGlobalState) { "Unresolved legacy global migration" }
        // Old copy sources remain protected evidence, never additional installations.
        require(children(File(filesDir, "mods")).none { it.name.startsWith("_dml_") }) {
            "Legacy global install recovery requires original-context review"
        }
        protectTree(File(filesDir, "mods"))

        val catalogueFile = paths.getGameLibraryCatalogueFile(gameId)
        require(read(catalogueFile) == null) { "Shared catalogue already exists" }
        require(attributesOrNull(paths.getGameLibraryInstallTransactionDir(gameId)) == null) { "Shared install recovery exists" }
        require(children(catalogueFile.parentFile!!).all { it.name in setOf("mods", "migration.json") }) {
            "Unknown shared-library evidence"
        }
        namespace(File(catalogueFile.parentFile, "mods"), emptySet())
        val entries = mutableListOf<MigrationInstallationPlan>()
        val profiles = mutableListOf<MigrationProfilePlan>()
        val catalogueEntries = JSONArray()
        for (profile in selectedProfiles) {
            signal.throwIfCancellationRequested()
            val profileId = profile.requiredString("profileId")
            val internal = paths.getLegacyProfileInternalDir(profileId)
            val profileState = checked(File(stateDirectory, "profiles/${internal.name}"))
            protectTree(internal)
            protectTree(profileState)
            // A prepared installer may own extracted content. Do not retire or adopt it.
            require(children(File(internal, "temp")).isEmpty()) { "Pending installer content requires original-context recovery" }
            val modsDirectory = File(internal, "mods")
            val stateText = read(File(profileState, "installed_mods.json"))
            stateText?.let { reserve(2L * it.toByteArray(Charsets.UTF_8).size) }
            val legacyMods = stateText?.let(::migrationArray)?.objects() ?: emptyList()
            val modIds = legacyMods.map { it.requiredString("id") }
            require(modIds.distinct().size == modIds.size) { "Duplicate legacy installation identity" }
            require(children(modsDirectory).map { it.name }.toSet() == modIds.toSet()) {
                "Missing, orphaned or unresolved install payload/recovery evidence"
            }
            require(read(paths.getProfileMembershipFile(gameId, profileId)) == null) { "Membership already exists" }
            val overwriteDirectory = paths.getProfileOverwriteDirectory(gameId, profileId)
            val overwriteRecord = ProfileOverwriteRepository(paths, gameId, profileId).loadRecord()
            require(overwriteRecord?.pendingBatch == null) { "Pending Overwrite work requires original-context recovery" }
            if (overwriteRecord == null) {
                require(children(overwriteDirectory).all { role ->
                    role.name in setOf("data", "game_root") && children(role).isEmpty()
                }) { "Unowned Overwrite content is unresolved; preserve evidence" }
            }
            // Snapshot, not source admission: final normal-reader verification of
            // all Overwrite records/content remains part of publication/reload.
            protectTree(overwriteDirectory)
            val choices = JSONArray()
            for (mod in legacyMods) {
                signal.throwIfCancellationRequested()
                migrationKeys(mod, setOf("id", "name", "installPath", "enabled", "priority", "modType"))
                val oldId = mod.requiredString("id")
                require(oldId !in setOf(".", "..") && '/' !in oldId && '\\' !in oldId && '\u0000' !in oldId)
                mod.requiredString("name")
                ModType.valueOf(mod.requiredString("modType"))
                val enabled = mod.requiredBoolean("enabled")
                val priority = mod.requiredInt("priority").also { require(it > 0) { "Legacy priority needs explicit resolution" } }
                val source = checked(File(modsDirectory, oldId))
                require(mod.requiredString("installPath") == source.absolutePath) { "Legacy installation path does not match its owned slot" }
                requireCriticalDirectory(source)
                val metadataText = read(File(source, ".dml_mod.json")) ?: throw IOException("Installed source metadata is missing")
                val metadata = ModLibraryJsonReader.read(metadataText)
                require(metadata.requiredString("modId") == oldId && metadata.requiredString("installPath") == source.absolutePath)
                val identity = "legacy_" + migrationHash(JSONArray(listOf(gameId, profileId, oldId)).toString().toByteArray(Charsets.UTF_8))
                val destination = paths.getGameLibraryEntryDir(gameId, identity)
                require(attributesOrNull(destination) == null) { "Migration destination already exists" }
                var destinationParent = destination.parentFile!!
                while (attributesOrNull(destinationParent) == null) destinationParent = destinationParent.parentFile!!
                require(Files.getFileStore(source.toPath()) == Files.getFileStore(destinationParent.toPath())) { "Migration requires same-volume storage" }
                val tree = verifier.capture(source, signal)
                reserve(4096L + metadataText.toByteArray(Charsets.UTF_8).size * 4L)
                entries.add(MigrationInstallationPlan(profileId, oldId, identity, source.absolutePath, destination.absolutePath, tree, metadataText))
                val candidate = migrationCandidateMetadata(metadataText, identity, destination.absolutePath)
                catalogueEntries.put(JSONObject().put("installationId", identity).put("payloadRevision", 1).put("installedMetadata", candidate))
                choices.put(JSONObject().put("installationId", identity).put("enabled", enabled).put("priority", priority))
            }
            val membership = JSONObject().put("schemaVersion", 1).put("gameId", gameId).put("profileId", profileId)
                .put("revision", 1).put("choices", choices).toString()
            profiles.add(MigrationProfilePlan(profileId, stateText, membership))
        }
        val catalogueText = JSONObject().put("schemaVersion", 1).put("gameId", gameId).put("revision", 1).put("entries", catalogueEntries).toString()
        GameModLibraryRepository(paths, gameId).decodeLibrary(catalogueText)
        val plan = GameLibraryMigrationPlan(gameId, inputs.values.sortedBy { it.path }, profiles, entries, catalogueText)
        val record = GameLibraryMigrationPlanRepository(paths, gameId, this)
        record.decode(record.encode(plan))
        // Catch ordinary concurrent changes during the inventory. Admission remains
        // required: this is not a cross-process snapshot or filesystem lock.
        requireInputsUnchanged(plan, signal)
        return plan
    }

    fun requireMatching(plan: GameLibraryMigrationPlan, signal: InstallCancellationSignal = InstallCancellationSignal.NONE) {
        require(plan.gameId == gameId)
        if (prepare(signal) != plan) throw IOException("Migration inventory changed; preserve the recorded mapping")
    }

    /** Resume validates immutable evidence separately from slots changed by the
     * recorded relocation/publication. The execution owner must verify every omitted
     * slot, profile remainder and normal candidate; this is not a general ignore list. */
    internal fun requireUnmovedInputs(plan: GameLibraryMigrationPlan, signal: InstallCancellationSignal) {
        val changed = mutableSetOf(paths.getGameLibraryCatalogueFile(gameId).absolutePath,
            File(paths.getGameLibraryCatalogueFile(gameId).parentFile, "mods").absolutePath)
        plan.profiles.forEach { profile ->
            changed.add(paths.getLegacyProfileInternalDir(profile.profileId).absolutePath)
            changed.add(paths.getProfileMembershipFile(gameId, profile.profileId).absolutePath)
        }
        plan.installations.forEach { changed.add(File(it.sourcePath, ".dml_mod.json").absolutePath) }
        requireInputsUnchanged(plan, signal, changed)
    }

    private fun requireInputsUnchanged(plan: GameLibraryMigrationPlan, signal: InstallCancellationSignal, changedPaths: Set<String> = emptySet()) {
        for (input in plan.inputs) {
            if (input.path in changedPaths) continue
            signal.throwIfCancellationRequested()
            val file = checked(File(input.path))
            val hash = when (input.kind) {
                "ABSENT" -> { require(attributesOrNull(file) == null) { "Absent migration input appeared" }; null }
                "FILE" -> CriticalMetadataFile(file).read(GameLibraryMigrationPlanRepository.MAX_BYTES)?.let { migrationHash(it.toByteArray(Charsets.UTF_8)) }
                "TREE" -> verifier.capture(file, signal).sha256
                "NAMES" -> {
                    requireCriticalDirectory(file)
                    val names = mutableListOf<String>()
                    var retainedBytes = 0L
                    Files.newDirectoryStream(file.toPath()).use { stream ->
                        for (child in stream) {
                            signal.throwIfCancellationRequested()
                            val name = child.fileName.toString()
                            retainedBytes += 256L + 8L * (file.path.length + name.length)
                            if (names.size >= 200_000 || retainedBytes > 32L * 1024 * 1024) throw IOException("Migration namespace exceeds bounds")
                            names.add(name)
                        }
                    }
                    migrationHash(JSONArray(names.sorted()).toString().toByteArray(Charsets.UTF_8))
                }
                else -> throw IOException("Unsupported migration input kind")
            }
            require(hash == input.sha256) { "Migration input changed during inventory" }
        }
    }

    internal fun validateInputPath(file: File) {
        require(file.isAbsolute) { "Migration input path must be absolute" }
        checked(file)
    }

    private fun checked(file: File): File {
        val absolute = file.absoluteFile
        require(absolute == absolute.canonicalFile &&
            (absolute.toPath().startsWith(filesDir.absoluteFile.toPath()) || absolute.toPath().startsWith(externalFilesDir.absoluteFile.toPath()))) {
            "Migration evidence contains an alias or escapes managed roots"
        }
        return absolute
    }

    private fun attributesOrNull(file: File): BasicFileAttributes? = try {
        Files.readAttributes(checked(file).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
            require(!it.isSymbolicLink && (it.isRegularFile || it.isDirectory)) { "Unsupported migration evidence" }
        }
    } catch (_: NoSuchFileException) { null }
}
