package com.shonkware.droidmodloader.engine.install

import java.io.File

class ArchiveReaderRegistry internal constructor(
    private val formatProbe: ArchiveFormatProbe,
    private val zipReader: ArchiveReader,
    private val sevenZipReader: ArchiveReader,
    private val rar4Reader: ArchiveReader,
    private val rar5Reader: ArchiveReader?
) {
    constructor() : this(
        formatProbe = ArchiveFormatProbe(),
        zipReader = ZipArchiveReader(),
        sevenZipReader = SevenZipArchiveReader(),
        rar4Reader = RarArchiveReader(),
        rar5Reader = Rar5ArchiveReader()
    )

    fun findReader(archive: File): ArchiveReader {
        val probeResult = formatProbe.probe(archive)

        return findReader(
            format = probeResult.format,
            archiveName = archive.name
        )
    }

    fun findReader(
        format: ArchiveFormat,
        archiveName: String
    ): ArchiveReader {
        return when (format) {
            ArchiveFormat.ZIP -> zipReader
            ArchiveFormat.SEVEN_Z -> sevenZipReader
            ArchiveFormat.RAR4 -> rar4Reader
            ArchiveFormat.RAR5 ->
                rar5Reader ?: throw ArchiveReadException(
                    code =
                        ArchiveReadFailureCode
                            .UNSUPPORTED_VARIANT,
                    message =
                        "RAR5 archives are not supported by this DML " +
                                "release: $archiveName"
                )
        }
    }
}
