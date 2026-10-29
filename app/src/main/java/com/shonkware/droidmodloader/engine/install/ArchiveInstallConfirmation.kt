package com.shonkware.droidmodloader.engine.install

import java.io.IOException
import java.text.Normalizer
import java.util.Locale

enum class ExistingModAction { ASK, ADD_UPDATE, REPLACE }

/** Immutable user choices, revalidated by the installer before any writes. */
data class ArchiveInstallConfirmation(
    val modName: String,
    val manualLayout: ManualLayoutProposal? = null,
    val allowUnrecognizedLayout: Boolean = false,
    val existingModAction: ExistingModAction = ExistingModAction.ASK
)

internal fun modNameKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

fun requireValidModName(name: String) {
    // Leave room for staging/backup suffixes within a filesystem component.
    if (name.isBlank() || name != name.trim() || name.startsWith('.') ||
        name.toByteArray(Charsets.UTF_8).size > 160 ||
        name.any { it.isISOControl() || it in "/\\:*?\"<>|" } ||
        name.endsWith('.') || modNameKey(name).startsWith("_dml_") ||
        modNameKey(name).startsWith("_installing_")) {
        throw IOException("Choose a mod name without folder separators or special characters.")
    }
}
