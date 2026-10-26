package com.shonkware.droidmodloader.engine.install

import java.io.IOException

enum class ArchiveFormat(
    val expectedExtensions: Set<String>,
    val metadataLabel: String,
    val diagnosticLabel: String
) {
    ZIP(
        expectedExtensions = setOf("zip"),
        metadataLabel = "zip",
        diagnosticLabel = "zip"
    ),
    SEVEN_Z(
        expectedExtensions = setOf("7z"),
        metadataLabel = "7z",
        diagnosticLabel = "7z"
    ),
    RAR4(
        expectedExtensions = setOf("rar"),
        metadataLabel = "rar",
        diagnosticLabel = "rar4"
    ),
    RAR5(
        expectedExtensions = setOf("rar"),
        metadataLabel = "rar",
        diagnosticLabel = "rar5"
    )
}

enum class ArchiveProbeFailureCode {
    FILE_NOT_FOUND,
    FILE_NOT_READABLE,
    UNSUPPORTED_FORMAT
}

data class ArchiveFormatProbeResult(
    val format: ArchiveFormat,
    val fileExtension: String,
    val extensionMismatch: Boolean
)

class ArchiveFormatProbeException(
    val code: ArchiveProbeFailureCode,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

enum class ArchiveReadFailureCode {
    UNSUPPORTED_VARIANT,
    PASSWORD_PROTECTED_OR_ENCRYPTED,
    MULTIPART_ARCHIVE,
    DECODER_MEMORY_LIMIT_EXCEEDED,
    HEADER_LIMIT_EXCEEDED,
    CORRUPT_OR_UNSUPPORTED,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY_PATH,
    CASE_COLLISION,
    ENTRY_LIMIT_EXCEEDED,
    FILE_SIZE_LIMIT_EXCEEDED,
    TOTAL_SIZE_LIMIT_EXCEEDED,
    PATH_LENGTH_LIMIT_EXCEEDED,
    PATH_DEPTH_LIMIT_EXCEEDED,
    INSUFFICIENT_STORAGE,
    IO_FAILURE
}

class ArchiveReadException(
    val code: ArchiveReadFailureCode,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)