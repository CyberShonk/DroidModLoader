package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.ArchiveExtractionFacts
import com.shonkware.droidmodloader.engine.install.ArchiveInspection
import com.shonkware.droidmodloader.engine.install.ArchiveFormat
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import com.shonkware.droidmodloader.engine.install.InstallerGroup
import com.shonkware.droidmodloader.engine.install.InstallerPlan
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.io.ArchiveImportFileStore
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveImportExecutionWorkflowTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sanitizeArchiveDisplayName_replacesUnsupportedCharacters() {
        assertEquals(
            "A_Mod_Name__1_.7z",
            sanitizeArchiveDisplayName(
                "A/Mod:Name?[1].7z"
            )
        )
    }

    @Test
    fun sanitizeArchiveDisplayName_preservesSupportedCharacters() {
        assertEquals(
            "My Mod-1.2_test.zip",
            sanitizeArchiveDisplayName(
                "My Mod-1.2_test.zip"
            )
        )
    }

    @Test
    fun calculateNextArchivePriority_returnsOneForEmptyList() {
        assertEquals(
            1,
            calculateNextArchivePriority(
                emptyList()
            )
        )
    }

    @Test
    fun calculateNextArchivePriority_returnsOneMoreThanHighestPriority() {
        val mods = listOf(
            mod(
                id = "first",
                priority = 10
            ),
            mod(
                id = "second",
                priority = 3
            ),
            mod(
                id = "third",
                priority = 25
            )
        )

        assertEquals(
            26,
            calculateNextArchivePriority(mods)
        )
    }

    @Test
    fun activeOnlyCancellationDoesNotCreateIdleEngineOrChangeStatus() {
        val harness = WorkflowHarness(FakeArchiveImportEngine())

        assertFalse(harness.workflow.cancelActiveImport())

        assertEquals(0, harness.engineCreationCount)
        assertTrue(harness.statuses.isEmpty())
        assertTrue(harness.logs.isEmpty())
        assertTrue(harness.archiveLibraryFiles().isEmpty())
    }

    @Test
    fun activeOnlyCancellationSignalsPreparationWithoutStartingAnotherEngine() {
        val engine = FakeArchiveImportEngine()
        lateinit var harness: WorkflowHarness
        engine.beforePrepareCompletes = { signal ->
            assertTrue(harness.workflow.cancelActiveImport())
            signal.throwIfCancellationRequested()
        }
        harness = WorkflowHarness(engine)

        harness.workflow.importArchive(harness.sourceArchive)

        assertEquals(1, harness.engineCreationCount)
        assertEquals(listOf("Archive import cancelled."), harness.cancelMessages)
        assertTrue(harness.failures.isEmpty())
        assertFalse(engine.finalizeCalled)
        assertFalse(engine.registeredArchiveFile?.exists() == true)
        assertFalse(harness.workflow.cancelActiveImport())
    }

    @Test
    fun cancellationDuringPreparationKeepsRecordButRemovesSnapshot() {
        val engine = FakeArchiveImportEngine()
        lateinit var harness: WorkflowHarness

        engine.beforePrepareCompletes = {
                cancellationSignal ->
            harness.workflow.cancelImport()
            cancellationSignal
                .throwIfCancellationRequested()
        }

        harness = WorkflowHarness(engine)

        harness.workflow.importArchive(
            harness.sourceArchive
        )

        assertNotNull(
            engine.registeredArchiveFile
        )
        assertFalse(
            engine.registeredArchiveFile
                ?.exists() == true
        )
        assertFalse(engine.finalizeCalled)
        assertTrue(
            engine.cancelledPreparedArchives
                .isEmpty()
        )
        assertEquals(
            listOf(
                "Archive import cancelled."
            ),
            harness.cancelMessages
        )
        assertEquals(
            listOf(
                "Cancelling archive import..."
            ),
            harness.statuses
        )
        assertTrue(
            harness.failures.isEmpty()
        )
        assertTrue(
            harness.logs.contains(
                "RESULT: CANCELLED"
            )
        )
        assertEquals(
            1,
            harness.refreshCount
        )

        harness.workflow.cancelImport()

        assertTrue(
            harness.logs.contains(
                "Ignoring archive import cancellation " +
                        "request: no archive import is active."
            )
        )
    }

    @Test
    fun cancellationDuringAutomaticFinalizeCleansPreparedSession() {
        val engine = FakeArchiveImportEngine()
        lateinit var harness: WorkflowHarness

        engine.beforeFinalizeCompletes = {
                cancellationSignal ->
            harness.workflow.cancelImport()
            cancellationSignal
                .throwIfCancellationRequested()
        }

        harness = WorkflowHarness(engine)

        harness.workflow.importArchive(
            harness.sourceArchive
        )

        assertTrue(engine.finalizeCalled)
        assertEquals(
            listOf("Example Archive.zip"),
            engine.cancelledPreparedArchives
                .map { it.archiveName }
        )
        assertNull(
            engine.markedInstalled
        )
        assertTrue(
            engine.savedMods.isEmpty()
        )
        assertEquals(
            listOf(
                "Archive import cancelled."
            ),
            harness.cancelMessages
        )
        assertTrue(
            harness.finishMessages.isEmpty()
        )
        assertTrue(
            harness.failures.isEmpty()
        )
        assertTrue(
            harness.logs.contains(
                "RESULT: CANCELLED"
            )
        )
        assertFalse(
            engine.registeredArchiveFile
                ?.exists() == true
        )
    }

    @Test
    fun choiceRequiredImportHandsPreparedSessionToInstaller() {
        val engine =
            FakeArchiveImportEngine(
                preparedPlan = InstallerPlan(
                    installerType =
                        InstallerType.FOMOD,
                    displayName =
                        "Example Installer",
                    rootPath = "",
                    groups =
                        emptyList<InstallerGroup>()
                )
            )
        val harness = WorkflowHarness(engine)

        harness.workflow.importArchive(
            harness.sourceArchive
        )

        assertFalse(engine.finalizeCalled)
        assertFalse(engine.registeredArchiveFile!!.exists())
        assertTrue(harness.sourceArchive.exists())
        assertTrue(harness.archiveLibraryFiles().isEmpty())
        assertEquals(
            "archive-1",
            harness.shownArchiveRecordId
        )
        assertEquals(
            "Example Archive.zip",
            harness.shownPrepared
                ?.archiveName
        )
        assertEquals(
            listOf(
                "Choose installer options."
            ),
            harness.attempts.waitingMessages
        )
        assertTrue(harness.finishMessages.isEmpty())
        assertEquals(OperationPhase.WAITING_FOR_CHOICES, harness.shownOperation!!.snapshot().phase)
        assertNull(harness.shownOperation!!.snapshot().outcome)
        assertTrue(
            harness.cancelMessages.isEmpty()
        )
        assertTrue(
            harness.failures.isEmpty()
        )
        assertEquals(
            1,
            harness.refreshCount
        )

        harness.workflow.cancelImport()

        assertTrue(
            harness.logs.contains(
                "Ignoring archive import cancellation " +
                        "request: no archive import is active."
            )
        )
    }


    @Test
    fun rar5PreflightLogsDistinctFormatAndContinues() {
        val engine = FakeArchiveImportEngine()
        val harness = WorkflowHarness(engine)
        val sourceArchive =
            harness.newSourceArchive(
                name = "Supported RAR5.rar",
                bytes = byteArrayOf(
                    0x52, 0x61, 0x72, 0x21,
                    0x1A, 0x07, 0x01, 0x00
                )
            )

        harness.workflow.importArchive(sourceArchive)

        assertNotNull(engine.registeredArchiveFile)
        assertTrue(sourceArchive.exists())
        assertTrue(harness.logs.contains("Archive preflight format: rar5"))
        assertTrue(harness.logs.contains("Archive format: rar5"))
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun unknownFormatPreflightRejectsBeforeCopyOrRegistration() {
        val engine = FakeArchiveImportEngine()
        val harness = WorkflowHarness(engine)
        val sourceArchive =
            harness.newSourceArchive(
                name = "Unknown.zip",
                bytes = byteArrayOf(
                    0x00, 0x01, 0x02, 0x03,
                    0x04, 0x05, 0x06, 0x07
                )
            )

        harness.workflow.importArchive(sourceArchive)

        assertNull(engine.registeredArchiveFile)
        assertTrue(harness.archiveLibraryFiles().isEmpty())
        assertTrue(sourceArchive.exists())
        assertTrue(
            harness.logs.contains(
                "ARCHIVE FAILURE: UNSUPPORTED_FORMAT"
            )
        )
        assertFalse(
            harness.logs.any {
                it.startsWith("CRASH TYPE:")
            }
        )
        assertEquals(1, harness.failures.size)
        assertNull(harness.failures.single().second)
    }

    @Test
    fun rar4PreflightLogsDistinctFormatAndContinues() {
        val engine = FakeArchiveImportEngine()
        val harness = WorkflowHarness(engine)
        val sourceArchive =
            harness.newSourceArchive(
                name = "Supported RAR4.rar",
                bytes = byteArrayOf(
                    0x52, 0x61, 0x72, 0x21,
                    0x1A, 0x07, 0x00
                )
            )

        harness.workflow.importArchive(sourceArchive)

        assertNotNull(engine.registeredArchiveFile)
        assertTrue(
            harness.logs.contains(
                "Archive preflight format: rar4"
            )
        )
        assertTrue(
            harness.logs.contains(
                "Archive format: rar4"
            )
        )
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun unexpectedFailureStillUsesCrashDiagnosticsAndCleansCopy() {
        val failure =
            IllegalStateException("registration failed")
        val engine =
            FakeArchiveImportEngine().apply {
                registerFailure = failure
            }
        val harness = WorkflowHarness(engine)

        harness.workflow.importArchive(
            harness.sourceArchive
        )

        assertNull(engine.registeredArchiveFile)
        assertTrue(harness.archiveLibraryFiles().isEmpty())
        assertTrue(
            harness.logs.contains(
                "CRASH TYPE: java.lang.IllegalStateException"
            )
        )
        assertTrue(harness.logs.contains("RESULT: FAIL"))
        assertEquals(1, harness.failures.size)
        assertSame(
            failure,
            harness.failures.single().second
        )
    }

    @Test
    fun repeatedAutomaticImportsNeverAccumulateSnapshotsOrRenameTheMod() {
        val engine = FakeArchiveImportEngine()
        val harness = WorkflowHarness(engine)
        val original = harness.sourceArchive.readBytes()
        repeat(3) {
            harness.workflow.importArchive(harness.sourceArchive)
            assertEquals("Example Archive.zip", engine.registeredArchiveFile!!.name)
            assertFalse(engine.registeredArchiveFile!!.exists())
            assertTrue(harness.archiveLibraryFiles().isEmpty())
            org.junit.Assert.assertArrayEquals(original, harness.sourceArchive.readBytes())
        }
        assertEquals(3, harness.finishMessages.size)
        assertTrue(harness.failures.isEmpty())
        assertEquals("installed", engine.markedInstalled!!.second)
    }

    @Test
    fun failureAfterRegistrationDuringPreparationOrFinalizationRemovesOnlySnapshot() {
        val engine = FakeArchiveImportEngine()
        val harness = WorkflowHarness(engine)
        engine.beforePrepareCompletes = { throw java.io.IOException("prepare failure") }
        harness.workflow.importArchive(harness.sourceArchive)
        assertTrue(harness.archiveLibraryFiles().isEmpty())
        assertTrue(harness.sourceArchive.exists())
        engine.beforePrepareCompletes = null
        engine.beforeFinalizeCompletes = { throw java.io.IOException("finalize failure") }
        harness.workflow.importArchive(harness.sourceArchive)
        assertTrue(harness.archiveLibraryFiles().isEmpty())
        assertTrue(harness.sourceArchive.exists())
        assertEquals(2, harness.failures.size)
    }

    @Test
    fun normalImportPublishesPreparationFactsWithoutASecondPreparation() {
        val engine = FakeArchiveImportEngine()
        val inspection = ArchiveInspection(ArchiveExtractionFacts("snapshot", 1234, ArchiveFormat.ZIP,
            false, 10, 1, 1, 20), automaticInstallerType())
        engine.preparedInspection = inspection
        val harness = WorkflowHarness(engine)
        harness.workflow.importArchive(harness.sourceArchive)
        assertEquals(1, engine.prepareCount)
        assertSame(inspection, harness.inspections.single().inspection)
        assertTrue(engine.finalizeCalled)
        assertEquals(listOf("Archive imported successfully."), harness.finishMessages)
    }

    @Test
    fun cancellationAfterPreparationCannotPublishCompletedFactsOrFinalize() {
        val engine = FakeArchiveImportEngine()
        engine.preparedInspection = ArchiveInspection(ArchiveExtractionFacts("snapshot", 1234,
            ArchiveFormat.ZIP, false, 10, 1, 1, 20), automaticInstallerType())
        val harness = WorkflowHarness(engine)
        engine.beforePrepareCompletes = { harness.workflow.cancelActiveImport() }
        harness.workflow.importArchive(harness.sourceArchive)
        assertTrue(harness.inspections.isEmpty())
        assertFalse(engine.finalizeCalled)
        assertEquals(1, engine.cancelledPreparedArchives.size)
    }

    private inner class WorkflowHarness(
        engine: FakeArchiveImportEngine?
    ) {
        private val external =
            temporaryFolder.newFolder("external")

        val importActiveStates = mutableListOf<Boolean>()
        val inspections = mutableListOf<ArchiveInspectionReport>()
        val sourceArchive =
            newSourceArchive(
                name = "Example Archive.zip",
                bytes = byteArrayOf(
                    0x50, 0x4B, 0x03, 0x04,
                    0x00, 0x00, 0x00, 0x00
                )
            )

        val logs =
            mutableListOf<String>()

        val errors =
            mutableListOf<
                    Pair<String, Throwable?>
                    >()

        val attempts = RecordingOperationAttempts()
        val beginMessages = attempts.beginMessages
        val finishMessages = attempts.finishMessages
        val cancelMessages = attempts.cancelMessages
        val failures = attempts.failures

        val statuses =
            mutableListOf<String>()

        var refreshCount = 0
        var shownPrepared:
                PreparedArchiveInstall? = null
        var shownArchiveRecordId:
                String? = null
        var shownOperation: OperationAttempt? = null

        private val fileStore =
            ArchiveImportFileStore(
                externalFilesDirProvider = {
                    external
                },
                appendError = { message ->
                    errors.add(
                        message to null
                    )
                }
            )

        fun newSourceArchive(
            name: String,
            bytes: ByteArray
        ): File {
            return temporaryFolder
                .newFile(name)
                .apply {
                    writeBytes(bytes)
                }
        }

        fun archiveLibraryFiles(): List<File> {
            return File(
                external,
                "downloads/import_staging"
            ).listFiles()?.toList().orEmpty()
        }

        var engineCreationCount = 0
        val workflow =
            ArchiveImportExecutionWorkflow(
                operationInProgressProvider = {
                    false
                },
                beginOperation = attempts::start,
                createEngine = {
                    engineCreationCount++
                    engine
                },
                archiveImportFileStore =
                    fileStore,
                showInstallerChoices = {
                        prepared,
                        archiveRecordId,
                        operation ->
                    shownPrepared = prepared
                    shownOperation = operation
                    shownArchiveRecordId =
                        archiveRecordId
                },
                appendLog = { message ->
                    logs.add(message)
                },
                appendError = {
                        message,
                        throwable ->
                    errors.add(
                        message to throwable
                    )
                },
                updateLastOperationStatus = {
                        status ->
                    statuses.add(status)
                },
                updateArchiveImportInProgress = { active ->
                    importActiveStates.add(active)
                },
                refreshDashboard = {
                    refreshCount++
                },
                inspectionPublisher = { { report -> inspections.add(report); Unit } }
            )
    }

    private inner class FakeArchiveImportEngine(
        private val preparedPlan:
        InstallerPlan =
            InstallerPlan(
                installerType =
                    automaticInstallerType(),
                displayName =
                    "Automatic Installer",
                rootPath = "",
                groups =
                    emptyList<InstallerGroup>()
            )
    ) : ArchiveImportEngine {
        var registeredArchiveFile:
                File? = null

        var registerFailure:
                RuntimeException? = null

        var beforePrepareCompletes:
                ((InstallCancellationSignal) -> Unit)? =
            null

        var beforeFinalizeCompletes:
                ((InstallCancellationSignal) -> Unit)? =
            null

        var finalizeCalled = false
        var prepareCount = 0
        var preparedInspection: ArchiveInspection? = null

        val cancelledPreparedArchives =
            mutableListOf<
                    PreparedArchiveInstall
                    >()

        var markedInstalled:
                Pair<String, String>? = null

        var savedMods:
                List<Mod> = emptyList()

        override fun registerDownloadedArchive(
            archiveFile: File,
            originalDisplayName: String,
            sourcePath: String?
        ): DownloadedArchiveRecord {
            registerFailure?.let { failure ->
                throw failure
            }

            registeredArchiveFile =
                archiveFile

            return DownloadedArchiveRecord(
                archiveId = "archive-1",
                displayName =
                    originalDisplayName,
                fileName =
                    archiveFile.name,
                archiveFormat = "zip",
                relativePath =
                    archiveFile.name,
                sizeBytes =
                    archiveFile.length(),
                modifiedAtMillis =
                    archiveFile.lastModified(),
                fingerprint =
                    "fingerprint",
                sourcePath = sourcePath
            )
        }

        override fun getInstalledModsFromFolders():
                List<Mod> {
            return emptyList()
        }

        override fun prepareArchiveInstall(
            archive: File,
            cancellationSignal:
            InstallCancellationSignal
        ): PreparedArchiveInstall {
            prepareCount++
            beforePrepareCompletes?.invoke(
                cancellationSignal
            )

            return prepared(
                plan = preparedPlan
            ).copy(inspection = preparedInspection)
        }

        override fun finalizePreparedArchiveInstall(
            prepared:
            PreparedArchiveInstall,
            selectedOptionIds:
            Set<String>,
            priority: Int,
            sourceType: String,
            cancellationSignal:
            InstallCancellationSignal
        ): Mod {
            finalizeCalled = true

            beforeFinalizeCompletes?.invoke(
                cancellationSignal
            )

            return mod(
                id = "installed",
                priority = priority
            )
        }

        override fun markDownloadedArchiveInstalled(
            archiveId: String,
            installedModId: String
        ) {
            markedInstalled =
                archiveId to installedModId
        }

        override fun getCurrentMods():
                List<Mod> {
            return savedMods
        }

        override fun saveCurrentMods(
            mods: List<Mod>
        ) {
            savedMods = mods
        }

        override fun syncPlugins() = Unit

        override fun appendInstalledModRoutingSummary(
            mod: Mod
        ) = Unit

        override fun cancelPreparedArchiveInstall(
            prepared:
            PreparedArchiveInstall
        ) {
            cancelledPreparedArchives.add(
                prepared
            )
        }
    }

    private fun prepared(
        plan: InstallerPlan
    ): PreparedArchiveInstall {
        return PreparedArchiveInstall(
            archivePath =
                "/archives/example.zip",
            archiveName =
                "Example Archive.zip",
            modName =
                "Example Archive",
            sessionRootPath =
                "/tmp/session",
            extractedRootPath =
                "/tmp/session/extracted",
            installRootPath =
                "/tmp/session/install",
            plan = plan
        )
    }

    private fun mod(
        id: String,
        priority: Int
    ): Mod {
        return Mod(
            id = id,
            name = id,
            installPath = "/mods/$id",
            enabled = true,
            priority = priority,
            modType = ModType.LOOSE
        )
    }

    companion object {
        private fun automaticInstallerType():
                InstallerType {
            return enumValues<InstallerType>()
                .first { installerType ->
                    installerType !=
                            InstallerType.BAIN &&
                            installerType !=
                            InstallerType.FOMOD &&
                            installerType !=
                            InstallerType
                                .MANUAL_DATA_FOLDER
                }
        }
    }
}
