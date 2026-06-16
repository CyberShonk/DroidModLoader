package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.install.InstallerGroup
import com.shonkware.droidmodloader.engine.install.InstallerGroupType
import com.shonkware.droidmodloader.engine.install.InstallerOption
import com.shonkware.droidmodloader.engine.install.InstallerPlan
import com.shonkware.droidmodloader.engine.install.InstallerType
import com.shonkware.droidmodloader.engine.install.PreparedArchiveInstall
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInstallerWorkflowTest {
    @Test
    fun finalizeInstallsSelectedOptionsAndClearsPendingState() {
        val engine = FakePendingInstallerEngine(
            currentMods = listOf(mod("existing", priority = 4))
        )
        val harness = WorkflowHarness(
            engine = engine,
            session = session(
                archiveRecordId = "archive-1",
                selectedOptionIds = setOf("core", "textures")
            )
        )

        harness.workflow.finalizePendingInstall()

        assertEquals(setOf("core", "textures"), engine.finalizedSelectedOptionIds)
        assertEquals(5, engine.finalizedPriority)
        assertEquals("imported_archive", engine.finalizedSourceType)
        assertEquals(listOf("existing", "installed"), engine.savedMods.map { it.id })
        assertEquals(listOf(4, 2), engine.savedMods.map { it.priority })
        assertEquals("archive-1" to "installed", engine.markedArchive)
        assertEquals(1, engine.syncCount)
        assertEquals(listOf("installed"), engine.routingSummaryModIds)
        assertNull(harness.session)
        assertEquals(1, harness.clearCount)
        assertEquals(listOf("Installing selected options..."), harness.beginMessages)
        assertEquals(listOf("Archive imported successfully."), harness.finishMessages)
        assertEquals(1, harness.refreshCount)
        assertTrue(harness.logs.contains("RESULT: PASS"))
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun finalizeWithoutArchiveRecordStillInstallsAndLogsMissingRecord() {
        val engine = FakePendingInstallerEngine()
        val harness = WorkflowHarness(
            engine = engine,
            session = session(archiveRecordId = null)
        )

        harness.workflow.finalizePendingInstall()

        assertNull(engine.markedArchive)
        assertTrue(
            harness.logs.contains(
                "No archive record ID was attached to this installer session."
            )
        )
        assertNull(harness.session)
        assertEquals(1, harness.refreshCount)
    }

    @Test
    fun finalizeIsIgnoredWhileAnotherOperationIsRunning() {
        val engine = FakePendingInstallerEngine()
        val harness = WorkflowHarness(engine = engine, session = session()).apply {
            operationInProgress = true
        }

        harness.workflow.finalizePendingInstall()

        assertFalse(engine.finalizeCalled)
        assertEquals(
            listOf("Ignoring installer finalize request: operation already in progress."),
            harness.logs
        )
        assertTrue(harness.beginMessages.isEmpty())
        assertEquals(0, harness.clearCount)
    }

    @Test
    fun finalizeWithoutPendingSessionReportsError() {
        val harness = WorkflowHarness(
            engine = FakePendingInstallerEngine(),
            session = null
        )

        harness.workflow.finalizePendingInstall()

        assertEquals("No pending installer session found.", harness.errors.single().first)
        assertTrue(harness.beginMessages.isEmpty())
        assertEquals(0, harness.clearCount)
    }

    @Test
    fun toggleOptionUpdatesSelectedIdsUsingInstallerRules() {
        val required = InstallerOption(
            id = "required",
            name = "Required",
            sourcePath = "required",
            required = true
        )
        val first = InstallerOption(
            id = "first",
            name = "First",
            sourcePath = "first"
        )
        val second = InstallerOption(
            id = "second",
            name = "Second",
            sourcePath = "second"
        )
        val prepared = prepared(
            groups = listOf(
                InstallerGroup(
                    id = "required-group",
                    name = "Required",
                    type = InstallerGroupType.SELECT_ANY,
                    options = listOf(required)
                ),
                InstallerGroup(
                    id = "choice-group",
                    name = "Choice",
                    type = InstallerGroupType.SELECT_EXACTLY_ONE,
                    options = listOf(first, second)
                )
            )
        )
        val harness = WorkflowHarness(
            engine = FakePendingInstallerEngine(),
            session = PendingInstallerSession(
                prepared = prepared,
                archiveRecordId = "archive-1",
                selectedOptionIds = setOf("required", "first")
            )
        )

        harness.workflow.toggleOption("second")

        assertEquals(setOf("required", "second"), harness.session?.selectedOptionIds)
        assertEquals(listOf(setOf("required", "second")), harness.selectedIdUpdates)
    }

    @Test
    fun cancelCleansPreparedInstallThenClearsState() {
        val engine = FakePendingInstallerEngine()
        val harness = WorkflowHarness(engine = engine, session = session())

        harness.workflow.cancelPendingInstall()

        assertEquals(listOf("Example Archive.zip"), engine.cancelledArchiveNames)
        assertNull(harness.session)
        assertEquals(1, harness.clearCount)
        assertEquals(listOf("Installer cancelled."), harness.statuses)
        assertTrue(
            harness.logs.contains("Cancelled installer session for: Example Archive.zip")
        )
    }

    @Test
    fun cancelFailureStillClearsStateAndUpdatesStatus() {
        val engine = FakePendingInstallerEngine().apply {
            cancelFailure = IllegalStateException("cleanup failed")
        }
        val harness = WorkflowHarness(engine = engine, session = session())

        harness.workflow.cancelPendingInstall()

        assertNull(harness.session)
        assertEquals(1, harness.clearCount)
        assertEquals(listOf("Installer cancelled."), harness.statuses)
        assertEquals(
            "Failed to clean installer session: cleanup failed",
            harness.errors.single().first
        )
    }

    @Test
    fun cancelWithNoEnginePreservesPendingState() {
        val initialSession = session()
        val harness = WorkflowHarness(engine = null, session = initialSession)

        harness.workflow.cancelPendingInstall()

        assertEquals(initialSession, harness.session)
        assertEquals(0, harness.clearCount)
        assertTrue(harness.statuses.isEmpty())
    }

    private class WorkflowHarness(
        engine: FakePendingInstallerEngine?,
        session: PendingInstallerSession?
    ) {
        var session: PendingInstallerSession? = session
        var operationInProgress = false
        var clearCount = 0
        var refreshCount = 0
        val logs = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()
        val statuses = mutableListOf<String>()
        val beginMessages = mutableListOf<String>()
        val finishMessages = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable?>>()
        val selectedIdUpdates = mutableListOf<Set<String>>()

        val workflow = PendingInstallerWorkflow(
            pendingSessionProvider = { session },
            isOperationInProgress = { operationInProgress },
            createEngine = { engine },
            beginOperation = { message -> beginMessages.add(message) },
            finishOperation = { message -> finishMessages.add(message) },
            failOperation = { message, throwable -> failures.add(message to throwable) },
            appendLog = { message -> logs.add(message) },
            appendError = { message, throwable -> errors.add(message to throwable) },
            updateLastOperationStatus = { status -> statuses.add(status) },
            updateSelectedOptionIds = { selectedOptionIds ->
                selectedIdUpdates.add(selectedOptionIds)
                this.session = this.session?.copy(selectedOptionIds = selectedOptionIds)
            },
            clearPendingInstallerState = {
                clearCount++
                this.session = null
            },
            refreshDashboard = { refreshCount++ }
        )
    }

    private class FakePendingInstallerEngine(
        currentMods: List<Mod> = emptyList()
    ) : PendingInstallerEngine {
        private var currentMods = currentMods
        var finalizeCalled = false
        var finalizedSelectedOptionIds: Set<String> = emptySet()
        var finalizedPriority: Int? = null
        var finalizedSourceType: String? = null
        var savedMods: List<Mod> = emptyList()
        var markedArchive: Pair<String, String>? = null
        var syncCount = 0
        val routingSummaryModIds = mutableListOf<String>()
        val cancelledArchiveNames = mutableListOf<String>()
        var cancelFailure: Exception? = null

        override fun getCurrentMods(): List<Mod> = currentMods

        override fun finalizePreparedArchiveInstall(
            prepared: PreparedArchiveInstall,
            selectedOptionIds: Set<String>,
            priority: Int,
            sourceType: String
        ): Mod {
            finalizeCalled = true
            finalizedSelectedOptionIds = selectedOptionIds
            finalizedPriority = priority
            finalizedSourceType = sourceType

            val installed = Mod(
                id = "installed",
                name = "installed",
                installPath = "/mods/installed",
                enabled = true,
                priority = priority,
                modType = ModType.LOOSE
            )
            currentMods = currentMods + installed
            return installed
        }

        override fun saveCurrentMods(mods: List<Mod>) {
            savedMods = mods
            currentMods = mods
        }

        override fun markDownloadedArchiveInstalled(
            archiveId: String,
            installedModId: String
        ) {
            markedArchive = archiveId to installedModId
        }

        override fun cancelPreparedArchiveInstall(prepared: PreparedArchiveInstall) {
            cancelFailure?.let { throw it }
            cancelledArchiveNames.add(prepared.archiveName)
        }

        override fun syncPlugins() {
            syncCount++
        }

        override fun appendInstalledModRoutingSummary(mod: Mod) {
            routingSummaryModIds.add(mod.id)
        }
    }

    private fun session(
        archiveRecordId: String? = "archive-1",
        selectedOptionIds: Set<String> = setOf("core")
    ): PendingInstallerSession {
        return PendingInstallerSession(
            prepared = prepared(),
            archiveRecordId = archiveRecordId,
            selectedOptionIds = selectedOptionIds
        )
    }

    private fun prepared(
        groups: List<InstallerGroup> = emptyList()
    ): PreparedArchiveInstall {
        return PreparedArchiveInstall(
            archivePath = "/archives/example.zip",
            archiveName = "Example Archive.zip",
            modName = "Example Archive",
            sessionRootPath = "/tmp/session",
            extractedRootPath = "/tmp/session/extracted",
            installRootPath = "/tmp/session/install",
            plan = InstallerPlan(
                installerType = InstallerType.FOMOD,
                displayName = "Example Installer",
                rootPath = "",
                groups = groups
            )
        )
    }

    private fun mod(id: String, priority: Int): Mod {
        return Mod(
            id = id,
            name = id,
            installPath = "/mods/$id",
            enabled = true,
            priority = priority,
            modType = ModType.LOOSE
        )
    }
}
