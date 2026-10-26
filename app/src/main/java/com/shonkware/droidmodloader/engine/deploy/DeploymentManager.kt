package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.build.DiffEngine
import com.shonkware.droidmodloader.engine.build.FileChange
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import java.io.File
import java.io.IOException

data class DeploymentResult(
    val addCount: Int,
    val removeCount: Int,
    val updateCount: Int,
    val finalRecordCount: Int,
    val backupCount: Int = 0,
    val restoreCount: Int = 0,
    val protectedConflictCount: Int = 0
)

class DeploymentManager(
    private val deployRootDir: File,
    private val backupRootDir: File = File(
        deployRootDir.parentFile ?: deployRootDir,
        "_dml_deployment_backups/${deployRootDir.name}"
    ),
    private val usableSpaceBytes: (File) -> Long? = AndroidStorageCapacityProbe::availableBytes
) {
    private data class BackupInfo(
        val backupFilePath: String,
        val backupCreatedAtEpochMillis: Long
    )

    private val protectedFolderNames = setOf(
        "data",
        "meshes",
        "textures",
        "scripts",
        "interface",
        "sound",
        "music",
        "strings",
        "video",
        "skse",
        "nvse",
        "obse",
        "f4se",
        "menus",
        "fonts",
        "shaders",
        "videos",
        "fose",
    )

    fun deploy(
        oldRecords: List<DeploymentRecord>,
        newFileRecords: List<FileRecord>
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        deployRootDir.mkdirs()
        backupRootDir.mkdirs()

        val oldByPath = oldRecords.associateBy { it.normalizedPath }

        val oldFileRecords = oldRecords.map {
            FileRecord(
                normalizedPath = it.normalizedPath,
                winningModId = it.winningModId,
                winningModName = it.winningModName,
                sourceFilePath = it.sourceFilePath,
                hash = it.hash
            )
        }

        val diffEngine = DiffEngine()
        val changes = diffEngine.diff(oldFileRecords, newFileRecords)

        var backupCount = 0
        var restoreCount = 0
        var protectedConflictCount = 0

        val changedRecordsByPath = mutableMapOf<String, DeploymentRecord>()

        for (change in changes) {
            when (change) {
                is FileChange.Add -> {
                    val oldRecord = oldByPath[change.record.normalizedPath]
                    val deployedRecord = copyIntoDeployRoot(
                        record = change.record,
                        oldRecord = oldRecord
                    )

                    if (deployedRecord.backupFilePath != null && oldRecord?.backupFilePath == null) {
                        backupCount++
                    }

                    changedRecordsByPath[deployedRecord.normalizedPath] = deployedRecord
                }

                is FileChange.Update -> {
                    val oldRecord = oldByPath[change.newRecord.normalizedPath]
                    val deployedRecord = copyIntoDeployRoot(
                        record = change.newRecord,
                        oldRecord = oldRecord
                    )

                    if (deployedRecord.backupFilePath != null && oldRecord?.backupFilePath == null) {
                        backupCount++
                    }

                    changedRecordsByPath[deployedRecord.normalizedPath] = deployedRecord
                }

                is FileChange.Remove -> {
                    val oldRecord = oldByPath[change.normalizedPath]

                    if (oldRecord != null) {
                        val restored = restoreBackupOrDeleteTarget(oldRecord)
                        if (restored) {
                            restoreCount++
                        }
                    }
                }
            }
        }

        val newDeploymentRecords = newFileRecords
            .map { fileRecord ->
                val changedRecord = changedRecordsByPath[fileRecord.normalizedPath]
                if (changedRecord != null) {
                    changedRecord
                } else {
                    fileRecord.toDeploymentRecord(
                        preservedBackupRecord = oldByPath[fileRecord.normalizedPath]
                    )
                }
            }
            .sortedBy { it.normalizedPath }

        val result = DeploymentResult(
            addCount = changes.count { it is FileChange.Add },
            removeCount = changes.count { it is FileChange.Remove },
            updateCount = changes.count { it is FileChange.Update },
            finalRecordCount = newDeploymentRecords.size,
            backupCount = backupCount,
            restoreCount = restoreCount,
            protectedConflictCount = protectedConflictCount
        )

        return Pair(newDeploymentRecords, result)
    }

    private fun copyIntoDeployRoot(
        record: FileRecord,
        oldRecord: DeploymentRecord?
    ): DeploymentRecord {
        val sourceFile = File(record.sourceFilePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            throw IOException("Source file missing during deployment: ${record.sourceFilePath}")
        }

        val targetFile = resolveInsideDeployRoot(record.normalizedPath)
        targetFile.parentFile?.mkdirs()

        val backupInfo = when {
            oldRecord?.backupFilePath != null -> {
                BackupInfo(
                    backupFilePath = oldRecord.backupFilePath,
                    backupCreatedAtEpochMillis = oldRecord.backupCreatedAtEpochMillis
                        ?: System.currentTimeMillis()
                )
            }

            oldRecord != null -> {
                null
            }

            targetFile.exists() && targetFile.isFile -> {
                backupExistingTargetFile(
                    normalizedPath = record.normalizedPath,
                    targetFile = targetFile
                )
            }

            targetFile.exists() && targetFile.isDirectory -> {
                throw IOException("Target path is a directory, expected file: ${record.normalizedPath}")
            }

            else -> {
                null
            }
        }

        val targetAlreadyMatchesSource =
            oldRecord == null &&
                    backupInfo != null &&
                    filesHaveSameContent(sourceFile, targetFile)

        if (!targetAlreadyMatchesSource) {
            StorageCapacity.requireCopySpace(targetFile, sourceFile.length(), usableSpaceBytes)
            sourceFile.copyTo(targetFile, overwrite = true)
        }

        return record.toDeploymentRecord(
            preservedBackupRecord = oldRecord,
            newBackupInfo = backupInfo
        )
    }

    private fun filesHaveSameContent(
        firstFile: File,
        secondFile: File
    ): Boolean {
        if (firstFile.length() != secondFile.length()) {
            return false
        }

        firstFile.inputStream().buffered().use { firstStream ->
            secondFile.inputStream().buffered().use { secondStream ->
                val firstBuffer = ByteArray(64 * 1024)
                val secondBuffer = ByteArray(64 * 1024)

                while (true) {
                    val firstCount = firstStream.read(firstBuffer)
                    val secondCount = secondStream.read(secondBuffer)

                    if (firstCount != secondCount) {
                        return false
                    }

                    if (firstCount == -1) {
                        return true
                    }

                    for (index in 0 until firstCount) {
                        if (firstBuffer[index] != secondBuffer[index]) {
                            return false
                        }
                    }
                }
            }
        }
    }

    private fun backupExistingTargetFile(
        normalizedPath: String,
        targetFile: File
    ): BackupInfo {
        val backupFile = resolveInsideBackupRoot(normalizedPath)
        StorageCapacity.requireCopySpace(backupFile, targetFile.length(), usableSpaceBytes)
        backupFile.parentFile?.mkdirs()

        targetFile.copyTo(backupFile, overwrite = true)

        return BackupInfo(
            backupFilePath = backupFile.absolutePath,
            backupCreatedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private fun restoreBackupOrDeleteTarget(record: DeploymentRecord): Boolean {
        val targetFile = resolveInsideDeployRoot(record.normalizedPath)

        val backupPath = record.backupFilePath
        if (backupPath.isNullOrBlank()) {
            if (targetFile.exists() && targetFile.isFile) {
                targetFile.delete()
                cleanupEmptyParents(targetFile)
            }

            return false
        }

        val backupFile = File(backupPath)
        if (!backupFile.exists() || !backupFile.isFile) {
            throw IOException(
                "Backup file missing; refusing to delete target without restore: ${record.normalizedPath}"
            )
        }

        targetFile.parentFile?.mkdirs()

        val restoreTemp = File(
            targetFile.parentFile,
            ".dml_restore_${targetFile.name}_${System.currentTimeMillis()}"
        )

        val restoreBudget = try {
            Math.multiplyExact(backupFile.length(), 2L)
        } catch (failure: ArithmeticException) {
            throw IOException("Restore copy budget overflowed.", failure)
        }
        StorageCapacity.requireCopySpace(restoreTemp, restoreBudget, usableSpaceBytes)
        backupFile.copyTo(restoreTemp, overwrite = true)
        StorageCapacity.requireCopySpace(targetFile, backupFile.length(), usableSpaceBytes)

        if (targetFile.exists() && targetFile.isFile) {
            targetFile.delete()
        }

        if (!restoreTemp.renameTo(targetFile)) {
            StorageCapacity.requireCopySpace(targetFile, restoreTemp.length(), usableSpaceBytes)
            restoreTemp.copyTo(targetFile, overwrite = true)
            restoreTemp.delete()
        }

        backupFile.delete()
        cleanupEmptyParents(backupFile)
        cleanupEmptyParents(targetFile)

        return true
    }

    private fun FileRecord.toDeploymentRecord(
        preservedBackupRecord: DeploymentRecord?,
        newBackupInfo: BackupInfo? = null
    ): DeploymentRecord {
        val backupFilePath = newBackupInfo?.backupFilePath
            ?: preservedBackupRecord?.backupFilePath

        val backupCreatedAt = newBackupInfo?.backupCreatedAtEpochMillis
            ?: preservedBackupRecord?.backupCreatedAtEpochMillis

        return DeploymentRecord(
            normalizedPath = normalizedPath,
            winningModId = winningModId,
            sourceFilePath = sourceFilePath,
            winningModName = winningModName,
            hash = hash,
            hadPreExistingTargetFile = backupFilePath != null ||
                    preservedBackupRecord?.hadPreExistingTargetFile == true,
            backupFilePath = backupFilePath,
            backupCreatedAtEpochMillis = backupCreatedAt
        )
    }

    private fun resolveInsideDeployRoot(normalizedPath: String): File {
        val target = File(deployRootDir, normalizedPath)
        val rootPath = deployRootDir.canonicalPath + File.separator
        val targetPath = target.canonicalPath

        if (!targetPath.startsWith(rootPath)) {
            throw SecurityException("Blocked unsafe deployment path: $normalizedPath")
        }

        return target
    }

    private fun resolveInsideBackupRoot(normalizedPath: String): File {
        val target = File(backupRootDir, normalizedPath)
        val rootPath = backupRootDir.canonicalPath + File.separator
        val targetPath = target.canonicalPath

        if (!targetPath.startsWith(rootPath)) {
            throw SecurityException("Blocked unsafe backup path: $normalizedPath")
        }

        return target
    }

    private fun cleanupEmptyParents(startFile: File) {
        val roots = listOf(
            deployRootDir.canonicalFile,
            backupRootDir.canonicalFile
        )

        var current = startFile.parentFile?.canonicalFile

        while (current != null) {
            val root = roots.firstOrNull { current == it || current.canonicalPath.startsWith(it.canonicalPath + File.separator) }
                ?: return

            if (current == root) return

            val children = current.listFiles()
            if (children != null && children.isNotEmpty()) return

            if (protectedFolderNames.contains(current.name.lowercase())) return

            val parent = current.parentFile?.canonicalFile
            current.delete()
            current = parent
        }
    }
}