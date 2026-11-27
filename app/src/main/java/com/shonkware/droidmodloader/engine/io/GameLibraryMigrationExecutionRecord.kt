package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

internal enum class MigrationExecutionPhase { PREPARED, MOVING, PUBLISHING, RELOADING, COMPLETE }
internal enum class MigrationCleanupKind { FILE, EMPTY_DIRECTORY }

internal data class MigrationEntryProgress(
    val installationId: String,
    val moveIntended: Boolean = false,
    val moveVerified: Boolean = false,
    val metadataIntended: Boolean = false,
    val metadataVerified: Boolean = false
)

internal data class MigrationProfileRemainder(val profileId: String, val sha256: String?)

/** Exact, pre-inventoried obsolete metadata/empty directory. A missing fileKey is
 * not permission to delete using only a familiar name or matching content. */
internal data class MigrationCleanupCandidate(
    val path: String,
    val kind: MigrationCleanupKind,
    val fileKey: String?,
    val modifiedAtMillis: Long,
    val sha256: String?,
    val retired: Boolean = false
)

internal data class MigrationExecutionRecord(
    val plan: GameLibraryMigrationPlan,
    val phase: MigrationExecutionPhase,
    val entries: List<MigrationEntryProgress>,
    val protectedRemainders: List<MigrationProfileRemainder>,
    val reloadedProfileIds: List<String>,
    val cleanup: List<MigrationCleanupCandidate>
)

/** Version2 execution receipt at the same path as version1 PLANNED. This owner
 * validates and persists records; it never moves payloads, publishes normal records,
 * retires evidence or admits ordinary readers. Caller owns exclusive admission.
 * Reads do not re-inventory an old layout which legitimate moves have changed. */
