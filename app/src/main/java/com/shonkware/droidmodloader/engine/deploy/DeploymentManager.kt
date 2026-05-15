package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.build.DiffEngine
import com.shonkware.droidmodloader.engine.build.FileChange
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import java.io.File

data class DeploymentResult(
    val addCount: Int,
    val removeCount: Int,
    val updateCount: Int,
    val finalRecordCount: Int
)

class DeploymentManager(
    private val deployRootDir: File
) {
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
        "f4se"
    )

    fun deploy(
        oldRecords: List<DeploymentRecord>,
        newFileRecords: List<FileRecord>
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        deployRootDir.mkdirs()

        val oldFileRecords = oldRecords.map {
            FileRecord(
                normalizedPath = it.normalizedPath,
                winningModId = it.winningModId,
                winningModName = it.winningModId,
                sourceFilePath = it.sourceFilePath,
                hash = it.hash
            )
        }

        val diffEngine = DiffEngine()
        val changes = diffEngine.diff(oldFileRecords, newFileRecords)

        for (change in changes) {
            when (change) {
                is FileChange.Add -> {
                    copyIntoDeployRoot(change.record)
                }

                is FileChange.Update -> {
                    copyIntoDeployRoot(change.newRecord)
                }

                is FileChange.Remove -> {
                    val targetFile = resolveInsideDeployRoot(change.normalizedPath)

                    if (targetFile.exists() && targetFile.isFile) {
                        targetFile.delete()
                        cleanupEmptyParents(targetFile)
                    }
                }
            }
        }

        val newDeploymentRecords = newFileRecords.map {
            DeploymentRecord(
                normalizedPath = it.normalizedPath,
                winningModId = it.winningModId,
                sourceFilePath = it.sourceFilePath,
                hash = it.hash,
                deployedRelativePath = it.normalizedPath
            )
        }

        val result = DeploymentResult(
            addCount = changes.count { it is FileChange.Add },
            removeCount = changes.count { it is FileChange.Remove },
            updateCount = changes.count { it is FileChange.Update },
            finalRecordCount = newDeploymentRecords.size
        )

        return Pair(newDeploymentRecords, result)
    }

    private fun copyIntoDeployRoot(record: FileRecord) {
        val sourceFile = File(record.sourceFilePath)
        if (!sourceFile.exists() || !sourceFile.isFile) return

        val targetFile = resolveInsideDeployRoot(record.normalizedPath)
        targetFile.parentFile?.mkdirs()
        sourceFile.copyTo(targetFile, overwrite = true)
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

    private fun cleanupEmptyParents(startFile: File) {
        val rootCanonical = deployRootDir.canonicalFile
        var current = startFile.parentFile?.canonicalFile

        while (current != null) {
            if (current == rootCanonical) return
            if (!current.canonicalPath.startsWith(rootCanonical.canonicalPath)) return

            val children = current.listFiles()
            if (children != null && children.isNotEmpty()) return

            if (protectedFolderNames.contains(current.name.lowercase())) return

            val parent = current.parentFile?.canonicalFile
            current.delete()
            current = parent
        }
    }
}