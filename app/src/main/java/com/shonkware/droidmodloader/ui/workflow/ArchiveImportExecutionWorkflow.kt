package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.install.ArchiveFormatProbe
import com.shonkware.droidmodloader.engine.install.ArchiveFormatProbeException
import com.shonkware.droidmodloader.engine.install.ArchiveReadException
import com.shonkware.droidmodloader.engine.install.ArchiveReaderRegistry
import com.shonkware.droidmodloader.engine.install.InstallCancellationController
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.io.ArchiveImportFileStore
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal interface ArchiveImportEngine {
    fun registerDownloadedArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String?
    ): DownloadedArchiveRecord

    fun getInstalledModsFromFolders(): List<Mod>

    fun prepareArchiveInstall(
        archive: File,
        cancellationSignal: InstallCancellationSignal
    ): PreparedArchiveInstall

    fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        sourceType: String,
        cancellationSignal: InstallCancellationSignal
    ): Mod

    fun markDownloadedArchiveInstalled(
        archiveId: String,
        installedModId: String
    )

    fun getCurrentMods(): List<Mod>

    fun saveCurrentMods(mods: List<Mod>)

    fun syncPlugins()

    fun appendInstalledModRoutingSummary(mod: Mod)

    fun cancelPreparedArchiveInstall(
        prepared: PreparedArchiveInstall
    )
}

internal class ArchiveImportEngineAdapter(
    private val engine: ModEngine,
    private val syncPluginsFromCurrentState: () -> Unit,
    private val appendRoutingSummary: (Mod) -> Unit
) : ArchiveImportEngine {
    override fun registerDownloadedArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String?
    ): DownloadedArchiveRecord {
        return engine.registerDownloadedArchive(
            archiveFile = archiveFile,
            originalDisplayName = originalDisplayName,
            sourcePath = sourcePath
        )
    }

    override fun getInstalledModsFromFolders(): List<Mod> {
        return engine.getInstalledModsFromFolders()
    }

    override fun prepareArchiveInstall(
        archive: File,
        cancellationSignal: InstallCancellationSignal
    ): PreparedArchiveInstall {
        return engine.prepareArchiveInstall(
            archive = archive,
            cancellationSignal = cancellationSignal
        )
    }

    override fun finalizePreparedArchiveInstall(
        prepared: PreparedArchiveInstall,
        selectedOptionIds: Set<String>,
        priority: Int,
        sourceType: String,
        cancellationSignal: InstallCancellationSignal
    ): Mod {
        return engine.finalizePreparedArchiveInstall(
            prepared = prepared,
            selectedOptionIds = selectedOptionIds,
            priority = priority,
            sourceType = sourceType,
            cancellationSignal = cancellationSignal
        )
    }

    override fun markDownloadedArchiveInstalled(
        archiveId: String,
        installedModId: String
    ) {
        engine.markDownloadedArchiveInstalled(
            archiveId = archiveId,
            installedModId = installedModId
        )
    }

    override fun getCurrentMods(): List<Mod> {
        return engine.getCurrentMods()
    }

    override fun saveCurrentMods(mods: List<Mod>) {
        engine.saveCurrentMods(mods)
    }

    override fun syncPlugins() {
        syncPluginsFromCurrentState()
    }

    override fun appendInstalledModRoutingSummary(mod: Mod) {
        appendRoutingSummary(mod)
    }

    override fun cancelPreparedArchiveInstall(
        prepared: PreparedArchiveInstall
    ) {
        engine.cancelPreparedArchiveInstall(prepared)
    }
}