internal class GameLibraryMigrationExecutionStore(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val inventory: GameLibraryMigrationInventory,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations
) {
    private val planRepository = GameLibraryMigrationPlanRepository(paths, gameId, inventory, operations)
    private fun metadata() = CriticalMetadataFile(paths.getGameLibraryMigrationFile(gameId), operations)

    fun load(): MigrationExecutionRecord? = metadata().read(GameLibraryMigrationPlanRepository.MAX_BYTES)?.let(::decode)

    /** Upgrade only an exact saved preparation. The executor must additionally finish
     * source/space/rename-readiness preflight before calling this boundary. */
    fun begin(record: MigrationExecutionRecord) {
        val validated = decode(encode(record))
        require(validated.phase == MigrationExecutionPhase.PREPARED && validated.entries.all { !it.moveIntended })
        require(validated.reloadedProfileIds.isEmpty() && validated.cleanup.none { it.retired })
        check(planRepository.load() == validated.plan) { "Saved migration preparation changed" }
        inventory.requireMatching(validated.plan)
        maximumEncodedBytes(validated)
        metadata().replaceDurably(encode(validated), GameLibraryMigrationPlanRepository.MAX_BYTES)
        ensurePreparedGuard(validated)
    }

    /** Immutable empty namespace retained with the receipt, not another state file.
     * Once present, absence of migration.json is unknown, never fresh-library evidence.
     * Only PREPARED may initialize it; later missing/foreign evidence stays blocked. */
    fun ensurePreparedGuard(expected: MigrationExecutionRecord) {
        require(expected.phase == MigrationExecutionPhase.PREPARED)
        check(load() == expected)
        val directory = paths.getGameLibraryMigrationGuardDirectory(gameId)
        val parent = requireCriticalDirectory(directory.parentFile!!)
        if (!migrationExecutionGuardPresent(paths, gameId)) {
            operations.syncDirectory(parent)
            Files.createDirectory(directory.toPath())
        }
        requireExecutionGuard()
        operations.syncDirectory(directory)
        operations.syncDirectory(parent)
    }

    internal fun requireExecutionGuard() {
        check(migrationExecutionGuardPresent(paths, gameId)) { "Required migration guard disappeared; preserve evidence" }
        Files.newDirectoryStream(paths.getGameLibraryMigrationGuardDirectory(gameId).toPath()).use {
            check(!it.iterator().hasNext()) { "Unknown migration guard content; preserve evidence" }
        }
    }

    fun save(expected: MigrationExecutionRecord, replacement: MigrationExecutionRecord) {
        val validated = decode(encode(replacement))
        requireExecutionGuard()
        check(load() == expected) { "Migration execution receipt changed" }
        require(validated.plan == expected.plan && validated.protectedRemainders == expected.protectedRemainders)
        require(validated.phase.ordinal in expected.phase.ordinal..minOf(expected.phase.ordinal + 1, MigrationExecutionPhase.COMPLETE.ordinal))
        require(validated.entries.zip(expected.entries).all { (next, previous) ->
            next.installationId == previous.installationId &&
                (!previous.moveIntended || next.moveIntended) && (!previous.moveVerified || next.moveVerified) &&
                (!previous.metadataIntended || next.metadataIntended) && (!previous.metadataVerified || next.metadataVerified) &&
                (!next.moveVerified || previous.moveIntended) && (!next.metadataIntended || previous.moveVerified) &&
                (!next.metadataVerified || previous.metadataIntended)
        }) { "Migration progress cannot be undone" }
        require(validated.reloadedProfileIds.containsAll(expected.reloadedProfileIds))
        if (validated.phase == MigrationExecutionPhase.COMPLETE) require(expected.reloadedProfileIds.toSet() == expected.plan.profiles.map { it.profileId }.toSet())
        require(validated.cleanup.size == expected.cleanup.size && validated.cleanup.zip(expected.cleanup).all { (next, previous) ->
            next.copy(retired = previous.retired) == previous && (!previous.retired || next.retired) &&
                (next.retired == previous.retired || expected.phase == MigrationExecutionPhase.COMPLETE)
        }) { "Cleanup ownership or completed progress changed" }
        if (expected.phase == MigrationExecutionPhase.COMPLETE) {
            require(validated.copy(cleanup = expected.cleanup) == expected) { "Completed migration cannot restart execution" }
        }
        metadata().replaceDurably(encode(validated), GameLibraryMigrationPlanRepository.MAX_BYTES)
    }

    /** Readable intent after an uncertain directory barrier is not durable authority. */
    fun confirmPersistence(expected: MigrationExecutionRecord) {
        requireExecutionGuard()
        check(load() == expected) { "Migration receipt changed before persistence confirmation" }
        operations.syncDirectory(requireCriticalDirectory(paths.getGameLibraryMigrationGuardDirectory(gameId)))
        operations.syncDirectory(requireCriticalDirectory(paths.getGameLibraryMigrationFile(gameId).parentFile!!))
        check(load() == expected) { "Migration receipt changed during persistence confirmation" }
    }

    internal fun encode(record: MigrationExecutionRecord): String = JSONObject().apply {
        put("schemaVersion", 2)
        put("externalFilesDirectory", inventory.externalFilesDir.absolutePath)
        put("plan", planRepository.encode(record.plan))
        put("phase", record.phase.name)
        put("entries", JSONArray().apply { record.entries.forEach { entry ->
            put(JSONObject().put("installationId", entry.installationId).put("moveIntended", entry.moveIntended)
                .put("moveVerified", entry.moveVerified).put("metadataIntended", entry.metadataIntended)
                .put("metadataVerified", entry.metadataVerified))
        } })
        put("protectedRemainders", JSONArray().apply { record.protectedRemainders.forEach { remainder ->
            put(JSONObject().put("profileId", remainder.profileId).put("sha256", remainder.sha256 ?: JSONObject.NULL))
        } })
        put("reloadedProfileIds", JSONArray(record.reloadedProfileIds))
        put("cleanup", JSONArray().apply { record.cleanup.forEach { candidate ->
            put(JSONObject().put("path", candidate.path).put("kind", candidate.kind.name)
                .put("fileKey", candidate.fileKey ?: JSONObject.NULL).put("modifiedAtMillis", candidate.modifiedAtMillis)
                .put("sha256", candidate.sha256 ?: JSONObject.NULL).put("retired", candidate.retired))
        } })
    }.toString().also(::requireMigrationRecordSize)

    internal fun decode(text: String): MigrationExecutionRecord {
        requireMigrationRecordSize(text)
        val json = ModLibraryJsonReader.read(text)
        migrationKeys(json, setOf("schemaVersion", "externalFilesDirectory", "plan", "phase", "entries", "protectedRemainders", "reloadedProfileIds", "cleanup"))
        require(json.requiredInt("schemaVersion") == 2) { "Unsupported migration execution version" }
        require(json.requiredString("externalFilesDirectory") == inventory.externalFilesDir.absolutePath)
        val plan = planRepository.decode(json.requiredString("plan"))
        require(plan.inputs.any { it.path == File(inventory.externalFilesDir, "state/profiles.json").absolutePath && it.kind == "FILE" })
        val phase = MigrationExecutionPhase.valueOf(json.requiredString("phase"))
        val entries = json.getJSONArray("entries").objects().map { entry ->
            migrationKeys(entry, setOf("installationId", "moveIntended", "moveVerified", "metadataIntended", "metadataVerified"))
            MigrationEntryProgress(entry.requiredString("installationId"), entry.requiredBoolean("moveIntended"),
                entry.requiredBoolean("moveVerified"), entry.requiredBoolean("metadataIntended"), entry.requiredBoolean("metadataVerified")).also {
                require((!it.moveVerified || it.moveIntended) && (!it.metadataIntended || it.moveVerified) && (!it.metadataVerified || it.metadataIntended))
            }
        }
        require(entries.map { it.installationId } == plan.installations.map { it.installationId })
        if (phase == MigrationExecutionPhase.PREPARED) require(entries.all { !it.moveIntended })
        if (phase.ordinal >= MigrationExecutionPhase.PUBLISHING.ordinal) require(entries.all { it.metadataVerified })
        val remainders = json.getJSONArray("protectedRemainders").objects().map { remainder ->
            migrationKeys(remainder, setOf("profileId", "sha256"))
            MigrationProfileRemainder(remainder.requiredString("profileId"), nullableHash(remainder, "sha256"))
        }
        require(remainders.map { it.profileId } == plan.profiles.map { it.profileId })
        val reloaded = json.getJSONArray("reloadedProfileIds").let { array -> List(array.length()) { array.get(it).also { value -> require(value is String) } as String } }
        require(reloaded.distinct().size == reloaded.size && reloaded.all { profileId -> plan.profiles.any { it.profileId == profileId } })
        if (phase.ordinal < MigrationExecutionPhase.RELOADING.ordinal) require(reloaded.isEmpty())
        if (phase == MigrationExecutionPhase.COMPLETE) require(reloaded.toSet() == plan.profiles.map { it.profileId }.toSet())
        val cleanup = json.getJSONArray("cleanup").objects().map { candidate ->
            migrationKeys(candidate, setOf("path", "kind", "fileKey", "modifiedAtMillis", "sha256", "retired"))
            val path = candidate.requiredString("path")
            inventory.validateInputPath(File(path))
            val kind = MigrationCleanupKind.valueOf(candidate.requiredString("kind"))
            val hash = nullableHash(candidate, "sha256")
            require((kind == MigrationCleanupKind.FILE) == (hash != null))
            val key = if (candidate.isNull("fileKey")) null else candidate.requiredString("fileKey")
            val retired = candidate.requiredBoolean("retired")
            require(!retired || phase == MigrationExecutionPhase.COMPLETE)
            requireCleanupPath(plan, File(path), kind)
            MigrationCleanupCandidate(path, kind, key, candidate.requiredLong("modifiedAtMillis"), hash, retired)
        }
        require(cleanup.map { it.path }.distinct().size == cleanup.size)
        return MigrationExecutionRecord(plan, phase, entries, remainders, reloaded, cleanup)
    }

    private fun requireCleanupPath(plan: GameLibraryMigrationPlan, file: File, kind: MigrationCleanupKind) {
        val allowed = plan.profiles.any { profile ->
            val internal = paths.getLegacyProfileInternalDir(profile.profileId)
            val state = File(inventory.externalFilesDir, "state/profiles/${internal.name}")
            when (kind) {
                MigrationCleanupKind.EMPTY_DIRECTORY -> file == File(internal, "mods") || file == File(state, "mod_file_indexes")
                MigrationCleanupKind.FILE -> file == File(state, "installed_mods.json") ||
                    plan.installations.any { installation ->
                        installation.profileId == profile.profileId && file == ModFileIndexRepository(File(state, "mod_file_indexes"))
                            .getIndexFileForModId(installation.legacyModId)
                    }
            }
        }
        require(allowed) { "Cleanup path is not an exact obsolete metadata/index or empty mods directory" }
    }

    internal fun maximumEncodedBytes(record: MigrationExecutionRecord): Long {
        // Longest phase name, all false booleans (longer than true), and every profile
        // reload identity conservatively bound future progress serialization. No log
        // messages or unbounded diagnostics are persisted in this receipt.
        return encode(record.copy(phase = MigrationExecutionPhase.PUBLISHING,
            entries = record.entries.map { MigrationEntryProgress(it.installationId) },
            reloadedProfileIds = record.plan.profiles.map { it.profileId })).toByteArray(Charsets.UTF_8).size.toLong()
    }

    private fun nullableHash(json: JSONObject, field: String): String? = if (json.isNull(field)) null else
        json.requiredString(field).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
}

internal fun migrationExecutionGuardPresent(paths: ProfileStoragePaths, gameId: String): Boolean = try {
    requireCriticalDirectory(paths.getGameLibraryMigrationGuardDirectory(gameId)); true
} catch (_: NoSuchFileException) { false }
