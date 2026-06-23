package com.shonkware.droidmodloader.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveImportFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copyFileToArchiveLibraryUsesDirectSourceAndUniqueNames() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply {
            writeText("archive payload")
        }
        val store = ArchiveImportFileStore(
            externalFilesDirProvider = { external },
            appendError = {}
        )

        val first = store.copyFileToArchiveLibraryFile(source, "Example.zip")
        val second = store.copyFileToArchiveLibraryFile(source, "Example.zip")

        assertEquals("archive payload", first.readText())
        assertEquals("Example.zip", first.name)
        assertEquals("Example (1).zip", second.name)
        assertTrue(first.canonicalPath.startsWith(external.canonicalPath + File.separator))
    }
}
