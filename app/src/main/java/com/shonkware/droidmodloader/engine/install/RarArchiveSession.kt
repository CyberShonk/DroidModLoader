package com.shonkware.droidmodloader.engine.install

import com.github.junrar.Archive
import java.io.Closeable
import java.io.File
import java.io.OutputStream

internal data class RarArchiveEntry(
    val name: String,
    val directory: Boolean,
    val encrypted: Boolean,
    val splitBefore: Boolean,
    val splitAfter: Boolean,
    val writeContent: (OutputStream) -> Unit
)

internal interface RarArchiveSession : Closeable {
    val encrypted: Boolean

    fun nextEntry(): RarArchiveEntry?
}

internal class JunrarArchiveSession(
    archiveFile: File
) : RarArchiveSession {
    private val archive = Archive(archiveFile)

    override val encrypted: Boolean
        get() = archive.isEncrypted

    override fun nextEntry(): RarArchiveEntry? {
        val header = archive.nextFileHeader() ?: return null

        return RarArchiveEntry(
            name = header.fileName.orEmpty(),
            directory = header.isDirectory,
            encrypted = header.isEncrypted,
            splitBefore = header.isSplitBefore,
            splitAfter = header.isSplitAfter,
            writeContent = { output ->
                archive.extractFile(header, output)
            }
        )
    }

    override fun close() {
        archive.close()
    }

}