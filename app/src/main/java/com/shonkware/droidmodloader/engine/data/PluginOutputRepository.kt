package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.plugins.PluginOutputContent
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PluginOutputWriteException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class PluginOutputPaths(
    val pluginsTxtPath: String,
    val loadorderTxtPath: String?
)

class PluginOutputRepository(
    private val pluginsTxtFile: File,
    private val loadorderTxtFile: File
) {
    fun replaceOutputs(content: PluginOutputContent): PluginOutputPaths {
        val pluginsSnapshot = FileSnapshot.capture(pluginsTxtFile)
        val loadorderSnapshot = FileSnapshot.capture(loadorderTxtFile)

        try {
            replaceTextFile(pluginsTxtFile, content.pluginsTxt)

            if (content.loadorderTxt == null) {
                deleteFileIfPresent(loadorderTxtFile)
            } else {
                replaceTextFile(loadorderTxtFile, content.loadorderTxt)
            }
        } catch (failure: Throwable) {
            val restoreFailures = buildList {
                restoreSnapshot(pluginsTxtFile, pluginsSnapshot)?.let(::add)
                restoreSnapshot(loadorderTxtFile, loadorderSnapshot)?.let(::add)
            }

            val restoreSuffix = if (restoreFailures.isEmpty()) {
                "Previous plugin outputs were restored."
            } else {
                "Could not restore: ${restoreFailures.joinToString()}."
            }

            throw PluginOutputWriteException(
                message = "Could not replace plugin output files. $restoreSuffix " +
                    failure.message.orEmpty(),
                cause = failure
            )
        }

        return PluginOutputPaths(
            pluginsTxtPath = pluginsTxtFile.absolutePath,
            loadorderTxtPath = content.loadorderTxt?.let { loadorderTxtFile.absolutePath }
        )
    }

    fun readPluginsTxt(): String {
        if (!pluginsTxtFile.exists()) return ""
        return pluginsTxtFile.readText()
    }

    fun readLoadorderTxt(): String {
        if (!loadorderTxtFile.exists()) return ""
        return loadorderTxtFile.readText()
    }

    private fun replaceTextFile(file: File, content: String) {
        val parent = file.parentFile
            ?: throw PluginOutputWriteException("Output file has no parent directory: ${file.path}")

        if (!parent.exists() && !parent.mkdirs()) {
            throw PluginOutputWriteException(
                "Could not create plugin output directory: ${parent.absolutePath}"
            )
        }
        if (!parent.isDirectory) {
            throw PluginOutputWriteException(
                "Plugin output parent is not a directory: ${parent.absolutePath}"
            )
        }

        val temporaryFile = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporaryFile.writeText(content)
            moveReplacing(temporaryFile, file)
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun deleteFileIfPresent(file: File) {
        if (file.exists() && !file.delete()) {
            throw PluginOutputWriteException(
                "Could not remove stale plugin output: ${file.absolutePath}"
            )
        }
    }

    private fun restoreSnapshot(file: File, snapshot: FileSnapshot): String? {
        return runCatching {
            if (snapshot.existed) {
                replaceBinaryFile(file, checkNotNull(snapshot.content))
            } else {
                deleteFileIfPresent(file)
            }
        }.exceptionOrNull()?.let { file.name }
    }

    private fun replaceBinaryFile(file: File, content: ByteArray) {
        val parent = file.parentFile
            ?: throw PluginOutputWriteException("Output file has no parent directory: ${file.path}")

        if (!parent.exists() && !parent.mkdirs()) {
            throw PluginOutputWriteException(
                "Could not create plugin output directory: ${parent.absolutePath}"
            )
        }
        if (!parent.isDirectory) {
            throw PluginOutputWriteException(
                "Plugin output parent is not a directory: ${parent.absolutePath}"
            )
        }

        val temporaryFile = File(parent, ".${file.name}.${System.nanoTime()}.restore.tmp")
        try {
            temporaryFile.writeBytes(content)
            moveReplacing(temporaryFile, file)
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private data class FileSnapshot(
        val existed: Boolean,
        val content: ByteArray?
    ) {
        companion object {
            fun capture(file: File): FileSnapshot {
                return if (file.exists()) {
                    FileSnapshot(existed = true, content = file.readBytes())
                } else {
                    FileSnapshot(existed = false, content = null)
                }
            }
        }
    }
}
