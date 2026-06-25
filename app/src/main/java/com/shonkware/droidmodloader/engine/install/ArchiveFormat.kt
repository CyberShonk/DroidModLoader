package com.shonkware.droidmodloader.engine.install

import java.io.IOException

enum class ArchiveFormat(
    val expectedExtensions: Set<String>
) {
    ZIP(setOf("zip")),
    SEVEN_Z(setOf("7z")),
    RAR4(setOf("rar")),
    RAR5(setOf("rar"))
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
    CORRUPT_OR_UNSUPPORTED,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY_PATH,
    CASE_COLLISION,
    ENTRY_LIMIT_EXCEEDED,
    FILE_SIZE_LIMIT_EXCEEDED,
    TOTAL_SIZE_LIMIT_EXCEEDED,
    PATH_LENGTH_LIMIT_EXCEEDED,
    INSUFFICIENT_STORAGE,
    IO_FAILURE
}

class ArchiveReadException(
    val code: ArchiveReadFailureCode,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)