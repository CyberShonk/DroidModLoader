package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalDirectoryMove
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.InstalledModRecordRepository
import com.shonkware.droidmodloader.engine.data.ProfileOverwriteRepository
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.model.*
import com.shonkware.droidmodloader.engine.service.ModLibraryService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

internal class MigrationExecutionTestFixture(
    temporaryFolder: TemporaryFolder,
    val profileIds: List<String> = listOf("profile"),
    withOverwrite: Boolean = false,
    withInstallations: Boolean = true,
    sourceUrlOverride: String? = null,
    private val payloadFilename: String = "payload.ini",
    indexHashOverride: String? = null
) {
    val internal = temporaryFolder.newFolder()
    val external = temporaryFolder.newFolder()
    val target = temporaryFolder.newFolder()
    val paths = ProfileStoragePaths(internal, { null }, { "game" })
    val inventory = GameLibraryMigrationInventory(internal, external, "game")
    val archive = File(external, "downloads/archive_library/original.zip").apply { parentFile!!.mkdirs(); writeText("protected original archive") }
    val protectedFiles = linkedMapOf<File, ByteArray>()
    val plan: GameLibraryMigrationPlan
    val record: MigrationExecutionRecord
    val store = GameLibraryMigrationExecutionStore(paths, "game", inventory)
    val receipt: File
    val payload get() = File(paths.getLegacyProfileInternalDir(profileIds.first()), "mods/mod/$payloadFilename")
    val sourceReader: ModLibraryService

    init {
        fun protect(file: File, content: String) {
            file.parentFile!!.mkdirs(); file.writeText(content); protectedFiles[file] = file.readBytes()
        }
        protect(File(target, "Data/protected.bin"), "game target bytes")
        protectedFiles[archive] = archive.readBytes()
        File(external, "state/profiles.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONArray(profileIds.map { id -> JSONObject().put("profileId", id).put("profileName", id)
                .put("gameId", "game").put("gameDisplayName", "Game").put("targetDataPath", File(target, "Data").absolutePath) }).toString())
        }
        profileIds.forEachIndexed { index, profileId ->
            val directory = paths.getLegacyProfileInternalDir(profileId)
            val state = File(external, "state/profiles/${directory.name}")
            if (withInstallations) {
                val mod = File(directory, "mods/mod").apply { mkdirs() }
                File(mod, payloadFilename).writeText("payload")
                InstalledModRecordRepository().saveRecord(mod, InstalledModRecord("mod", "Mod", mod.absolutePath,
                    "archive", "original.zip", 1, sourceUrl = sourceUrlOverride ?: archive.toURI().toString()))
                File(state, "installed_mods.json").apply {
                    parentFile!!.mkdirs()
                    writeText(JSONArray().put(JSONObject().put("id", "mod").put("name", "Mod").put("installPath", mod.absolutePath)
                        .put("enabled", index > 0).put("priority", index * 10 + 1).put("modType", "ARCHIVE")).toString())
                }
                val indexRepository = ModFileIndexRepository(File(state, "mod_file_indexes"))
                val snapshot = ModFileIndexService(indexRepository).rebuildIndex(
                    Mod("mod", "Mod", mod.absolutePath, index > 0, index * 10 + 1, ModType.ARCHIVE))
                if (indexHashOverride != null) indexRepository.save(snapshot.copy(entries = snapshot.entries.map { it.copy(hash = indexHashOverride) }))
            }
            protect(File(directory, "retained-settings"), "internal protected settings")
            protect(File(state, "plugins.txt"), "disabled.esp\r\n*enabled.esp\r\n")
            protect(File(state, "ini/game.ini"), "[General]\r\nOriginal=true\r\n")
            protect(File(state, "deployment_manifest.json"), "protected target evidence")
            if (withOverwrite) {
                paths.getProfileOverwriteDirectory("game", profileId).mkdirs()
                val overwrite = ProfileOverwriteRepository(paths, "game", profileId)
                val empty = ProfileOverwriteRecord("game", profileId, 1, 1, emptyList())
                overwrite.save(empty, null)
                overwrite.save(empty.copy(recordRevision = 2, pendingBatch = PendingOverwriteBatch("fixture", OverwriteBatchPhase.PREPARING)), 1)
                val generated = File(paths.getProfileOverwriteRoleDirectory("game", profileId, DeployScope.DATA), "generated.ini")
                protect(generated, "generated settings")
                overwrite.save(ProfileOverwriteRecord("game", profileId, 3, 2,
                    listOf(ProfileOverwriteFile(DeployScope.DATA, "generated.ini", migrationHash(generated.readBytes()), generated.length()))), 2)
                val owner = paths.getProfileOverwriteOwnerFile("game", profileId)
                protectedFiles[owner] = owner.readBytes()
            }
        }
        plan = inventory.prepare()
        receipt = paths.getGameLibraryMigrationFile("game").apply { parentFile!!.mkdirs() }
        GameLibraryMigrationPlanRepository(paths, "game", inventory).save(plan)
        record = MigrationExecutionRecord(plan, MigrationExecutionPhase.PREPARED,
            plan.installations.map { MigrationEntryProgress(it.installationId) },
            plan.profiles.map { profile -> MigrationProfileRemainder(profile.profileId,
                MigrationTreeVerifier().captureProfileRemainder(paths.getLegacyProfileInternalDir(profile.profileId),
                    plan.installations.filter { it.profileId == profile.profileId }.map { it.legacyModId }.toSet()).sha256) },
            emptyList(), emptyList())
        val state = File(external, "state/profiles/${paths.getLegacyProfileInternalDir(profileIds.first()).name}")
        sourceReader = ModLibraryService(File(internal, "unused-temp"), File(internal, "unused-mods"),
            File(state, "installed_mods.json"), File(state, "deployment_manifest.json"), File(external, "unused-target"),
            File(state, "game_deployment_configs.json"), File(state, "plugins.json"), File(state, "plugins.txt"), File(state, "loadorder.txt"))
    }

    fun executor(
        operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations,
        directoryMove: CriticalDirectoryMove = CriticalDirectoryMove(),
        availableBytes: (File) -> Long? = { Long.MAX_VALUE },
        contextStillCurrent: () -> Boolean = { true },
        deleteOwnedPath: (File) -> Unit = { Files.delete(it.toPath()) }
    ) = GameLibraryMigrationExecutor(paths, "game", inventory, sourceReader, contextStillCurrent,
        operations, directoryMove, availableBytes, deleteOwnedPath)

    fun publishedLibrary() = GameModLibraryRepository(paths, "game").loadLibrary()
}
