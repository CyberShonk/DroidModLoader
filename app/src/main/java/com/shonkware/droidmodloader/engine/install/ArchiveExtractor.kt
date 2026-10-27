package com.shonkware.droidmodloader.engine.install

import java.io.File

class ArchiveExtractor(
    private val readerRegistry: ArchiveReaderRegistry = ArchiveReaderRegistry(),
    private val formatProbe: ArchiveFormatProbe = ArchiveFormatProbe(),
    private val createWriter: (File, InstallCancellationSignal) -> ArchiveEntryWriter = { output, signal ->
        ArchiveEntryWriter(outputDir = output, cancellationSignal = signal)
    }
) {
    fun extractToRawFolder(
        archive: File,
        outputDir: File,
        cancellationSignal:
        InstallCancellationSignal =
            InstallCancellationSignal.NONE
    ): ArchiveExtractionFacts {
        cancellationSignal
            .throwIfCancellationRequested()

        val probe = formatProbe.probe(archive)
        val reader = readerRegistry.findReader(probe.format, archive.name)
        val writer = createWriter(outputDir, cancellationSignal)

        reader.read(
            archive = archive,
            writer = writer
        )

        cancellationSignal
            .throwIfCancellationRequested()
        return writer.extractionFacts(archive, probe)
    }
}