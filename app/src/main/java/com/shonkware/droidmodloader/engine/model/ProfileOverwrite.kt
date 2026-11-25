package com.shonkware.droidmodloader.engine.model

import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry

/** Private profile content, never a toggleable installation in the shared catalogue. */
internal data class ProfileOverwriteFile(
    val role: DeployScope,
    val originalPath: String,
    val hash: String,
    val sizeBytes: Long
)

/** Preservation evidence only. This does not grant ownership of a physical target. */
internal data class OverwriteCaptureReceipt(
    val captureOperationId: String,
    val targetGenerationId: String,
    val role: DeployScope,
    val originalPath: String,
    val hash: String,
    val contentRevision: Long
)

internal enum class OverwriteBatchPhase { PREPARING, APPLYING, ROLLING_BACK, CLEANUP }

/** Read gate for the later bounded capture executor; no capture plan or moves run here. */
internal data class PendingOverwriteBatch(val operationId: String, val phase: OverwriteBatchPhase)

internal data class ProfileOverwriteRecord(
    val gameId: String,
    val profileId: String,
    val recordRevision: Long,
    val contentRevision: Long,
    val files: List<ProfileOverwriteFile>,
    val receipts: List<OverwriteCaptureReceipt> = emptyList(),
    val pendingBatch: PendingOverwriteBatch? = null
)

internal data class IndexedOverwriteFile(
    val role: DeployScope,
    val indexEntry: ModFileIndexEntry,
    val sourceFilePath: String
)

/** null record is verified fresh/empty, not a silently initialized saved record. */
internal data class VerifiedProfileOverwrite(
    val gameId: String,
    val profileId: String,
    val record: ProfileOverwriteRecord?,
    val files: List<IndexedOverwriteFile>
) {
    val contentRevision: Long get() = record?.contentRevision ?: 0L
}
