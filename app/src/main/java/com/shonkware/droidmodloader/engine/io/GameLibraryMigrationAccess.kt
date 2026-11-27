package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import java.io.File

/** Narrow captured access to the normal repositories during publication/reload.
 * Cannot be constructed from a caller's Boolean or retained across receipt changes.
 * Caller still holds exclusive session admission and validates its context generation. */
internal class MigrationVerificationAccess private constructor(
    private val recordPath: String,
    private val gameId: String,
    private val receiptHash: String,
    internal val phase: MigrationExecutionPhase
) {
    companion object {
        fun capture(paths: ProfileStoragePaths, gameId: String, expected: MigrationExecutionRecord): MigrationVerificationAccess {
            require(expected.phase in setOf(MigrationExecutionPhase.PUBLISHING, MigrationExecutionPhase.RELOADING))
            val snapshot = checkNotNull(GameLibraryMigrationAccess.read(paths, gameId)) { "Migration receipt is missing" }
            check(snapshot.execution == expected) { "Migration verification scope changed" }
            checkNotNull(snapshot.store).confirmPersistence(expected)
            return MigrationVerificationAccess(paths.getGameLibraryMigrationFile(gameId).absolutePath, gameId, snapshot.sha256, expected.phase)
        }
    }

    internal fun requireMatching(paths: ProfileStoragePaths, gameId: String, snapshot: MigrationAccessSnapshot): MigrationExecutionRecord {
        check(this.gameId == gameId && recordPath == paths.getGameLibraryMigrationFile(gameId).absolutePath && receiptHash == snapshot.sha256) {
            "Migration verification access is stale or belongs to another game"
        }
        return checkNotNull(snapshot.execution).also { check(it.phase == phase) { "Migration verification phase changed" } }
    }
}

internal data class MigrationAccessSnapshot(
    val plan: GameLibraryMigrationPlan,
    val execution: MigrationExecutionRecord?,
    val sha256: String,
    val store: GameLibraryMigrationExecutionStore?
)

/** Game-layout barrier for staged shared access. Active legacy startup/factory/UI
 * integration remains a later coherent switch. Pure PLANNED has not moved anything;
 * its inventory must be revalidated before execution. Only COMPLETE releases an
 * execution barrier, including when a different profile is selected. */
internal object GameLibraryMigrationAccess {
    internal fun read(
        paths: ProfileStoragePaths,
        gameId: String,
        operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations
    ): MigrationAccessSnapshot? {
        val text = CriticalMetadataFile(paths.getGameLibraryMigrationFile(gameId), operations)
            .read(GameLibraryMigrationPlanRepository.MAX_BYTES)
        if (text == null) {
            check(!migrationExecutionGuardPresent(paths, gameId)) { "Required migration receipt is missing; ordinary access is blocked" }
            return null
        }
        val json = ModLibraryJsonReader.read(text)
        val version = json.requiredInt("schemaVersion")
        require(version == 1 || version == 2) { "Unsupported migration layout receipt; preserve evidence" }
        val planJson = if (version == 1) json else ModLibraryJsonReader.read(json.requiredString("plan"))
        // Version1 binds this exact logical-profile source. Version2 preserves that
        // binding, allowing strict record decoding without re-inventorying moved data.
        // No external document contents are read by the access decision itself.
        val profileFile = planJson.getJSONArray("inputs").objects().filter {
            it.requiredString("kind") == "FILE" && File(it.requiredString("path")).let { file ->
                file.name == "profiles.json" && file.parentFile?.name == "state"
            }
        }.single().requiredString("path").let(::File)
        require(profileFile.isAbsolute && profileFile == profileFile.canonicalFile)
        val externalRoot = checkNotNull(profileFile.parentFile?.parentFile)
        val inventory = GameLibraryMigrationInventory(paths.getManagedInternalDirectory(), externalRoot, gameId)
        val hash = migrationHash(text.toByteArray(Charsets.UTF_8))
        return if (version == 1) {
            check(!migrationExecutionGuardPresent(paths, gameId)) { "Execution guard conflicts with an older preparation; preserve evidence" }
            MigrationAccessSnapshot(GameLibraryMigrationPlanRepository(paths, gameId, inventory, operations).decode(text), null, hash, null)
        } else {
            val store = GameLibraryMigrationExecutionStore(paths, gameId, inventory, operations)
            val record = store.decode(text)
            MigrationAccessSnapshot(record.plan, record, hash, store)
        }
    }

    fun requireOrdinaryAccess(paths: ProfileStoragePaths, gameId: String) {
        val snapshot = read(paths, gameId) ?: return
        val record = snapshot.execution ?: return
        check(record.phase == MigrationExecutionPhase.COMPLETE) { "Game library migration is unfinished; ordinary access is blocked" }
        checkNotNull(snapshot.store).confirmPersistence(record)
    }

    fun requireReadAccess(
        paths: ProfileStoragePaths,
        gameId: String,
        access: MigrationVerificationAccess?
    ): MigrationExecutionRecord? {
        if (access == null) { requireOrdinaryAccess(paths, gameId); return null }
        val snapshot = checkNotNull(read(paths, gameId)) { "Migration receipt is missing" }
        checkNotNull(snapshot.store).requireExecutionGuard()
        return access.requireMatching(paths, gameId, snapshot)
    }

    fun requirePublicationAccess(paths: ProfileStoragePaths, gameId: String, access: MigrationVerificationAccess?): MigrationExecutionRecord? {
        val record = requireReadAccess(paths, gameId, access)
        if (access != null) check(record?.phase == MigrationExecutionPhase.PUBLISHING) { "Migration access does not permit publication" }
        return record
    }

    fun requireReloadAccess(paths: ProfileStoragePaths, gameId: String, profileId: String, access: MigrationVerificationAccess?) {
        val record = requireReadAccess(paths, gameId, access)
        if (access != null) {
            check(record?.phase == MigrationExecutionPhase.RELOADING && record.plan.profiles.any { it.profileId == profileId }) {
                "Migration access does not permit this profile reload"
            }
        }
    }
}
