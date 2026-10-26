package com.shonkware.droidmodloader.engine.storage

import com.shonkware.droidmodloader.engine.install.ExtractionLimits
import java.io.File
import java.io.IOException

/** A point-in-time observation, never a reservation or permission to write. */
internal data class StorageCapacityMeasurement(
    val volumeId: String? = null,
    val availableBytes: Long? = null,
    val details: String = ""
) {
    init {
        require(volumeId == null || volumeId.isNotBlank())
        require(availableBytes == null || availableBytes >= 0L)
    }
}

internal fun interface StorageCapacityProbe {
    fun read(path: File): StorageCapacityMeasurement
}

/** Known portion of a phase's new-copy budget, not exact physical allocation or net disk growth. */
internal data class StorageWriteRequirement(
    val label: String,
    val path: File,
    val ownership: String,
    val minimumBytes: Long? = null,
    val complete: Boolean = false,
    val details: String = ""
) {
    init {
        require(minimumBytes == null || minimumBytes >= 0L)
        require(!complete || minimumBytes != null)
    }
}

internal data class StorageLocationCapacity(
    val requirement: StorageWriteRequirement,
    val measurement: StorageCapacityMeasurement
)

internal enum class StorageCapacityStatus {
    INSUFFICIENT,
    ENOUGH_FOR_MEASURED_BYTES,
    UNKNOWN
}

internal data class StorageVolumeCapacity(
    val volumeId: String?,
    val locations: List<StorageLocationCapacity>,
    val availableBytes: Long?,
    val minimumRequiredBytes: Long?,
    val requirementComplete: Boolean,
    val reserveBytes: Long,
    val status: StorageCapacityStatus,
    val details: String = ""
) {
    fun toDebugSummary(): String = buildString {
        appendLine("Backing volume: ${volumeId ?: "unknown"}")
        appendLine("Available: ${availableBytes?.let { "$it bytes" } ?: "unknown"}")
        val budget = when {
            requirementComplete -> minimumRequiredBytes?.let { "$it bytes" } ?: "unknown"
            minimumRequiredBytes != null && minimumRequiredBytes > 0L -> "at least $minimumRequiredBytes bytes (incomplete)"
            else -> "unknown"
        }
        appendLine("Copy budget: $budget; headroom: $reserveBytes bytes")
        locations.forEach { location ->
            val requirement = location.requirement
            appendLine("${requirement.label}: ${requirement.path.absolutePath}; ${requirement.ownership}")
            if (requirement.details.isNotBlank()) appendLine(requirement.details)
            if (location.measurement.details.isNotBlank()) appendLine(location.measurement.details)
        }
        if (details.isNotBlank()) appendLine(details)
        append("An observation, not a reservation. Copy budgets exclude filesystem overhead and do not credit reclaimed bytes.")
    }
}

internal object StorageCapacity {
    fun requireCopySpace(path: File, bytes: Long, probe: (File) -> Long?) {
        require(bytes >= 0L)
        val available = try { probe(path)?.takeIf { it >= 0L } } catch (_: Exception) { null } ?: return
        val headroom = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES
        if (available < headroom || bytes > available - headroom) {
            throw IOException("Not enough free storage remains to copy files safely.")
        }
    }

    /** Requirements must describe simultaneous writes, not successive alternative phases. */
    fun assess(
        requirements: List<StorageWriteRequirement>,
        probe: StorageCapacityProbe,
        reserveBytes: Long = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES
    ): List<StorageVolumeCapacity> {
        require(reserveBytes >= 0L)
        val measurements = requirements.map { it.path }.distinct().associateWith { path ->
            try {
                probe.read(path)
            } catch (_: Exception) {
                StorageCapacityMeasurement(details = "Capacity probe unavailable.")
            }
        }
        val locations = requirements.map { StorageLocationCapacity(it, measurements.getValue(it.path)) }
        // No identity means no grouping, even when paths or available-byte values look alike.
        return locations.withIndex().groupBy { (index, location) ->
            location.measurement.volumeId?.let { it to -1 } ?: (null to index)
        }.values.map { entries ->
            val group = entries.map { it.value }
            val volumeId = group.first().measurement.volumeId
            val available = if (group.all { it.measurement.availableBytes != null }) {
                group.minOf { checkNotNull(it.measurement.availableBytes) }
            } else null
            val knownRequirements = group.mapNotNull { it.requirement.minimumBytes }
            var overflow = false
            val required = try {
                knownRequirements.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact)
            } catch (_: ArithmeticException) {
                overflow = true
                null
            }
            val complete = !overflow && group.all { it.requirement.complete }
            val status = when {
                available != null && required != null &&
                    (available < reserveBytes || required > available - reserveBytes) ->
                    StorageCapacityStatus.INSUFFICIENT
                volumeId != null && available != null && required != null && complete ->
                    StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES
                else -> StorageCapacityStatus.UNKNOWN
            }
            StorageVolumeCapacity(volumeId, group, available, required, complete, reserveBytes, status,
                if (overflow) "Required byte accounting overflowed; total is unknown." else "")
        }
    }
}
