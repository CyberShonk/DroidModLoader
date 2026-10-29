package com.shonkware.droidmodloader.ui

import com.shonkware.droidmodloader.engine.install.ExistingModAction
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.ManualLayoutInventory
import com.shonkware.droidmodloader.engine.install.ManualLayoutProposal

/** Session-only choices. Never restored as a live installer after process death. */
data class InstallerDialogState(
    val modName: String = "",
    val busy: Boolean = false,
    val page: InstallerPage = InstallerPage.NAME,
    val proposal: ManualLayoutProposal? = null,
    val expanded: Set<String> = emptySet(),
    val selectedFolder: String? = null,
    val rows: List<ManualInstallerRow> = emptyList(),
    val error: String? = null,
    val layoutError: String? = null,
    val recognized: Boolean = false,
    val showUnrecognized: Boolean = false,
    val showExisting: Boolean = false,
    val allowUnrecognized: Boolean = false,
    val existingAction: ExistingModAction = ExistingModAction.ASK
)

enum class InstallerPage { NAME, OPTIONS, FILES }

data class ManualInstallerRow(
    val path: String,
    val directory: Boolean,
    val depth: Int,
    val included: Boolean,
    val partial: Boolean,
    val enabled: Boolean
)

sealed interface InstallerDialogAction {
    data class Name(val value: String) : InstallerDialogAction
    data object ChooseFiles : InstallerDialogAction
    data object Back : InstallerDialogAction
    data class Expand(val path: String) : InstallerDialogAction
    data class SelectFolder(val path: String) : InstallerDialogAction
    data class Include(val path: String, val included: Boolean) : InstallerDialogAction
    data class Root(val path: String) : InstallerDialogAction
    data object Install : InstallerDialogAction
    data object InstallAnyway : InstallerDialogAction
    data class Existing(val action: ExistingModAction) : InstallerDialogAction
    data object DismissConfirmation : InstallerDialogAction
}

/** Pure projection, calculated with validation on the session worker, not in composition. */
internal fun manualInstallerRows(
    inventory: ManualLayoutInventory,
    proposal: ManualLayoutProposal,
    expanded: Set<String>,
    signal: InstallCancellationSignal = InstallCancellationSignal.NONE
): List<ManualInstallerRow> {
    val partial = hashSetOf<String>()
    proposal.excludedPaths.forEach { excluded ->
        var parent = excluded.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            partial.add(parent)
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return inventory.entries.mapNotNull { entry ->
        signal.throwIfCancellationRequested()
        val prefix = proposal.rootPath.takeIf { it.isNotEmpty() }?.plus("/") ?: ""
        if (!entry.path.startsWith(prefix) || entry.path == proposal.rootPath) return@mapNotNull null
        val relative = entry.path.removePrefix(prefix)
        var ancestor = entry.path.substringBeforeLast('/', "")
        var visible = true
        var excludedParent = false
        while (ancestor.isNotEmpty() && ancestor != proposal.rootPath) {
            if (ancestor !in expanded) visible = false
            if (ancestor in proposal.excludedPaths) excludedParent = true
            ancestor = ancestor.substringBeforeLast('/', "")
        }
        if (!visible) null else ManualInstallerRow(entry.path, entry.directory,
            relative.count { it == '/' }, !excludedParent && entry.path !in proposal.excludedPaths,
            entry.path in partial, !excludedParent)
    }
}
