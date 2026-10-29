package com.shonkware.droidmodloader.engine.install

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveInspectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private var reads = 0
    private var sessionId = 0

    private fun extractor(
        limits: ExtractionLimits = ExtractionLimits(),
        afterRead: () -> Unit = {}
    ): ArchiveExtractor {
        fun counted(delegate: ArchiveReader) = object : ArchiveReader {
            override fun read(archive: File, writer: ArchiveEntryWriter) {
                reads++
                delegate.read(archive, writer)
                afterRead()
            }
        }
        return ArchiveExtractor(
            readerRegistry = ArchiveReaderRegistry(ArchiveFormatProbe(), counted(ZipArchiveReader()),
                counted(SevenZipArchiveReader()), counted(RarArchiveReader()), counted(Rar5ArchiveReader())),
            createWriter = { output, signal -> ArchiveEntryWriter(output, limits, {}, { Long.MAX_VALUE }, signal) }
        )
    }

    private fun installer(extractor: ArchiveExtractor = extractor()) = PreparedArchiveInstaller(
        tempDir = File(temporary.root, "temp"), modsDir = File(temporary.root, "mods"),
        directoryReplacer = InstalledModDirectoryReplacer(), operationId = { "session-${sessionId++}" },
        debugLog = {}, archiveExtractor = extractor
    )

    private fun zip(name: String = "mod.zip", path: String = "Data/mod.esp", text: String = "payload"): File =
        File(temporary.root, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }

    private fun assertNoSessionOrInstall() {
        assertTrue(File(temporary.root, "temp/installer_sessions").list().orEmpty().isEmpty())
        assertFalse(File(temporary.root, "mods").exists())
    }

    private fun PreparedArchiveInstaller.prepareFacts(
        archive: File,
        signal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ): ArchiveInspection {
        val prepared = prepare(archive, signal)
        try { return checkNotNull(prepared.inspection) } finally { cancel(prepared) }
    }

    @Test fun `supported formats report actual output without an additional read`() {
        val seven = File(temporary.root, "mod.7z")
        val input = File(temporary.root, "mod.esp").apply { writeText("payload") }
        SevenZOutputFile(seven).use { output ->
            output.putArchiveEntry(output.createArchiveEntry(input, "Data/mod.esp"))
            output.write(input.readBytes())
            output.closeArchiveEntry()
        }
        val rar4 = File(temporary.root, "old.rar").apply {
            writeBytes(RarTestArchives.rar4("mod.esp", "payload".toByteArray()))
        }
        val rar5 = File(temporary.root, "new.rar").apply {
            writeBytes(RarTestArchives.rar5(RarTestArchives.entry("Data/mod.esp", "payload".toByteArray())))
        }
        val inspector = installer()
        for ((archive, format) in listOf(zip() to ArchiveFormat.ZIP, seven to ArchiveFormat.SEVEN_Z,
            rar4 to ArchiveFormat.RAR4, rar5 to ArchiveFormat.RAR5)) {
            val observation = inspector.prepareFacts(archive)
            assertEquals(format, observation.extraction.format)
            assertEquals(1, observation.extraction.acceptedEntries)
            assertEquals(1, observation.extraction.completedFiles)
            assertEquals(7L, observation.extraction.expandedBytes)
            assertEquals(archive.length(), observation.extraction.archiveBytes)
            assertEquals(InstallerType.SIMPLE, observation.installerType)
            assertNull(observation.layoutIssue)
            assertNoSessionOrInstall()
        }
        assertEquals(4, reads)
    }

    @Test fun `extraction routing retains the baseline single reader pass`() {
        var passes = 0
        val reader = object : ArchiveReader {
            override fun read(archive: File, writer: ArchiveEntryWriter) {
                passes++
                ZipArchiveReader().read(archive, writer)
            }
        }
        val registry = ArchiveReaderRegistry(ArchiveFormatProbe(), reader, reader, reader, reader)
        val source = zip()
        val baseline = temporary.newFolder()
        // The pre-change extractor selected by file then invoked the reader once.
        registry.findReader(source).read(source,
            ArchiveEntryWriter(baseline, ExtractionLimits(), {}, { Long.MAX_VALUE }))
        assertEquals(1, passes)
        val candidate = temporary.newFolder()
        val facts = ArchiveExtractor(registry, createWriter = { output, signal ->
            ArchiveEntryWriter(output, ExtractionLimits(), {}, { Long.MAX_VALUE }, signal)
        }).extractToRawFolder(source, candidate)
        assertEquals(2, passes)
        assertEquals(File(baseline, "Data/mod.esp").readText(), File(candidate, "Data/mod.esp").readText())
        assertEquals(7L, facts.expandedBytes)
        println("Baseline reader passes=1; candidate reader passes=1; candidate output bytes=${facts.expandedBytes}")
    }

    @Test fun `preparation reuses its single extraction for facts and layout`() {
        val inspector = installer()
        val prepared = inspector.prepare(zip())
        val inspection = checkNotNull(prepared.inspection)
        repeat(3) {
            assertEquals(1, inspection.extraction.completedFiles)
            assertEquals(InstallerType.SIMPLE, inspection.installerType)
        }
        assertEquals(1, reads)
        assertTrue(File(prepared.installRootPath).exists())
        assertEquals("Data", prepared.plan.groups.single().options.single().sourcePath)
        assertEquals("payload", File(prepared.installRootPath, "Data/mod.esp").readText())
        inspector.cancel(prepared)
        assertNoSessionOrInstall()
    }

    @Test fun `unknown layout retains one safe preparation for explicit manual choices and cancel cleans it`() {
        val installer = installer()
        val prepared = installer.prepare(zip(path = "unusual.dat"))
        assertEquals(InstallerType.MANUAL_DATA_FOLDER, prepared.plan.installerType)
        assertTrue(prepared.plan.requiresUserChoice)
        assertNotNull(prepared.manualLayoutInventory)
        assertEquals(1, reads)
        assertTrue(File(prepared.extractedRootPath, "unusual.dat").isFile)
        assertThrows(java.io.IOException::class.java) {
            installer.finalizeInstall(prepared, InstallerSelection(emptySet()))
        }
        assertTrue(File(prepared.sessionRootPath).isDirectory)
        installer.cancel(prepared)
        assertNoSessionOrInstall()
    }

    @Test fun `signature mismatch RAR5 metadata and ignored QO have precise provenance`() {
        val source = File(temporary.root, "misleading.zip").apply {
            writeBytes(RarTestArchives.rar5(
                RarTestArchives.entry("Data", directory = true),
                RarTestArchives.entry("Data/mod.esp", "payload".toByteArray()),
                RarTestArchives.entry("Data/empty.bin"),
                RarTestArchives.entry("QO", "unused metadata".toByteArray(), type = 3)
            ))
        }
        val facts = installer().prepareFacts(source).extraction
        assertEquals(ArchiveFormat.RAR5, facts.format)
        assertTrue(facts.extensionMismatch)
        assertEquals(3, facts.acceptedEntries)
        assertEquals(2, facts.completedFiles)
        assertEquals(7L, facts.expandedBytes)
        assertEquals(ArchiveVariantFacts(false, false, false, setOf(0)), facts.variant)
        assertNoSessionOrInstall()
    }

    @Test fun `uncollected ZIP metadata stays unknown rather than inferred from reader success`() {
        val facts = installer().prepareFacts(zip()).extraction
        assertEquals(ArchiveVariantFacts(), facts.variant)
    }

    @Test fun `unsafe and corrupt inputs publish no completed facts and clean sessions`() {
        val unsafe = zip(path = "../outside")
        val refused = assertThrows(java.io.IOException::class.java) { installer().prepare(unsafe) }
        assertTrue(refused.cause is ArchiveReadException)
        assertEquals(ArchiveReadFailureCode.UNSAFE_ENTRY_PATH, (refused.cause as ArchiveReadException).code)
        assertFalse(File(temporary.root, "outside").exists())
        assertNoSessionOrInstall()
        val corrupt = File(temporary.root, "broken.rar").apply {
            val bytes = RarTestArchives.rar5(RarTestArchives.entry("mod.esp", "payload".toByteArray()))
            writeBytes(bytes.copyOf(bytes.size - 8))
        }
        val corruptFailure = assertThrows(java.io.IOException::class.java) { installer().prepare(corrupt) }
        assertTrue(corruptFailure.cause is ArchiveReadException)
        assertNoSessionOrInstall()
    }

    @Test fun `preparation retains output limits and prior installation`() {
        val mods = File(temporary.root, "mods/Existing").apply { mkdirs() }
        val marker = File(mods, "old.esp").apply { writeText("unchanged") }
        val inspector = installer(extractor(ExtractionLimits(maxFileBytes = 3, maxTotalBytes = 3)))
        val failure = assertThrows(java.io.IOException::class.java) { inspector.prepare(zip()) }
        assertTrue(failure.cause is ArchiveReadException)
        assertEquals(ArchiveReadFailureCode.FILE_SIZE_LIMIT_EXCEEDED, (failure.cause as ArchiveReadException).code)
        assertEquals("unchanged", marker.readText())
        assertTrue(File(temporary.root, "temp/installer_sessions").list().orEmpty().isEmpty())
    }

    @Test fun `pre cancellation and cancellation after reader completion cannot return facts`() {
        val controller = InstallCancellationController()
        controller.cancel()
        assertThrows(InstallCancelledException::class.java) { installer().prepare(zip(), controller.signal) }
        assertEquals(0, reads)
        assertNoSessionOrInstall()
        val late = InstallCancellationController()
        assertThrows(InstallCancelledException::class.java) {
            installer(extractor(afterRead = { late.cancel() })).prepare(zip(), late.signal)
        }
        assertEquals(1, reads)
        assertNoSessionOrInstall()
    }

    @Test fun `existing session is not owned or deleted by preparation`() {
        val existing = File(temporary.root, "temp/installer_sessions/fixed").apply { mkdirs() }
        val marker = File(existing, "keep").apply { writeText("not ours") }
        val inspector = PreparedArchiveInstaller(File(temporary.root, "temp"), File(temporary.root, "mods"),
            InstalledModDirectoryReplacer(), { "fixed" }, {}, archiveExtractor = extractor())
        val archive = zip()
        assertThrows(java.io.IOException::class.java) { inspector.prepare(archive) }
        assertEquals("not ours", marker.readText())
        assertEquals(0, reads)
    }
}
