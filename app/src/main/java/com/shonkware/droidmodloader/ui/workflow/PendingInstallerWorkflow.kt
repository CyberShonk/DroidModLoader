package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.InstallerOptionSelectionHelper
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        sourceType: String,
        cancellationSignal:
        InstallCancellationSignal
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
        sourceType: String,
        cancellationSignal:
        InstallCancellationSignal
    ): Mod {
        return engine.finalizePreparedArchiveInstall(
            prepared = prepared,
            selectedOptionIds =
                selectedOptionIds,
            priority = priority,
            sourceType = sourceType,
            cancellationSignal =
                cancellationSignal
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
    private val cancelOperation: (String) -> Unit,
    private val failOperation: (String, Throwable?) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val updateSelectedOptionIds: (Set<String>) -> Unit,
    private val clearPendingInstallerState: () -> Unit,
    private val refreshDashboard: () -> Unit
) {
    private val activeCancellationController =
        AtomicReference<
                InstallCancellationController?
                >(null)
    fun finalizePendingInstall() {
        prepareFinalization()?.run()
    }

    /** Reserve the immutable choices and cancellation handle while admission is held. */
    fun prepareFinalization(): Finalization? {
        if (isOperationInProgress()) {
            appendLog(
                "Ignoring installer finalize request: " +
                        "operation already in progress."
            )
            return null
        }

        val session = pendingSessionProvider()?.let {
            it.copy(selectedOptionIds = it.selectedOptionIds.toSet())
        }

        if (session == null) {
            appendError(
                "No pending installer session found.",
                null
            )
            return null
        }

        val cancellationController =
            InstallCancellationController()

        if (
            !activeCancellationController
                .compareAndSet(
                    null,
                    cancellationController
                )
        ) {
            appendLog(
                "Ignoring installer finalize request: " +
                        "installer finalization is already active."
            )
            return null
        }
        return Finalization(session, cancellationController)
    }

    inner class Finalization internal constructor(
        private val session: PendingInstallerSession,
        private val cancellationController: InstallCancellationController
    ) {
        private val consumed = AtomicBoolean(false)

        fun run() {
            if (consumed.compareAndSet(false, true)) {
                executeFinalization(session, cancellationController)
            }
        }

        fun abandon() {
            if (consumed.compareAndSet(false, true)) {
                activeCancellationController.compareAndSet(cancellationController, null)
            }
        }
    }

    private fun executeFinalization(
        session: PendingInstallerSession,
        cancellationController: InstallCancellationController
    ) {
        var cleanupEngine: PendingInstallerEngine? = null
        try {
            beginOperation(
                "Installing selected options..."
            )
            val engine = createEngine()
            cleanupEngine = engine
            if (engine == null) {
                failOperation("Install failed: could not create engine.", null)
                return
            }
            cancellationController.signal.throwIfCancellationRequested()
            val existingMods =
                engine.getCurrentMods()

            val nextPriority =
                calculateNextPendingInstallerPriority(
                    existingMods
                )

            val installedMod =
                engine.finalizePreparedArchiveInstall(
                    prepared = session.prepared,
                    selectedOptionIds =
                        session.selectedOptionIds,
                    priority = nextPriority,
                    sourceType =
                        "imported_archive",
                    cancellationSignal =
                        cancellationController.signal
                )

            val currentMods =
                engine.getCurrentMods()
                    .filterNot {
                        it.id == installedMod.id
                    }
                    .sortedBy { it.priority }

            val updatedMods =
                currentMods +
                        installedMod.copy(
                            priority =
                                currentMods.size + 1
                        )

            engine.saveCurrentMods(updatedMods)

            val archiveRecordId =
                session.archiveRecordId

            if (!archiveRecordId.isNullOrBlank()) {
                engine.markDownloadedArchiveInstalled(
                    archiveId = archiveRecordId,
                    installedModId =
                        installedMod.id
                )

                appendLog(
                    "Archive record marked installed: " +
                            archiveRecordId
                )
            } else {
                appendLog(
                    "No archive record ID was attached " +
                            "to this installer session."
                )
            }

            engine.syncPlugins()
            clearPendingInstallerState()

            appendLog(
                "Installed selected options for: " +
                        session.prepared.archiveName
            )
            appendLog(
                "Installed mod: $installedMod"
            )

            engine.appendInstalledModRoutingSummary(
                installedMod
            )

            appendLog("RESULT: PASS")

            finishOperation(
                "Archive imported successfully."
            )
            refreshDashboard()
        } catch (
            exception: InstallCancelledException
        ) {
            try {
                cleanupEngine?.cancelPreparedArchiveInstall(
                    session.prepared
                )
            } catch (
                cleanupException: Exception
            ) {
                exception.addSuppressed(
                    cleanupException
                )

                appendError(
                    "Failed to clean cancelled " +
                            "installer session: " +
                            cleanupException.message,
                    cleanupException
                )
            }

            clearPendingInstallerState()

            appendLog(
                "Installer session cancelled: " +
                        session.prepared.archiveName
            )
            appendLog("RESULT: CANCELLED")

            cancelOperation(
                "Installer cancelled."
            )
            refreshDashboard()
        } catch (throwable: Throwable) {
            appendLog(
                "CRASH TYPE: " +
                        throwable::class.java.name
            )
            appendLog("RESULT: FAIL")

            failOperation(
                "Installer finalize failed: " +
                        throwable.message,
                throwable
            )
        } finally {
            activeCancellationController
                .compareAndSet(
                    cancellationController,
                    null
                )
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

    fun cancelActiveInstall(): Boolean {
        val activeController = activeCancellationController.get() ?: return false
        activeController.cancel()
        appendLog(
            "Installer cancellation requested for: " +
                pendingSessionProvider()?.prepared?.archiveName.orEmpty()
        )
        updateLastOperationStatus("Cancelling installer...")
        return true
    }

    fun cancelPendingInstall() {
        val session = pendingSessionProvider() ?: return
        if (cancelActiveInstall()) return

        val engine = createEngine() ?: return

        try {
            engine.cancelPreparedArchiveInstall(
                session.prepared
            )

            appendLog(
                "Cancelled installer session for: " +
                        session.prepared.archiveName
            )
        } catch (exception: Exception) {
            appendError(
                "Failed to clean installer session: " +
                        exception.message,
                exception
            )
        }

        clearPendingInstallerState()
        updateLastOperationStatus(
            "Installer cancelled."
        )
    }
}

internal fun calculateNextPendingInstallerPriority(existingMods: List<Mod>): Int {
    return if (existingMods.isEmpty()) {
        1
    } else {
        existingMods.maxOf { it.priority } + 1
    }
}
