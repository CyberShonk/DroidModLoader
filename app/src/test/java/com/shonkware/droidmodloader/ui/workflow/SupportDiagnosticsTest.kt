package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessState
import com.shonkware.droidmodloader.ui.MutableMainActivityUiState
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SupportDiagnosticsTest {
    private val context = OperationContext("profile-a", "fallout_nv", true, "/People/Alice/Game/Data", "/People/Alice/Game")
    private val attempt = OperationAttempt.start(context, "Preparing", clock = { 100 }) { _, _, _ -> }.apply {
        fail("Archive is encrypted; cleanup failed for /People/Alice/staging")
    }.snapshot()

    @Test fun `export captures structured facts once preserves paths and never rewrites logs`() {
        val root = Files.createTempDirectory("dml-support-facts").toFile()
        try {
            val writer = SessionLogWriter { root }
            writer.append("Original failure; not success")
            val before = writer.readTextOrEmpty()
            var snapshots = 0
            val diagnostics = SupportDiagnostics(context, "Example", OperationHistorySnapshot(listOf(attempt)),
                storage = SupportStorageObservation(context, 123, "Available capacity unknown; required bytes unknown"),
                archive = ArchiveInspectionReport("encrypted.rar", 124, failureCode = "PASSWORD_PROTECTED_OR_ENCRYPTED",
                    message = "Password protected archives are not supported", attemptId = attempt.id))
            val coordinator = coordinator(writer) { snapshots++; diagnostics }
            val report = coordinator.buildShareText()
            assertEquals(1, snapshots)
            assertEquals(before, writer.readTextOrEmpty())
            assertTrue(report.contains("Installed Mods: unavailable"))
            assertTrue(report.contains("/People/Alice/Game/Data"))
            assertTrue(report.contains("Historical observation at 123"))
            assertTrue(report.contains("Available capacity unknown"))
            assertTrue(report.contains("PASSWORD_PROTECTED_OR_ENCRYPTED"))
            assertTrue(report.contains("cleanup failed for /People/Alice/staging"))
            assertTrue(report.contains("Original failure; not success"))
            assertTrue(report.indexOf("=== Technical details ===") > report.indexOf("Deployment:"))
            assertTrue(report.indexOf("Current Logs:") > report.indexOf("=== Technical details ==="))
            // The report provider exposes facts only, no check/extraction/probe callback to invoke.
            assertEquals(report, coordinator.buildShareText())
            assertEquals(2, snapshots)
        } finally { root.deleteRecursively() }
    }

    @Test fun `foreign observations are omitted rather than relabeled as current facts`() {
        val other = context.copy(profileId = "profile-b", dataTarget = "/Other/Data")
        val environment = EnvironmentDiagnostics(1, other,
            AppDiagnosticInfo("fixture", 1, "fixture", "16", "fixture", 36),
            AllFilesAccessState.DENIED, emptyList(), emptyList())
        val report = SupportDiagnostics(context, history = OperationHistorySnapshot(listOf(attempt.copy(context = other))),
            environment = environment, storage = SupportStorageObservation(other, 1, "FOREIGN STORAGE"),
            archive = ArchiveInspectionReport("FOREIGN ARCHIVE", 1, attemptId = attempt.id),
            allFilesAccess = AllFilesAccessState.DENIED).toSummary()
        assertTrue(report.contains("Stale context or permission"))
        assertTrue(report.contains("Stale context; previous storage details omitted"))
        assertTrue(report.contains("Context unavailable or stale; archive details omitted"))
        assertFalse(report.contains("FOREIGN STORAGE"))
        assertFalse(report.contains("FOREIGN ARCHIVE"))
        assertTrue(report.contains("profile=profile-b")) // history remains honestly scoped
    }

    @Test fun `same context environment is historical and changed permission invalidates it`() {
        val environment = EnvironmentDiagnostics(123, context,
            AppDiagnosticInfo("fixture", 1, "fixture", "16", "fixture", 36),
            AllFilesAccessState.DENIED, emptyList(), emptyList())
        val facts = SupportDiagnostics(context, environment = environment, allFilesAccess = AllFilesAccessState.DENIED)
        assertTrue(facts.toSummary().contains("historical observation at 123"))
        assertTrue(facts.toSummary().contains("Runtime compatibility: unverified"))
        assertTrue(facts.copy(allFilesAccess = null).toSummary().contains("previous game setup details omitted"))
    }

    @Test fun `unavailable corrupt history and interrupted attempts never become empty success`() {
        val corrupt = SupportDiagnostics(history = OperationHistorySnapshot(
            availability = OperationHistoryAvailability.UNAVAILABLE, reason = "Corrupt fixture; preserved"))
        assertTrue(corrupt.toSummary().contains("History: UNAVAILABLE"))
        assertTrue(corrupt.toSummary().contains("Corrupt fixture; preserved"))
        val interrupted = attempt.copy(outcome = null, finishedAtMillis = null, interrupted = true)
        val history = OperationHistorySnapshot(List(12) { interrupted.copy(id = "attempt-$it") },
            OperationHistoryAvailability.NOT_SAVED, "Could not save")
        val report = SupportDiagnostics(context, history = history).toSummary()
        assertTrue(report.contains("Showing 10 of 12 retained attempts"))
        assertTrue(report.contains("Interrupted; outcome unknown"))
        assertTrue(report.contains("History: NOT_SAVED"))
        assertFalse(report.contains("[attempt-0]"))
        assertFalse(report.contains("SUCCEEDED"))
    }

    @Test fun `missing profile engine history capacity and observations stay unavailable`() {
        val root = Files.createTempDirectory("dml-empty-support").toFile()
        try {
            val report = coordinator(SessionLogWriter { root }) { SupportDiagnostics() }.buildShareText()
            for (text in listOf("Profile: unavailable", "Game: unavailable", "Mod/plugin snapshot: unavailable",
                "Installed Mods: unavailable", "History: unavailable", "capacity and required space are unknown",
                "Deployment details unavailable", "no retained game setup check", "no retained archive observation")) {
                assertTrue(text, report.contains(text))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `foreign health is not presented as selected profile health`() {
        val root = Files.createTempDirectory("dml-foreign-support").toFile()
        try {
            val health = MutableMainActivityUiState().apply {
                activeProfileId = "profile-b"
                selectedGameId = "fallout_nv"
            }.currentDeploymentHealth()
            val report = SupportReportCoordinator({ null },
                { AppDiagnosticInfo("test", 1, "fixture", "16", "fixture") }, { "Ready" }, { false },
                { "historical log" }, SessionLogWriter { root }, { health }, { SupportDiagnostics(context) }).buildShareText()
            assertTrue(report.contains("Deployment: unavailable"))
            assertTrue(report.contains("stale profile/game context"))
            assertFalse(report.contains(health.toDebugSummary()))
        } finally { root.deleteRecursively() }
    }

    @Test fun `embedded provenance uses only allowed fields and handles corrupt input`() {
        val provenance = SupportDiagnostics.readBuildProvenance("""{"commit":"abc","dirty":true,"variant":"debug","apiKey":"do-not-share","privateContext":"do-not-share"}""")
        assertEquals(mapOf("commit" to "abc", "dirty" to "true", "variant" to "debug"), provenance)
        val text = SupportDiagnostics(buildProvenance = provenance + ("password" to "do-not-share")).toSummary()
        assertTrue(text.contains("commit: abc"))
        assertFalse(text.contains("do-not-share"))
        assertEquals(emptyMap<String, String>(), SupportDiagnostics.readBuildProvenance("{broken"))
    }

    @Test fun `mod and plugin snapshots copy displayed lists without an engine or discovery`() {
        val mods = mutableListOf<com.shonkware.droidmodloader.engine.model.Mod>()
        val plugins = mutableListOf<com.shonkware.droidmodloader.engine.model.PluginEntry>()
        val snapshot = SupportReportEngineSnapshot(mods, plugins)
        mods += com.shonkware.droidmodloader.engine.model.Mod("id", "Mod", "/mod", true, 1,
            com.shonkware.droidmodloader.engine.model.ModType.LOOSE)
        assertTrue(snapshot.getCurrentMods().isEmpty())
        assertTrue(snapshot.getCurrentPlugins().isEmpty())
    }

    @Test fun `simultaneous recovery and partial integrity facts stay visible together`() {
        val root = Files.createTempDirectory("dml-partial-support").toFile()
        try {
            val health = MutableMainActivityUiState().apply {
                activeProfileId = context.profileId
                selectedGameId = context.gameId
            }.currentDeploymentHealth().copy(
                phase = com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthPhase.RECOVERY_ATTENTION,
                reasons = listOf(
                    com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthReason.INSTALL_RECOVERY_REQUIRED,
                    com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthReason.UNFINISHED_JOURNAL,
                    com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthReason.INTEGRITY_PARTIAL),
                recovery = com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryFact.UNFINISHED,
                installRecoveryRequired = true,
                integrity = com.shonkware.droidmodloader.engine.deploy.health.DeploymentIntegrityEvidence(
                    com.shonkware.droidmodloader.engine.deploy.health.DeploymentIntegrityFact.PARTIAL,
                    checked = 1, unreadable = 2))
            var healthReads = 0
            val report = SupportReportCoordinator({ null },
                { AppDiagnosticInfo("test", 1, "fixture", "16", "fixture") }, { "Failed" }, { false },
                { "log" }, SessionLogWriter { root }, { healthReads++; health },
                { SupportDiagnostics(context) }).buildShareText()
            assertEquals(1, healthReads)
            for (text in listOf("Unfinished changes need review", "UNFINISHED_JOURNAL", "INSTALL_RECOVERY_REQUIRED",
                "INTEGRITY_PARTIAL", "checked=1", "unreadable=2", "Retry / rollback unavailable")) {
                assertTrue(text, report.contains(text))
            }
        } finally { root.deleteRecursively() }
    }

    private fun coordinator(writer: SessionLogWriter, facts: () -> SupportDiagnostics) = SupportReportCoordinator(
        { null }, { AppDiagnosticInfo("test", 1, "fixture", "16", "fixture", 36) },
        { "Archive failed" }, { false }, { "Current failure log" }, writer, diagnosticsProvider = facts)
}
