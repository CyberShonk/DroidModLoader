package com.shonkware.droidmodloader.ui.archive

import com.shonkware.droidmodloader.engine.install.ArchiveProbeFailureCode
import com.shonkware.droidmodloader.engine.install.ArchiveReadFailureCode

internal fun archiveFailureMessage(code: ArchiveProbeFailureCode): String = when (code) {
    ArchiveProbeFailureCode.FILE_NOT_FOUND -> "This archive could not be found. Refresh the library and try again."
    ArchiveProbeFailureCode.FILE_NOT_READABLE -> "This archive could not be read. Check folder access and try again."
    ArchiveProbeFailureCode.UNSUPPORTED_FORMAT -> "This file is not a supported archive format."
}

internal fun archiveFailureMessage(code: ArchiveReadFailureCode): String = when (code) {
    ArchiveReadFailureCode.UNSUPPORTED_VARIANT -> "This archive uses an unsupported compression or archive variant."
    ArchiveReadFailureCode.PASSWORD_PROTECTED_OR_ENCRYPTED -> "Password protected or encrypted archives aren't supported."
    ArchiveReadFailureCode.MULTIPART_ARCHIVE -> "Archives split across multiple volumes aren't supported."
    ArchiveReadFailureCode.DECODER_MEMORY_LIMIT_EXCEEDED -> "This archive needs more decoder memory than DML allows."
    ArchiveReadFailureCode.HEADER_LIMIT_EXCEEDED -> "This archive exceeds DML's archive header limits."
    ArchiveReadFailureCode.CORRUPT_OR_UNSUPPORTED -> "This archive may be damaged or use an unsupported archive feature."
    ArchiveReadFailureCode.UNSAFE_ENTRY_PATH -> "This archive contains an unsafe file path. Installation stopped."
    ArchiveReadFailureCode.DUPLICATE_ENTRY_PATH,
    ArchiveReadFailureCode.CASE_COLLISION -> "This archive contains conflicting file paths. Installation stopped."
    ArchiveReadFailureCode.ENTRY_LIMIT_EXCEEDED -> "This archive contains more entries than DML allows."
    ArchiveReadFailureCode.FILE_SIZE_LIMIT_EXCEEDED -> "A file in this archive exceeds DML's extraction size limit."
    ArchiveReadFailureCode.TOTAL_SIZE_LIMIT_EXCEEDED -> "This archive exceeds DML's total extraction size limit."
    ArchiveReadFailureCode.PATH_LENGTH_LIMIT_EXCEEDED,
    ArchiveReadFailureCode.PATH_DEPTH_LIMIT_EXCEEDED -> "This archive contains paths longer or deeper than DML allows."
    ArchiveReadFailureCode.INSUFFICIENT_STORAGE -> "Not enough free storage remains to unpack this archive safely. Free some space and try again."
    ArchiveReadFailureCode.IO_FAILURE -> "The archive could not be read or unpacked. Check storage and folder access, then try again."
}
