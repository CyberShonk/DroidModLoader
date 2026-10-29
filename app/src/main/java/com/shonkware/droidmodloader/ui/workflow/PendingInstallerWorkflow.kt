package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.install.InstallerOptionSelectionHelper
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.install.ExistingModAction
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.ManualLayoutInventory
import com.shonkware.droidmodloader.engine.install.ManualLayoutProposal
import com.shonkware.droidmodloader.ui.InstallerDialogAction
import com.shonkware.droidmodloader.ui.InstallerDialogState
import com.shonkware.droidmodloader.ui.InstallerPage
import com.shonkware.droidmodloader.ui.manualInstallerRows
import java.io.File
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class PendingInstallerSession(
    val prepared: PreparedArchiveInstall,
    val archiveRecordId: String?,
    val selectedOptionIds: Set<String>,
    val operation: OperationAttempt? = null
)

internal interface PendingInstallerEngine {
    fun getCurrentMods(): List<Mod>
    fun findExistingModName(name: String): String? = getCurrentMods().singleOrNull { it.id == name }?.id

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
    override fun findExistingModName(name: String): String? = engine.findExistingModName(name)

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
    private val beginOperation: (OperationAttempt?, String) -> OperationAttempt,
    private val updatePendingOperation: (OperationAttempt) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val updateSelectedOptionIds: (Set<String>) -> Unit,
    private val clearPendingInstallerState: () -> Unit,
    private val refreshDashboard: () -> Unit,
    private val onExistingInstall: (String) -> Unit = {},
    private val onFinalizationFailure: (String) -> Unit = {}
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
        var operation: OperationAttempt? = null
        try {
            val engine = createEngine()
            cleanupEngine = engine
            checkNotNull(engine) { "Install failed: could not create engine." }
            cancellationController.signal.throwIfCancellationRequested()
            session.prepared.confirmation?.let { confirmation ->
                if (confirmation.existingModAction == ExistingModAction.ASK &&
                    engine.findExistingModName(confirmation.modName) != null) {
                    cancellationController.signal.throwIfCancellationRequested()
                    onExistingInstall(confirmation.modName)
                    return
                }
            }
            operation = beginOperation(session.operation, "Installing selected options...")
            updatePendingOperation(operation)
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

            val previous = existingMods.singleOrNull { it.id == installedMod.id }
            val currentMods = engine.getCurrentMods()
                .filterNot { it.id == installedMod.id }
                .sortedBy { it.priority }
            val updatedMods = (currentMods + installedMod.copy(
                priority = previous?.priority ?: (currentMods.size + 1),
                enabled = previous?.enabled ?: installedMod.enabled
            )).let { if (previous != null) it.sortedBy { mod -> mod.priority } else it }

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

            operation.succeed("Archive imported successfully.")
            refreshDashboard()
        } catch (
            exception: InstallCancelledException
        ) {
            if (operation == null) {
                operation = beginOperation(session.operation, "Cancelling installer...")
                updatePendingOperation(operation)
            }
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

            operation.cancel("Installer cancelled.")
            refreshDashboard()
        } catch (throwable: Throwable) {
            appendLog(
                "CRASH TYPE: " +
                        throwable::class.java.name
            )
            appendLog("RESULT: FAIL")

            if (operation == null) {
                operation = beginOperation(session.operation, "Installing selected options...")
                updatePendingOperation(operation)
            }
            val message = "Install failed: ${throwable.message}"
            onFinalizationFailure(message)
            operation.fail(message, throwable)
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
        val operation = beginOperation(session.operation, "Cancelling installer...")
        updatePendingOperation(operation)

        try {
            engine.cancelPreparedArchiveInstall(
                session.prepared
            )

            appendLog(
                "Cancelled installer session for: " +
                        session.prepared.archiveName
            )
            operation.cancel("Installer cancelled.")
        } catch (exception: Exception) {
            val message = "Failed to clean installer session: ${exception.message}"
            appendError(message, exception)
            operation.fail(message, exception)
        }

        clearPendingInstallerState()
    }
}

/** Worker-only: reuse the extracted session, never reopen the source archive. */
internal fun editManualInstallerChoices(
    prepared: PreparedArchiveInstall,
    choices: InstallerDialogState,
    selectedOptionIds: Set<String>,
    action: InstallerDialogAction,
    signal: InstallCancellationSignal = InstallCancellationSignal.NONE
): Pair<PreparedArchiveInstall, InstallerDialogState> {
    signal.throwIfCancellationRequested()
    val inventory = prepared.manualLayoutInventory ?: ManualLayoutInventory.capture(
        File(prepared.sessionRootPath), File(prepared.extractedRootPath), signal)
    var proposal = choices.proposal ?: run {
        val option = prepared.plan.groups.flatMap { it.options }.firstOrNull { it.id in selectedOptionIds }
        val root = if (prepared.plan.installerType == InstallerType.MANUAL_DATA_FOLDER && option != null) {
            val source = File(prepared.installRootPath, option.sourcePath).toPath().normalize()
            File(prepared.extractedRootPath).toPath().relativize(source).toString().replace(File.separatorChar, '/')
        } else ""
        ManualLayoutProposal(root)
    }
    var expanded = choices.expanded
    when (action) {
        is InstallerDialogAction.Root -> {
            proposal = proposal.selectRoot(action.path)
            expanded = emptySet()
        }
        is InstallerDialogAction.Include -> proposal =
            if (action.included) proposal.includeSubtree(action.path) else proposal.excludeSubtree(action.path)
        is InstallerDialogAction.Expand -> expanded =
            if (action.path in expanded) expanded - action.path else expanded + action.path
        else -> Unit
    }
    val preview = runCatching { inventory.preview(proposal, signal) }
    signal.throwIfCancellationRequested()
    val changed = choices.proposal != proposal
    return prepared.copy(manualLayoutInventory = inventory) to choices.copy(
        page = InstallerPage.FILES, proposal = proposal, expanded = expanded,
        selectedFolder = if (action is InstallerDialogAction.Root) null else choices.selectedFolder,
        rows = manualInstallerRows(inventory, proposal, expanded, signal),
        error = null, layoutError = preview.exceptionOrNull()?.message,
        recognized = preview.getOrNull()?.recognizedLayout == true,
        allowUnrecognized = choices.allowUnrecognized && !changed,
        showUnrecognized = false, showExisting = false,
        existingAction = if (changed) ExistingModAction.ASK else choices.existingAction)
}

internal fun calculateNextPendingInstallerPriority(existingMods: List<Mod>): Int {
    return if (existingMods.isEmpty()) {
        1
    } else {
        existingMods.maxOf { it.priority } + 1
    }
}
