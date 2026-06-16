package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.InstallerOptionSelectionHelper
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.Mod

internal data class PendingInstallerSession(
    val prepared: PreparedArchiveInstall,
    val archiveRecordId: String?,
    val selectedOptionIds: Set<String>
)

internal interface PendingInstallerEngine {
    fun getCurrentMods(): List<Mod>

    fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        sourceType: String
    ): Mod

    fun saveCurrentMods(mods: List<Mod>)
    fun markDownloadedArchiveInstalled(archiveId: String, installedModId: String)
    fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall)
    fun syncPlugins()
    fun appendInstalledModRoutingSummary(mod: Mod)
}

internal class PendingInstallerEngineAdapter(
    private val engine: ModEngine,
    private val syncPlugins: () -> Unit,
    private val appendRoutingSummary: (Mod) -> Unit
) : PendingInstallerEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()

    override fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        sourceType: String
    ): Mod {
        return engine.finalizePreparedArchiveInstall(
            prepared = prepared,
            selectedOptionIds = selectedOptionIds,
            priority = priority,
            sourceType = sourceType
        )
    }

    override fun saveCurrentMods(mods: List<Mod>) {
        engine.saveCurrentMods(mods)
    }

    override fun markDownloadedArchiveInstalled(archiveId: String, installedModId: String) {
        engine.markDownloadedArchiveInstalled(
            archiveId = archiveId,
            installedModId = installedModId
        )
    }

    override fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) {
        engine.cancelPreparedArchiveInstall(prepared)
    }

    override fun syncPlugins() {
        syncPlugins.invoke()
    }

    override fun appendInstalledModRoutingSummary(mod: Mod) {
        appendRoutingSummary(mod)
    }
}

internal class PendingInstallerWorkflow(
    private val pendingSessionProvider: () -> PendingInstallerSession?,
    private val isOperationInProgress: () -> Boolean,
    private val createEngine: () -> PendingInstallerEngine?,
    private val beginOperation: (String) -> Unit,
    private val finishOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val updateSelectedOptionIds: (Set<String>) -> Unit,
    private val clearPendingInstallerState: () -> Unit,
    private val refreshDashboard: () -> Unit
) {
    fun finalizePendingInstall() {
        if (isOperationInProgress()) {
            appendLog("Ignoring installer finalize request: operation already in progress.")
            return
        }

        val session = pendingSessionProvider()
        if (session == null) {
            appendError("No pending installer session found.", null)
            return
        }

        beginOperation("Installing selected options...")

        val engine = createEngine()
        if (engine == null) {
            failOperation("Install failed: could not create engine.", null)
            return
        }

        try {
            val existingMods = engine.getCurrentMods()
            val nextPriority = calculateNextPendingInstallerPriority(existingMods)

            val installedMod = engine.finalizePreparedArchiveInstall(
                prepared = session.prepared,
                selectedOptionIds = session.selectedOptionIds,
                priority = nextPriority,
                sourceType = "imported_archive"
            )

            val currentMods = engine.getCurrentMods()
                .filterNot { it.id == installedMod.id }
                .sortedBy { it.priority }

            val updatedMods = currentMods + installedMod.copy(priority = currentMods.size + 1)
            engine.saveCurrentMods(updatedMods)

            val archiveRecordId = session.archiveRecordId
            if (!archiveRecordId.isNullOrBlank()) {
                engine.markDownloadedArchiveInstalled(
                    archiveId = archiveRecordId,
                    installedModId = installedMod.id
                )

                appendLog("Archive record marked installed: $archiveRecordId")
            } else {
                appendLog("No archive record ID was attached to this installer session.")
            }

            engine.syncPlugins()
            clearPendingInstallerState()

            appendLog("Installed selected options for: ${session.prepared.archiveName}")
            appendLog("Installed mod: $installedMod")
            engine.appendInstalledModRoutingSummary(installedMod)
            appendLog("RESULT: PASS")

            finishOperation("Archive imported successfully.")
            refreshDashboard()
        } catch (t: Throwable) {
            appendLog("CRASH TYPE: ${t::class.java.name}")
            appendLog("RESULT: FAIL")
            failOperation("Installer finalize failed: ${t.message}", t)
        }
    }

    fun toggleOption(optionId: String) {
        val session = pendingSessionProvider() ?: return

        val selectedOptionIds = InstallerOptionSelectionHelper.toggleOption(
            groups = session.prepared.plan.groups,
            selectedOptionIds = session.selectedOptionIds,
            optionId = optionId
        )

        updateSelectedOptionIds(selectedOptionIds)
    }

    fun cancelPendingInstall() {
        val session = pendingSessionProvider() ?: return
        val engine = createEngine() ?: return

        try {
            engine.cancelPreparedArchiveInstall(session.prepared)
            appendLog("Cancelled installer session for: ${session.prepared.archiveName}")
        } catch (e: Exception) {
            appendError("Failed to clean installer session: ${e.message}", e)
        }

        clearPendingInstallerState()
        updateLastOperationStatus("Installer cancelled.")
    }
}

internal fun calculateNextPendingInstallerPriority(existingMods: List<Mod>): Int {
    return if (existingMods.isEmpty()) {
        1
    } else {
        existingMods.maxOf { it.priority } + 1
    }
}
