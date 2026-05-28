package com.shonkware.droidmodloader.engine.deploy.plan

import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import java.io.File

class DeploymentPlanBuilder {

    fun build(
        scope: DeploymentPlanScope,
        oldManifest: List<DeploymentRecord>,
        newWinningRecords: List<FileRecord>
    ): DeploymentPlan {
        val oldByPath = oldManifest.associateBy { it.normalizedPath }
        val newByPath = newWinningRecords.associateBy { it.normalizedPath }

        val allPaths = (oldByPath.keys + newByPath.keys).sorted()

        val operations = mutableListOf<DeploymentPlanOperation>()

        for (path in allPaths) {
            val oldRecord = oldByPath[path]
            val newRecord = newByPath[path]

            when {
                oldRecord == null && newRecord != null -> {
                    operations.add(
                        DeploymentPlanOperation(
                            type = DeploymentPlanOperationType.ADD,
                            normalizedPath = path,
                            newRecord = newRecord,
                            oldRecord = null,
                            winningModName = newRecord.winningModName,
                            sourceSizeBytes = getSourceSize(newRecord),
                            reason = "File is newly provided by the current resolved mod list."
                        )
                    )
                }

                oldRecord != null && newRecord == null -> {
                    val operationType =
                        if (oldRecord.hadPreExistingTargetFile && !oldRecord.backupFilePath.isNullOrBlank()) {
                            DeploymentPlanOperationType.RESTORE_BACKUP
                        } else {
                            DeploymentPlanOperationType.REMOVE
                        }

                    val reason =
                        if (operationType == DeploymentPlanOperationType.RESTORE_BACKUP) {
                            "Managed file is no longer needed, and a previous target file should be restored."
                        } else {
                            "Managed file is no longer needed and should be removed."
                        }

                    operations.add(
                        DeploymentPlanOperation(
                            type = operationType,
                            normalizedPath = path,
                            newRecord = null,
                            oldRecord = oldRecord,
                            winningModName = oldRecord.winningModName,
                            sourceSizeBytes = null,
                            reason = reason
                        )
                    )
                }

                oldRecord != null && newRecord != null && hasChanged(oldRecord, newRecord) -> {
                    operations.add(
                        DeploymentPlanOperation(
                            type = DeploymentPlanOperationType.UPDATE,
                            normalizedPath = path,
                            newRecord = newRecord,
                            oldRecord = oldRecord,
                            winningModName = newRecord.winningModName,
                            sourceSizeBytes = getSourceSize(newRecord),
                            reason = buildUpdateReason(oldRecord, newRecord)
                        )
                    )
                }
            }
        }

        return DeploymentPlan(
            scope = scope,
            operations = operations
        )
    }

    private fun hasChanged(
        oldRecord: DeploymentRecord,
        newRecord: FileRecord
    ): Boolean {
        return oldRecord.hash != newRecord.hash ||
                oldRecord.winningModId != newRecord.winningModId ||
                oldRecord.sourceFilePath != newRecord.sourceFilePath
    }

    private fun buildUpdateReason(
        oldRecord: DeploymentRecord,
        newRecord: FileRecord
    ): String {
        val reasons = mutableListOf<String>()

        if (oldRecord.hash != newRecord.hash) {
            reasons.add("content hash changed")
        }

        if (oldRecord.winningModId != newRecord.winningModId) {
            reasons.add("winning mod changed")
        }

        if (oldRecord.sourceFilePath != newRecord.sourceFilePath) {
            reasons.add("source file changed")
        }

        return if (reasons.isEmpty()) {
            "File changed."
        } else {
            "File update needed because ${reasons.joinToString(", ")}."
        }
    }

    private fun getSourceSize(record: FileRecord): Long? {
        val source = File(record.sourceFilePath)

        if (!source.exists() || !source.isFile) {
            return null
        }

        return source.length()
    }
}