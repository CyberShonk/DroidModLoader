package com.shonkware.droidmodloader.engine.deploy.plan

import com.shonkware.droidmodloader.engine.deploy.ResolvedDeploymentTargets
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import com.shonkware.droidmodloader.engine.storage.StorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageVolumeCapacity
import com.shonkware.droidmodloader.engine.storage.StorageWriteRequirement
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal object DeploymentStorageCapacity {
    private const val MAX_OPERATIONS = 10_000

    fun assess(
        targets: ResolvedDeploymentTargets,
        plan: ScopedDeploymentPlan,
        probe: StorageCapacityProbe
    ): List<StorageVolumeCapacity> = StorageCapacity.assess(requirements(targets, plan), probe)

    /** Conservative copy budget: no reclaimed-byte credit or assumption that restore rename succeeds. */
    internal fun requirements(
        targets: ResolvedDeploymentTargets,
        plan: ScopedDeploymentPlan,
        operationLimit: Int = MAX_OPERATIONS
    ): List<StorageWriteRequirement> {
        require(operationLimit > 0)
        var remaining = operationLimit
        return listOf(targets.data to plan.dataPlan, targets.root to plan.rootPlan).flatMap { (target, scope) ->
            val directory = target.deployDirectory ?: return@flatMap emptyList()
            val ownership = if (target.identity.mode in setOf("simulated", "root_simulated")) {
                "DML managed simulated target"
            } else "Selected target; DML manages only recorded files"
            val copies = Budget("${target.targetType.displayName} deployment copies", directory, ownership)
            val backups = Budget("${target.targetType.displayName} new backups", target.backupDirectory, "DML managed backup directory")
            val restoreExtra = Budget("${target.targetType.displayName} restore fallback", directory, ownership)
            val inspected = scope.operations.take(remaining)
            remaining -= inspected.size
            inspected.forEach { operation ->
                when (operation.type) {
                    DeploymentPlanOperationType.ADD,
                    DeploymentPlanOperationType.UPDATE,
                    DeploymentPlanOperationType.FORCE_REWRITE -> {
                        copies.add(operation.newRecord?.let { size(File(it.sourceFilePath)) })
                        if (operation.oldRecord == null) {
                            val originalSize = try {
                                size(DirectPathValidator().resolveContainedChild(directory.path, operation.normalizedPath), missingIsZero = true)
                            } catch (_: Exception) { null }
                            // First adoption backs up even an identical existing target. Existing backups are not copied again.
                            if (originalSize != 0L) backups.add(originalSize)
                        }
                    }
                    DeploymentPlanOperationType.RESTORE_BACKUP -> {
                        val restoreSize = try {
                            val backup = File(checkNotNull(operation.oldRecord?.backupFilePath)).canonicalFile
                            val root = target.backupDirectory.canonicalFile
                            require(backup != root && backup.toPath().startsWith(root.toPath()))
                            size(backup)
                        } catch (_: Exception) { null }
                        copies.add(restoreSize)
                        // Restores are sequential. Only the largest fallback needs a second simultaneous copy.
                        restoreExtra.maximum(restoreSize)
                    }
                    DeploymentPlanOperationType.REMOVE -> Unit
                }
            }
            if (inspected.size < scope.operations.size) {
                listOf(copies, backups, restoreExtra).forEach { it.unknown("Operation accounting limit reached.") }
            }
            listOf(copies, backups, restoreExtra).mapNotNull { it.requirement() }
        }
    }

    private fun size(file: File, missingIsZero: Boolean = false): Long? = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isRegularFile && file.canRead()) attributes.size() else null
    } catch (_: NoSuchFileException) {
        if (missingIsZero && !Files.isSymbolicLink(file.toPath())) 0L else null
    } catch (_: Exception) { null }

    private class Budget(private val label: String, private val path: File, private val ownership: String) {
        private var touched = false
        private var hasKnownBytes = false
        private var knownBytes = 0L
        private var complete = true
        private val reasons = mutableSetOf<String>()

        fun add(bytes: Long?) {
            touched = true
            if (bytes == null) {
                unknown("Some file sizes are missing, unreadable or unsafe to inspect.")
            } else {
                hasKnownBytes = true
                try {
                    knownBytes = Math.addExact(knownBytes, bytes)
                } catch (_: ArithmeticException) {
                    unknown("Copy budget overflowed; only a partial lower bound remains.")
                }
            }
        }

        fun maximum(bytes: Long?) {
            touched = true
            if (bytes == null) unknown("Restore fallback size is unknown.") else {
                hasKnownBytes = true
                knownBytes = maxOf(knownBytes, bytes)
            }
        }

        fun unknown(reason: String) {
            touched = true
            complete = false
            reasons.add(reason)
        }

        fun requirement(): StorageWriteRequirement? = if (!touched) null else StorageWriteRequirement(
            label, path, ownership, knownBytes.takeIf { hasKnownBytes }, complete && hasKnownBytes,
            reasons.joinToString(" ")
        )
    }
}
