package com.shonkware.droidmodloader.ui.workflow

import java.io.File
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.io.ArchiveImportFileStore
import com.shonkware.droidmodloader.engine.model.Mod

internal class ArchiveImportExecutionWorkflow(
    private val operationInProgressProvider: () -> Boolean,
    private val beginOperation: (String) -> Unit,
    private val createEngine: () -> ModEngine?,
    private val archiveImportFileStore: ArchiveImportFileStore,
    private val showInstallerChoices: (PreparedArchiveInstall, String) -> Unit,
    private val appendLog: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val syncPluginsFromCurrentState: (ModEngine) -> Unit,
    private val appendInstalledModRoutingSummary: (ModEngine, Mod) -> Unit,
    private val refreshDashboard: () -> Unit
) {

    fun importArchive(sourceFile: File) {
        if (operationInProgressProvider()) {
            appendLog("Ignoring import request: operation already in progress.")
            return
        }

        beginOperation("Importing archive...")

        val engine = createEngine()
        if (engine == null) {
            failOperation("Import archive failed: engine could not be created.", null)
            return
        }

        val fileName = sourceFile.name.takeIf { it.isNotBlank() } ?: "imported_mod"
        val sanitizedName = sanitizeArchiveDisplayName(fileName)

        try {
            val archiveLibraryFile = archiveImportFileStore.copyFileToArchiveLibraryFile(
                sourceFile = sourceFile,
                displayName = sanitizedName
            )

            val archiveRecord = engine.registerDownloadedArchive(
                archiveFile = archiveLibraryFile,
                originalDisplayName = fileName,
                sourcePath = sourceFile.canonicalPath
            )

            appendLog("Archive saved to library: ${archiveRecord.fileName}")
            appendLog("Archive format: ${archiveRecord.archiveFormat}")
            appendLog("Archive size: ${archiveRecord.sizeBytes} bytes")
            appendLog("Archive record ID: ${archiveRecord.archiveId}")
            appendLog("About to install imported archive using engine...")

            val existingMods = engine.getInstalledModsFromFolders()
            val nextPriority = calculateNextArchivePriority(existingMods)
            val prepared = engine.prepareArchiveInstall(archiveLibraryFile)

            if (prepared.plan.requiresUserChoice) {
                showInstallerChoices(prepared, archiveRecord.archiveId)

                appendLog("Installer choices required: ${prepared.plan.installerType}")
                appendLog("Pending installer archive record ID: ${archiveRecord.archiveId}")
                prepared.plan.warnings.forEach { appendLog("INSTALLER WARNING: $it") }

                finishOperation("Choose installer options.")
                return
            }

            val installedMod = engine.finalizePreparedArchiveInstall(
                prepared = prepared,
                selectedOptionIds = prepared.plan.defaultSelectedOptionIds,
                priority = nextPriority,
                sourceType = "imported_archive"
            )

            engine.markDownloadedArchiveInstalled(
                archiveId = archiveRecord.archiveId,
                installedModId = installedMod.id
            )

            appendLog("Archive install returned successfully.")
            appendLog("Archive record marked installed: ${archiveRecord.archiveId}")

            val currentMods = engine.getCurrentMods()
                .filterNot { it.id == installedMod.id }
                .sortedBy { it.priority }

            val updatedMods = currentMods + installedMod.copy(priority = currentMods.size + 1)
            engine.saveCurrentMods(updatedMods)

            syncPluginsFromCurrentState(engine)

            appendLog("Installed imported mod: $installedMod")
            appendInstalledModRoutingSummary(engine, installedMod)
            appendLog("Saved installed mod count after import: ${updatedMods.size}")
            appendLog("Plugins refreshed automatically.")
            appendLog("RESULT: PASS")
            finishOperation("Archive imported successfully.")
        } catch (t: Throwable) {
            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            failOperation("Import archive failed: ${t.message}", t)
        }

        refreshDashboard()
        appendLog("----- Import Archive Workflow End -----")
    }
}

internal fun sanitizeArchiveDisplayName(displayName: String): String {
    return displayName.replace(Regex("""[^\w.\- ]"""), "_")
}

internal fun calculateNextArchivePriority(existingMods: List<Mod>): Int {
    return if (existingMods.isEmpty()) {
        1
    } else {
        existingMods.maxOf { it.priority } + 1
    }
}
