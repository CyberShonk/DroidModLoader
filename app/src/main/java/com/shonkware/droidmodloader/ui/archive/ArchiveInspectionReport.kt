package com.shonkware.droidmodloader.ui.archive

import com.shonkware.droidmodloader.engine.install.ArchiveInspection
import com.shonkware.droidmodloader.engine.install.InstallerType

/** Preparation only. Even PREPARED still needs the normal naming/confirmation flow. */
enum class ArchivePreparationStatus { BLOCKED, NEEDS_CHOICES, PREPARED, UNKNOWN }

/** One historical observation, not a verdict about the current source or installed mod. */
data class ArchiveInspectionReport(
    val archiveName: String,
    val observedAtMillis: Long,
    val inspection: ArchiveInspection? = null,
    val failureCode: String? = null,
    val message: String? = null,
    /** Links this observation to the existing attempt and its captured context, not current UI state. */
    val attemptId: String? = null
) {
    val preparationStatus: ArchivePreparationStatus
        get() = when {
            failureCode != null -> ArchivePreparationStatus.BLOCKED
            inspection == null -> ArchivePreparationStatus.UNKNOWN
            inspection.installerType == InstallerType.SIMPLE -> ArchivePreparationStatus.PREPARED
            else -> ArchivePreparationStatus.NEEDS_CHOICES
        }
}
