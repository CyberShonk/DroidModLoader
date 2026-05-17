package com.shonkware.droidmodloader.engine.deploy

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import java.io.File
import java.io.IOException

class TreeUriDeploymentManager(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val treeUri: Uri
) {

    private val root: DocumentFile by lazy {
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Could not open selected Tree URI target folder.")
    }

    // Directory path -> DocumentFile directory.
    // Example: "" -> root, "textures", "textures/armor"
    private val directoryCache = mutableMapOf<String, DocumentFile>()

    // Parent directory path -> child name -> DocumentFile.
    // This avoids calling parentDir.findFile(name) repeatedly for every file.
    private val childCache = mutableMapOf<String, MutableMap<String, DocumentFile>>()

    fun deploy(
        oldManifest: List<DeploymentRecord>,
        newWinningRecords: List<FileRecord>
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        directoryCache.clear()
        childCache.clear()

        directoryCache[""] = root

        val oldByPath = oldManifest.associateBy { it.normalizedPath }
        val newByPath = newWinningRecords.associateBy { it.normalizedPath }

        val removes = oldByPath
            .filterKeys { path -> path !in newByPath }
            .values
            .sortedByDescending { it.normalizedPath.count { c -> c == '/' } }

        val adds = newByPath
            .filterKeys { path -> path !in oldByPath }
            .values
            .sortedBy { it.normalizedPath }

        val updates = newByPath
            .filter { (path, newRecord) ->
                val oldRecord = oldByPath[path]
                oldRecord != null && oldRecord.hash != newRecord.hash
            }
            .values
            .sortedBy { it.normalizedPath }

        for (record in removes) {
            deleteFileIfPresent(record.normalizedPath)
        }

        for (record in updates) {
            deleteFileIfPresent(record.normalizedPath)
            copyRecordToTarget(record)
        }

        for (record in adds) {
            copyRecordToTarget(record)
        }

        val newManifest = newWinningRecords
            .map { it.toDeploymentRecord() }
            .sortedBy { it.normalizedPath }

        val result = DeploymentResult(
            addCount = adds.size,
            removeCount = removes.size,
            updateCount = updates.size,
            finalRecordCount = newManifest.size
        )

        return Pair(newManifest, result)
    }

    private fun copyRecordToTarget(record: FileRecord) {
        val sourceFile = File(record.sourceFilePath)

        if (!sourceFile.exists() || !sourceFile.isFile) {
            throw IOException("Source file missing during Tree URI deploy: ${record.sourceFilePath}")
        }

        val parentPath = getParentPath(record.normalizedPath)
        val fileName = getFileName(record.normalizedPath)
        val parentDir = getOrCreateDirectory(parentPath)

        val existing = getCachedChild(
            parentPath = parentPath,
            parentDir = parentDir,
            childName = fileName
        )

        if (existing != null) {
            if (existing.isDirectory) {
                throw IOException("Target path is a directory, expected file: ${record.normalizedPath}")
            }

            existing.delete()
            removeCachedChild(parentPath, fileName)
        }

        val targetFile = parentDir.createFile(
            guessMimeType(fileName),
            fileName
        ) ?: throw IOException("Could not create target file through Tree URI: ${record.normalizedPath}")

        sourceFile.inputStream().use { input ->
            contentResolver.openOutputStream(targetFile.uri, "w").use { output ->
                if (output == null) {
                    throw IOException("Could not open target output stream: ${record.normalizedPath}")
                }

                input.copyTo(output)
            }
        }

        putCachedChild(parentPath, fileName, targetFile)
    }

    private fun deleteFileIfPresent(normalizedPath: String) {
        val parentPath = getParentPath(normalizedPath)
        val fileName = getFileName(normalizedPath)

        val parentDir = getExistingDirectory(parentPath) ?: return

        val existing = getCachedChild(
            parentPath = parentPath,
            parentDir = parentDir,
            childName = fileName
        ) ?: return

        // Do not delete directories through a file-delete path.
        if (!existing.isFile) {
            return
        }

        existing.delete()
        removeCachedChild(parentPath, fileName)
    }

    private fun getOrCreateDirectory(path: String): DocumentFile {
        if (path.isBlank()) {
            return root
        }

        val cached = directoryCache[path]
        if (cached != null) {
            return cached
        }

        var currentPath = ""
        var currentDir = root
        var parentPath = ""

        val parts = path
            .split("/")
            .filter { it.isNotBlank() }

        for (part in parts) {
            currentPath = if (currentPath.isBlank()) {
                part
            } else {
                "$currentPath/$part"
            }

            val cachedDir = directoryCache[currentPath]
            if (cachedDir != null) {
                currentDir = cachedDir
                parentPath = currentPath
                continue
            }

            val child = getCachedChild(
                parentPath = parentPath,
                parentDir = currentDir,
                childName = part
            )

            val nextDir = when {
                child != null && child.isDirectory -> child

                child != null && child.isFile -> {
                    throw IOException("Target path segment is a file, expected directory: $currentPath")
                }

                else -> {
                    val created = currentDir.createDirectory(part)
                        ?: throw IOException("Could not create target directory through Tree URI: $currentPath")

                    putCachedChild(parentPath, part, created)
                    created
                }
            }

            directoryCache[currentPath] = nextDir
            currentDir = nextDir
            parentPath = currentPath
        }

        return currentDir
    }

    private fun getExistingDirectory(path: String): DocumentFile? {
        if (path.isBlank()) {
            return root
        }

        val cached = directoryCache[path]
        if (cached != null) {
            return cached
        }

        var currentPath = ""
        var currentDir = root
        var parentPath = ""

        val parts = path
            .split("/")
            .filter { it.isNotBlank() }

        for (part in parts) {
            currentPath = if (currentPath.isBlank()) {
                part
            } else {
                "$currentPath/$part"
            }

            val cachedDir = directoryCache[currentPath]
            if (cachedDir != null) {
                currentDir = cachedDir
                parentPath = currentPath
                continue
            }

            val child = getCachedChild(
                parentPath = parentPath,
                parentDir = currentDir,
                childName = part
            )

            if (child == null || !child.isDirectory) {
                return null
            }

            directoryCache[currentPath] = child
            currentDir = child
            parentPath = currentPath
        }

        return currentDir
    }

    private fun getCachedChild(
        parentPath: String,
        parentDir: DocumentFile,
        childName: String
    ): DocumentFile? {
        val children = getCachedChildren(parentPath, parentDir)
        return children[childName]
    }

    private fun getCachedChildren(
        parentPath: String,
        parentDir: DocumentFile
    ): MutableMap<String, DocumentFile> {
        val cached = childCache[parentPath]
        if (cached != null) {
            return cached
        }

        val loaded = parentDir
            .listFiles()
            .mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                name to child
            }
            .toMap()
            .toMutableMap()

        childCache[parentPath] = loaded
        return loaded
    }

    private fun putCachedChild(
        parentPath: String,
        childName: String,
        child: DocumentFile
    ) {
        val children = childCache[parentPath]
        if (children != null) {
            children[childName] = child
        }
    }

    private fun removeCachedChild(
        parentPath: String,
        childName: String
    ) {
        childCache[parentPath]?.remove(childName)
    }

    private fun getParentPath(normalizedPath: String): String {
        return normalizedPath.substringBeforeLast("/", missingDelimiterValue = "")
    }

    private fun getFileName(normalizedPath: String): String {
        return normalizedPath.substringAfterLast("/")
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()

        return when {
            lower.endsWith(".txt") ||
                    lower.endsWith(".ini") ||
                    lower.endsWith(".cfg") ||
                    lower.endsWith(".json") ||
                    lower.endsWith(".xml") -> "text/plain"

            lower.endsWith(".dds") -> "image/vnd.ms-dds"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"

            else -> "application/octet-stream"
        }
    }

    private fun FileRecord.toDeploymentRecord(): DeploymentRecord {
        return DeploymentRecord(
            normalizedPath = normalizedPath,
            sourceFilePath = sourceFilePath,
            winningModId = winningModId,
            winningModName = winningModName,
            hash = hash
        )
    }
}