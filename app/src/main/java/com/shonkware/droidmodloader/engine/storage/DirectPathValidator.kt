package com.shonkware.droidmodloader.engine.storage

import java.io.File
import java.io.IOException

data class DirectPathValidationResult(
    val isValid: Boolean,
    val canonicalPath: String? = null,
    val message: String? = null
)

class DirectPathValidator {
    fun validateDirectory(
        path: String,
        requireWritable: Boolean
    ): DirectPathValidationResult {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return DirectPathValidationResult(
                isValid = false,
                message = "Folder path is blank."
            )
        }

        val candidate = File(trimmed)
        if (!candidate.isAbsolute) {
            return DirectPathValidationResult(
                isValid = false,
                message = "Folder path must be absolute."
            )
        }

        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            return DirectPathValidationResult(
                isValid = false,
                message = "Folder path could not be resolved: ${e.message}"
            )
        }

        return when {
            !canonical.exists() -> DirectPathValidationResult(
                isValid = false,
                message = "Folder does not exist: ${canonical.absolutePath}"
            )

            !canonical.isDirectory -> DirectPathValidationResult(
                isValid = false,
                message = "Path is not a folder: ${canonical.absolutePath}"
            )

            !canonical.canRead() -> DirectPathValidationResult(
                isValid = false,
                message = "Folder is not readable: ${canonical.absolutePath}"
            )

            requireWritable && !canonical.canWrite() -> DirectPathValidationResult(
                isValid = false,
                message = "Folder is not writable: ${canonical.absolutePath}"
            )

            else -> DirectPathValidationResult(
                isValid = true,
                canonicalPath = canonical.absolutePath
            )
        }
    }

    fun resolveContainedChild(
        rootPath: String,
        relativePath: String
    ): File {
        require(relativePath.isNotBlank()) { "Relative path must not be blank." }
        require(!File(relativePath).isAbsolute) { "Child path must be relative." }

        val root = File(rootPath).canonicalFile
        val child = File(root, relativePath).canonicalFile
        val rootPrefix = root.absolutePath.trimEnd(File.separatorChar) + File.separator

        require(
            child.absolutePath == root.absolutePath || child.absolutePath.startsWith(rootPrefix)
        ) {
            "Resolved child path escapes the selected root."
        }

        return child
    }
}
