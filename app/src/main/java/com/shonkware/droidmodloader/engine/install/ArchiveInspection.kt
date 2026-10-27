package com.shonkware.droidmodloader.engine.install

/** Facts from one completed reader pass, never a cache key for an external source file. */
data class ArchiveExtractionFacts(
    val snapshotPath: String,
    val observedAtMillis: Long,
    val format: ArchiveFormat,
    val extensionMismatch: Boolean,
    val archiveBytes: Long,
    val acceptedEntries: Int,
    val completedFiles: Int,
    val expandedBytes: Long,
    val variant: ArchiveVariantFacts = ArchiveVariantFacts()
)

/** Null means not separately observed; a reader slot or filename is not metadata evidence. */
data class ArchiveVariantFacts(
    val encrypted: Boolean? = null,
    val multipart: Boolean? = null,
    val solid: Boolean? = null,
    val compressionMethods: Set<Int>? = null
)

/** Layout recognition is separate from successful, bounded archive reading. */
data class ArchiveInspection(
    val extraction: ArchiveExtractionFacts,
    val installerType: InstallerType?,
    val layoutIssue: String? = null
)
