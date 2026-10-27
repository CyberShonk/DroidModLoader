package com.shonkware.droidmodloader.ui.archive

import com.shonkware.droidmodloader.engine.install.ArchiveInspection

/** One historical observation, not a verdict about the current source or installed mod. */
data class ArchiveInspectionReport(
    val archiveName: String,
    val observedAtMillis: Long,
    val inspection: ArchiveInspection? = null,
    val failureCode: String? = null,
    val message: String? = null
)
