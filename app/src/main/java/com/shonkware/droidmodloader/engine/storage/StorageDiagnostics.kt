package com.shonkware.droidmodloader.engine.storage

import java.time.Instant

internal data class StorageDiagnostics(
    val observedAtMillis: Long,
    val volumes: List<StorageVolumeCapacity>,
    val archives: ManagedArchiveInventory,
    val notes: List<String>
) {
    fun toSummary(): String = buildString {
        appendLine("Storage facts (historical observation at ${Instant.ofEpochMilli(observedAtMillis)})")
        appendLine("Available bytes already account for occupied files. Inventory bytes are not added to free space.")
        volumes.forEachIndexed { index, volume ->
            appendLine()
            appendLine("Volume group ${index + 1}: ${when (volume.status) {
                StorageCapacityStatus.INSUFFICIENT -> "known copy budget exceeds measured space"
                StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES -> "measured copy budget fits"
                StorageCapacityStatus.UNKNOWN -> "requirements or capacity unknown"
            }}")
            appendLine(volume.toDebugSummary())
        }
        appendLine()
        appendLine(archives.toSummary())
        appendLine("Inventory is metadata only, not archive validity, allocated disk size or deletion eligibility.")
        notes.forEach { appendLine(it) }
        append("No managed content or archive files were changed. Remeasure after changing files, permissions or targets.")
    }
}
