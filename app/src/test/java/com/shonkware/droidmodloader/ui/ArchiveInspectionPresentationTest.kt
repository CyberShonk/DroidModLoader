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
        assertTrue(text.contains("source may have changed"))
        assertTrue(text.contains("Installing reads a fresh snapshot"))
        assertTrue(text.contains("Detected from signature: zip"))
        assertTrue(text.contains("Extension mismatch: yes"))
        assertTrue(text.contains("Accepted entries: 3; completed files: 2"))
        assertTrue(text.contains("expanded output: 40 bytes"))
        assertTrue(text.contains("Installer layout: unavailable"))
        assertTrue(text.contains("Encrypted: not separately observed"))
        assertTrue(text.contains("Multipart: not separately observed"))
        assertTrue(text.contains("Solid: not separately observed"))
        assertFalse(text.contains("/private/snapshot"))
    }

    @Test fun `reader refusals are not formatted as a completed read`() {
        val text = archiveInspectionDetails(ArchiveInspectionReport("locked.rar", 1234,
            failureCode = "PASSWORD_PROTECTED_OR_ENCRYPTED", message = "Encrypted RAR5 is unsupported.")).joinToString("\n")
        assertTrue(text.contains("PASSWORD_PROTECTED_OR_ENCRYPTED"))
        assertTrue(text.contains("Encrypted RAR5 is unsupported"))
        assertFalse(text.contains("Reader: completed"))
        assertFalse(text.contains("expanded output"))
    }

    @Test fun `observed RAR5 flags and methods are shown without inventing compatibility`() {
        val report = report()
        val inspection = report.inspection!!
        val text = archiveInspectionDetails(report.copy(inspection = inspection.copy(
            extraction = inspection.extraction.copy(format = ArchiveFormat.RAR5,
                variant = ArchiveVariantFacts(false, false, true, setOf(3, 0))),
            installerType = InstallerType.SIMPLE, layoutIssue = null))).joinToString("\n")
        assertTrue(text.contains("Encrypted: no"))
        assertTrue(text.contains("Multipart: no"))
        assertTrue(text.contains("Solid: yes"))
        assertTrue(text.contains("Compression method codes: 0, 3"))
        assertTrue(text.contains("does not prove game compatibility"))
    }
}