internal class ArchiveImportExecutionWorkflow(
    private val operationInProgressProvider: () -> Boolean,
    private val beginOperation: (String) -> OperationAttempt,
    private val createEngine: () -> ArchiveImportEngine?,
    private val archiveImportFileStore: ArchiveImportFileStore,
    private val showInstallerChoices:
        (PreparedArchiveInstall, String, OperationAttempt) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendError: (String, Throwable?) -> Unit,
    private val updateLastOperationStatus: (String) -> Unit,
    private val refreshDashboard: () -> Unit,
    private val updateArchiveImportInProgress:
        (Boolean) -> Unit,
    private val archiveFormatProbe:
        ArchiveFormatProbe = ArchiveFormatProbe(),
    private val archiveReaderRegistry:
        ArchiveReaderRegistry = ArchiveReaderRegistry(),
    private val inspectionPublisher: (String) -> (ArchiveInspectionReport) -> Unit = { {} },
) {
    private val activeCancellationController =
        AtomicReference<InstallCancellationController?>(null)


    fun importArchive(sourceFile: File) {
        if (operationInProgressProvider()) {
            appendLog(
                "Ignoring import request: operation already in progress."
            )
            return
        }

        val cancellationController =
            InstallCancellationController()

        if (
            !activeCancellationController.compareAndSet(
                null,
                cancellationController
            )
        ) {
            appendLog(
                "Ignoring import request: archive import is already active."
            )
            return
        }
        updateArchiveImportInProgress(true)

        var engine: ArchiveImportEngine? = null
        var archiveLibraryFile: File? = null
        var prepared: PreparedArchiveInstall? = null
        var operation: OperationAttempt? = null

        try {
            val publishInspection = inspectionPublisher(sourceFile.absolutePath)
            operation = beginOperation("Importing archive...")

            val fileName =
                sourceFile.name
                    .takeIf { it.isNotBlank() }
                    ?: "imported_mod"

            val sourceProbe =
                archiveFormatProbe.probe(sourceFile)

            appendLog(
                "Archive preflight format: " +
                        sourceProbe.format.diagnosticLabel
            )

            if (sourceProbe.extensionMismatch) {
                appendLog(
                    "ARCHIVE WARNING: File extension does not match " +
                            "detected " +
                            sourceProbe.format.diagnosticLabel +
                            " content."
                )
            }

            archiveReaderRegistry.findReader(
                format = sourceProbe.format,
                archiveName = fileName
            )

            cancellationController.signal
                .throwIfCancellationRequested()

            engine = createEngine()

            if (engine == null) {
                operation.fail("Import archive failed: engine could not be created.")
                return
            }

            val sanitizedName =
                sanitizeArchiveDisplayName(fileName)

            val copiedArchiveFile =
                archiveImportFileStore
                    .copyToTemporarySnapshot(
                        sourceFile = sourceFile,
                        displayName = sanitizedName,
                        cancellationSignal =
                            cancellationController.signal
                    )

            archiveLibraryFile = copiedArchiveFile

            cancellationController.signal
                .throwIfCancellationRequested()

            val archiveRecord =
                engine.registerDownloadedArchive(
                    archiveFile = copiedArchiveFile,
                    originalDisplayName = fileName,
                    sourcePath = sourceFile.canonicalPath
                )

            appendLog(
                "Temporary archive snapshot prepared: " +
                        archiveRecord.fileName
            )
            appendLog(
                "Archive format: " +
                        sourceProbe.format.diagnosticLabel
            )
            appendLog(
                "Archive size: " +
                        archiveRecord.sizeBytes +
                        " bytes"
            )
            appendLog(
                "Archive record ID: " +
                        archiveRecord.archiveId
            )
            appendLog(
                "About to install imported archive using engine..."
            )

            val existingMods =
                engine.getInstalledModsFromFolders()

            val nextPriority =
                calculateNextArchivePriority(existingMods)

            val preparedInstall =
                engine.prepareArchiveInstall(
                    archive = copiedArchiveFile,
                    cancellationSignal =
                        cancellationController.signal
                )

            prepared = preparedInstall
            cancellationController.signal.throwIfCancellationRequested()
            preparedInstall.inspection?.let { inspection ->
                publishInspection(ArchiveInspectionReport(
                    fileName, inspection.extraction.observedAtMillis, inspection
                ))
            }

            if (preparedInstall.plan.requiresUserChoice) {
                showInstallerChoices(
                    preparedInstall,
                    archiveRecord.archiveId,
                    operation
                )

                appendLog(
                    "Installer choices required: " +
                            preparedInstall.plan.installerType
                )
                appendLog(
                    "Pending installer archive record ID: " +
                            archiveRecord.archiveId
                )
                preparedInstall.plan.warnings.forEach { warning ->
                    appendLog(
                        "INSTALLER WARNING: $warning"
                    )
                }

                operation.waitForChoices("Choose installer options.")
                return
            }

            operation.startInstalling("Installing archive...")
            val installedMod =
                engine.finalizePreparedArchiveInstall(
                    prepared = preparedInstall,
                    selectedOptionIds =
                        preparedInstall.plan.defaultSelectedOptionIds,
                    priority = nextPriority,
                    sourceType = "imported_archive",
                    cancellationSignal =
                        cancellationController.signal
                )

            engine.markDownloadedArchiveInstalled(
                archiveId = archiveRecord.archiveId,
                installedModId = installedMod.id
            )

            appendLog(
                "Archive install returned successfully."
            )
            appendLog(
                "Archive record marked installed: " +
                        archiveRecord.archiveId
            )

            val currentMods =
                engine.getCurrentMods()
                    .filterNot {
                        it.id == installedMod.id
                    }
                    .sortedBy {
                        it.priority
                    }

            val updatedMods =
                currentMods +
                        installedMod.copy(
                            priority = currentMods.size + 1
                        )

            engine.saveCurrentMods(updatedMods)
            engine.syncPlugins()

            appendLog(
                "Installed imported mod: $installedMod"
            )
            engine.appendInstalledModRoutingSummary(
                installedMod
            )
            appendLog(
                "Saved installed mod count after import: " +
                        updatedMods.size
            )
            appendLog(
                "Plugins refreshed automatically."
            )
            appendLog("RESULT: PASS")

            operation.succeed("Archive imported successfully.")
        } catch (exception: InstallCancelledException) {
            val activeEngine = engine
            val activePrepared = prepared

            if (activePrepared != null && activeEngine != null) {
                try {
                    activeEngine.cancelPreparedArchiveInstall(
                        activePrepared
                    )
                } catch (cleanupException: Exception) {
                    exception.addSuppressed(cleanupException)
                    appendError(
                        "Failed to clean cancelled archive " +
                                "installer session: " +
                                cleanupException.message,
                        cleanupException
                    )
                }
            }

            appendLog(
                "Archive import cancellation completed."
            )
            appendLog("RESULT: CANCELLED")

            operation?.cancel("Archive import cancelled.")
        } catch (exception: ArchiveFormatProbeException) {
            handleArchiveFailure(
                operation = operation,
                failureCode = exception.code.name,
                exception = exception
            )
        } catch (exception: ArchiveReadException) {
            handleArchiveFailure(
                operation = operation,
                failureCode = exception.code.name,
                exception = exception
            )
        } catch (throwable: Throwable) {
            appendLog(
                "CRASH TYPE: " +
                        throwable::class.java.name
            )
            appendLog("RESULT: FAIL")

            operation?.fail(
                "Import archive failed: " +
                        throwable.message,
                throwable
            )
        } finally {
            archiveLibraryFile?.let { snapshot ->
                try {
                    archiveImportFileStore.releaseSnapshot(snapshot)
                } catch (cleanupFailure: Exception) {
                    appendError(
                        "Temporary archive cleanup failed; startup will retry: ${cleanupFailure.message}",
                        cleanupFailure
                    )
                }
            }
            activeCancellationController.compareAndSet(
                cancellationController,
                null
            )

            updateArchiveImportInProgress(false)

            refreshDashboard()

            appendLog(
                "----- Import Archive Workflow End -----"
            )
        }
    }

    fun cancelImport() {
        if (!cancelActiveImport()) {
            appendLog(
                "Ignoring archive import cancellation request: " +
                    "no archive import is active."
            )
        }
    }

    fun cancelActiveImport(): Boolean {
        val cancellationController = activeCancellationController.get() ?: return false
        cancellationController.cancel()

        updateLastOperationStatus(
            "Cancelling archive import..."
        )
        appendLog(
            "Archive import cancellation requested."
        )
        return true
    }

    private fun handleArchiveFailure(
        operation: OperationAttempt?,
        failureCode: String,
        exception: Exception
    ) {
        appendLog(
            "ARCHIVE FAILURE: $failureCode"
        )
        appendLog("RESULT: FAIL")

        operation?.fail(
            "Import archive failed: " +
                    exception.message.orEmpty(),
            null
        )
    }

}

internal fun sanitizeArchiveDisplayName(
    displayName: String
): String {
    return displayName.replace(
        Regex("""[^\w.\- ]"""),
        "_"
    )
}

internal fun calculateNextArchivePriority(
    existingMods: List<Mod>
): Int {
    return if (existingMods.isEmpty()) {
        1
    } else {
        existingMods.maxOf {
            it.priority
        } + 1
    }
}
