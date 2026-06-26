package com.shonkware.droidmodloader.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import org.junit.Assert.assertFalse

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

    @Test
    fun cancellationRemovesPartialArchiveLibraryFile() {
        val external =
            temporaryFolder.newFolder("external")

        val source =
            temporaryFolder
                .newFile("Large Example.zip")
                .apply {
                    writeBytes(
                        ByteArray(128 * 1024) {
                            42
                        }
                    )
                }

        val store = ArchiveImportFileStore(
            externalFilesDirProvider = {
                external
            },
            appendError = {}
        )

        var cancellationChecks = 0

        val cancellationSignal =
            InstallCancellationSignal {
                cancellationChecks++

                if (cancellationChecks == 5) {
                    throw InstallCancelledException()
                }
            }

        var thrown: Throwable? = null

        try {
            store.copyFileToArchiveLibraryFile(
                sourceFile = source,
                displayName =
                    "Large Example.zip",
                cancellationSignal =
                    cancellationSignal
            )
        } catch (throwable: Throwable) {
            thrown = throwable
        }

        assertTrue(
            thrown is InstallCancelledException
        )

        val archiveLibraryDir = File(
            external,
            "downloads/archive_library"
        )

        assertTrue(
            archiveLibraryDir.exists()
        )

        assertFalse(
            File(
                archiveLibraryDir,
                "Large Example.zip"
            ).exists()
        )

        assertTrue(
            archiveLibraryDir
                .listFiles()
                .orEmpty()
                .isEmpty()
        )
    }
}
