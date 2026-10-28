package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.engine.install.*
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import org.junit.Assert.*
import org.junit.Test

class ArchiveInspectionPresentationTest {
    private fun report() = ArchiveInspectionReport("mod.zip", 1234,
        ArchiveInspection(ArchiveExtractionFacts("/private/snapshot", 1234, ArchiveFormat.ZIP,
            true, 100, 3, 2, 40), null, "Layout could not be recognized."))

    @Test fun `details reuse captured facts and do not imply source freshness or installation`() {
        val report = report()
        val details = archiveInspectionDetails(report)
        repeat(5) { assertEquals(details, archiveInspectionDetails(report)) }
        val text = details.joinToString("\n")
        assertTrue(text.contains("source archive may have changed"))
        assertTrue(text.contains("A new install reads the archive again"))
        assertTrue(text.contains("Archive type: zip"))
        assertTrue(text.contains("Filename extension matches archive type: no"))
        assertTrue(text.contains("Archive entries: 3; files unpacked: 2"))
        assertTrue(text.contains("unpacked size: 40 bytes"))
        assertTrue(text.contains("Installer: Not recognized"))
        assertTrue(text.contains("Encrypted: unknown"))
        assertTrue(text.contains("Split across volumes: unknown"))
        assertTrue(text.contains("Solid: unknown"))
        assertFalse(text.contains("/private/snapshot"))
    }

    @Test fun `installer labels explain folder choice without exposing enum spelling`() {
        assertEquals("Simple", installerTypeLabel(InstallerType.SIMPLE))
        assertEquals("FOMOD", installerTypeLabel(InstallerType.FOMOD))
        assertEquals("BAIN", installerTypeLabel(InstallerType.BAIN))
        assertEquals("Choose a Data folder", installerTypeLabel(InstallerType.MANUAL_DATA_FOLDER))
        assertEquals("Not recognized", installerTypeLabel(null))
    }

    @Test fun `reader refusals are not formatted as a completed read`() {
        val text = archiveInspectionDetails(ArchiveInspectionReport("locked.rar", 1234,
            failureCode = "PASSWORD_PROTECTED_OR_ENCRYPTED", message = "Encrypted RAR5 is unsupported.")).joinToString("\n")
        assertTrue(text.contains("PASSWORD_PROTECTED_OR_ENCRYPTED"))
        assertTrue(text.contains("Encrypted RAR5 is unsupported"))
        assertFalse(text.contains("Archive was unpacked"))
        assertFalse(text.contains("unpacked size"))
    }

    @Test fun `observed RAR5 flags and methods are shown without inventing compatibility`() {
        val report = report()
        val inspection = report.inspection!!
        val text = archiveInspectionDetails(report.copy(inspection = inspection.copy(
            extraction = inspection.extraction.copy(format = ArchiveFormat.RAR5,
                variant = ArchiveVariantFacts(false, false, true, setOf(3, 0))),
            installerType = InstallerType.SIMPLE, layoutIssue = null))).joinToString("\n")
        assertTrue(text.contains("Encrypted: no"))
        assertTrue(text.contains("Split across volumes: no"))
        assertTrue(text.contains("Solid: yes"))
        assertTrue(text.contains("Compression method codes: 0, 3"))
        assertTrue(text.contains("does not prove the mod will work in the game"))
    }
}
