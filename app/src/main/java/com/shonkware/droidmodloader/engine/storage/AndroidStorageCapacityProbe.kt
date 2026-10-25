package com.shonkware.droidmodloader.engine.storage

import android.content.Context
import android.os.storage.StorageManager
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Uses the backing-volume UUID, not mount paths or free-byte equality, for grouping. */
internal class AndroidStorageCapacityProbe internal constructor(
    private val volumeIdForPath: (File) -> String,
    private val availableBytesForPath: (File) -> Long?
) : StorageCapacityProbe {
    constructor(context: Context) : this(
        volumeIdForPath = { path ->
            checkNotNull(context.getSystemService(StorageManager::class.java)).getUuidForPath(path).toString()
        },
        availableBytesForPath = ::availableBytes
    )

    override fun read(path: File): StorageCapacityMeasurement {
        val ancestor = try {
            existingAncestor(path)
        } catch (_: Exception) {
            return StorageCapacityMeasurement(details = "Storage path is unavailable or unreadable.")
        }
        val volumeId = try {
            volumeIdForPath(ancestor)
        } catch (_: Exception) {
            null
        }
        val available = try {
            availableBytesForPath(ancestor)?.takeIf { it >= 0L }
        } catch (_: Exception) {
            null
        }
        if (volumeId != null) {
            val identityStillMatches = try {
                volumeIdForPath(ancestor) == volumeId
            } catch (_: Exception) {
                false
            }
            if (!identityStillMatches) {
                return StorageCapacityMeasurement(details = "Storage volume changed or became unavailable during observation.")
            }
        }
        return StorageCapacityMeasurement(volumeId, available, listOfNotNull(
            if (volumeId == null) "Backing volume identity is unknown; no combined sufficiency verdict." else null,
            if (available == null) "Available capacity is unknown." else null
        ).joinToString(" "))
    }

    companion object {
        /** Unlike File.usableSpace, a successful statvfs distinguishes a full volume from failure. */
        fun availableBytes(path: File): Long? = try {
            val stats = Os.statvfs(existingAncestor(path).absolutePath)
            if (stats.f_bavail < 0L || stats.f_frsize <= 0L) null
            else Math.multiplyExact(stats.f_bavail, stats.f_frsize)
        } catch (_: Exception) {
            null
        }

        internal fun existingAncestor(path: File): File {
            var candidate = path.absoluteFile.toPath().normalize()
            while (true) {
                try {
                    Files.readAttributes(candidate, BasicFileAttributes::class.java)
                    return candidate.toFile().canonicalFile
                } catch (missing: NoSuchFileException) {
                    // Do not turn a broken link into evidence about its unrelated parent volume.
                    if (Files.isSymbolicLink(candidate)) throw IOException("Storage path is a broken link.", missing)
                    candidate = candidate.parent ?: throw missing
                }
            }
        }
    }
}
