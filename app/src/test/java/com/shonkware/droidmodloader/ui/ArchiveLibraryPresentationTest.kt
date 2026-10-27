package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserItemStatus
import com.shonkware.droidmodloader.ui.archive.ArchiveBrowserUiItem
import org.junit.Assert.*
import org.junit.Test

class ArchiveLibraryPresentationTest {
    private fun item() = ArchiveBrowserUiItem("archive", "/archives/mod.zip", "Mod", "mod.zip",
        "zip", 84L * 1024 * 1024, 1234, ArchiveBrowserItemStatus.NEVER_INSTALLED)

    @Test fun `row shows format and packed size without claiming modified time is download time`() {
        assertEquals("ZIP • 84.0 MB", buildArchiveDetails(item()))
        assertEquals(buildArchiveDetails(item()), buildArchiveDetails(item().copy(downloadedAtMillis = 9999)))
        assertEquals("7Z • 84.0 MB", buildArchiveDetails(item().copy(archiveFormat = "7z")))
        assertEquals("RAR • 84.0 MB", buildArchiveDetails(item().copy(archiveFormat = "rar")))
    }

    @Test fun `recorded profile install state is compact and does not need an invented date`() {
        assertEquals("ZIP • 84.0 MB • Installed",
            buildArchiveDetails(item().copy(status = ArchiveBrowserItemStatus.INSTALLED)))
        assertEquals("ZIP • 84.0 MB • Previously installed",
            buildArchiveDetails(item().copy(status = ArchiveBrowserItemStatus.PREVIOUSLY_INSTALLED)))
    }

    @Test fun `known source game needs no numeric ids and absent metadata adds no placeholder`() {
        assertEquals("Nexus game: falloutnewvegas",
            buildNexusSourceText(item().copy(nexusGameDomain = "falloutnewvegas")))
        assertNull(buildNexusSourceText(item()))
        assertNull(buildNexusSourceText(item().copy(nexusGameDomain = " ", nexusModId = 123,
            sourceUrl = "https://example.invalid/private-download")))
    }
}
