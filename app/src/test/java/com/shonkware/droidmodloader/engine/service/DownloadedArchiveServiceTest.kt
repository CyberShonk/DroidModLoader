package com.shonkware.droidmodloader.engine.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadedArchiveServiceTest {
    @Test
    fun `empty archive library returns stable empty summary`() {
        val fixture = fixture("empty")

        assertTrue(fixture.service.getDownloadedArchives().isEmpty())
        assertEquals(
            "No archives are currently saved in the archive library.",
            fixture.service.buildDownloadedArchiveSummary()
        )
    }

    @Test
    fun `registered archive can be found and marked installed`() {
        val fixture = fixture("register")
        val archive = File(fixture.archiveLibraryDir, "Example Mod-123-1-0.zip").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val record = fixture.service.registerDownloadedArchive(
            archiveFile = archive,
            originalDisplayName = archive.name,
            sourcePath = archive.absolutePath
        )
        fixture.service.markDownloadedArchiveInstalled(record.archiveId, "example-mod")

        val restored = fixture.service.getDownloadedArchiveById(record.archiveId)
        assertNotNull(restored)
        assertEquals("example-mod", restored?.installedModId)
        assertEquals(1, fixture.service.getDownloadedArchives().size)
    }

    private fun fixture(name: String): Fixture {
        val root = Files.createTempDirectory("dml-downloaded-archive-service-$name").toFile()
        val archiveLibraryDir = File(root, "archives").apply { mkdirs() }
        val service = DownloadedArchiveService(
            archiveLibraryDir = archiveLibraryDir,
            downloadedArchiveListFile = File(root, "state/downloaded_archives.json")
        )
        return Fixture(service, archiveLibraryDir)
    }

    private data class Fixture(
        val service: DownloadedArchiveService,
        val archiveLibraryDir: File
    )
}
