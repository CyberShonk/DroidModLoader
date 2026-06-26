package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.download.DownloadedArchiveRecord
import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
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
    fun cancellationDuringPreparationKeepsRegisteredArchive() {
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
        assertTrue(
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
        assertTrue(
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
            harness.finishMessages
        )
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

    private inner class WorkflowHarness(
        engine: FakeArchiveImportEngine?
    ) {
        private val external =
            temporaryFolder.newFolder("external")

        val importActiveStates = mutableListOf<Boolean>()
        val sourceArchive =
            temporaryFolder
                .newFile("Example Archive.zip")
                .apply {
                    writeText("archive payload")
                }

        val logs =
            mutableListOf<String>()

        val errors =
            mutableListOf<
                    Pair<String, Throwable?>
                    >()

        val beginMessages =
            mutableListOf<String>()

        val finishMessages =
            mutableListOf<String>()

        val cancelMessages =
            mutableListOf<String>()

        val failures =
            mutableListOf<
                    Pair<String, Throwable?>
                    >()

        val statuses =
            mutableListOf<String>()

        var refreshCount = 0
        var shownPrepared:
                PreparedArchiveInstall? = null
        var shownArchiveRecordId:
                String? = null

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

        val workflow =
            ArchiveImportExecutionWorkflow(
                operationInProgressProvider = {
                    false
                },
                beginOperation = { message ->
                    beginMessages.add(message)
                },
                createEngine = {
                    engine
                },
                archiveImportFileStore =
                    fileStore,
                showInstallerChoices = {
                        prepared,
                        archiveRecordId ->
                    shownPrepared = prepared
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
                finishOperation = { message ->
                    finishMessages.add(message)
                },
                cancelOperation = { message ->
                    cancelMessages.add(message)
                },
                failOperation = {
                        message,
                        throwable ->
                    failures.add(
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
                }
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

        var beforePrepareCompletes:
                ((InstallCancellationSignal) -> Unit)? =
            null

        var beforeFinalizeCompletes:
                ((InstallCancellationSignal) -> Unit)? =
            null

        var finalizeCalled = false

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
            beforePrepareCompletes?.invoke(
                cancellationSignal
            )

            return prepared(
                plan = preparedPlan
            )
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
