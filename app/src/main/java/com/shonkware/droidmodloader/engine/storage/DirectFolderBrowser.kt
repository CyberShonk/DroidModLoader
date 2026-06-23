package com.shonkware.droidmodloader.engine.storage

import java.io.File

data class DirectFolderBrowserEntry(
    val name: String,
    val path: String,
    val writable: Boolean
)

data class DirectFolderBrowserState(
    val currentPath: String? = null,
    val parentPath: String? = null,
    val entries: List<DirectFolderBrowserEntry> = emptyList(),
    val canSelectCurrent: Boolean = false,
    val currentWritable: Boolean = false,
    val errorMessage: String? = null
)

class DirectFolderBrowser(
    roots: List<File>,
    private val pathValidator: DirectPathValidator = DirectPathValidator()
) {
    private val canonicalRoots: List<File> = roots
        .mapNotNull { root ->
            runCatching { root.canonicalFile }
                .getOrNull()
                ?.takeIf { it.exists() && it.isDirectory && it.canRead() }
        }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath.lowercase() }

    fun openRoots(): DirectFolderBrowserState {
        if (canonicalRoots.isEmpty()) {
            return DirectFolderBrowserState(
                errorMessage = "No readable shared-storage roots are available."
            )
        }

        return DirectFolderBrowserState(
            entries = canonicalRoots.map { root ->
                DirectFolderBrowserEntry(
                    name = root.absolutePath,
                    path = root.absolutePath,
                    writable = root.canWrite()
                )
            }
        )
    }

    fun open(path: String): DirectFolderBrowserState {
        val validation = pathValidator.validateDirectory(
            path = path,
            requireWritable = false
        )

        if (!validation.isValid || validation.canonicalPath == null) {
            return openRoots().copy(errorMessage = validation.message)
        }

        val current = File(validation.canonicalPath)
        val containingRoot = canonicalRoots
            .filter { root -> isInside(root, current) }
            .maxByOrNull { it.absolutePath.length }

        if (containingRoot == null) {
            return openRoots().copy(
                errorMessage = "Folder is outside the available shared-storage roots."
            )
        }

        val entries = current.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.canRead() }
            ?.mapNotNull { child ->
                runCatching { child.canonicalFile }.getOrNull()?.let { canonical ->
                    DirectFolderBrowserEntry(
                        name = canonical.name.ifBlank { canonical.absolutePath },
                        path = canonical.absolutePath,
                        writable = canonical.canWrite()
                    )
                }
            }
            ?.distinctBy { it.path }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?.toList()
            ?: emptyList()

        val parentPath = if (current.absolutePath == containingRoot.absolutePath) {
            null
        } else {
            current.parentFile
                ?.canonicalFile
                ?.takeIf { isInside(containingRoot, it) }
                ?.absolutePath
        }

        return DirectFolderBrowserState(
            currentPath = current.absolutePath,
            parentPath = parentPath,
            entries = entries,
            canSelectCurrent = true,
            currentWritable = current.canWrite()
        )
    }

    fun navigateUp(state: DirectFolderBrowserState): DirectFolderBrowserState {
        val parent = state.parentPath
        return if (parent == null) openRoots() else open(parent)
    }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val candidatePath = candidate.absolutePath
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    }
}
