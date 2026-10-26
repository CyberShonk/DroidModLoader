package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test
import java.nio.file.Files

class SupportReportCoordinatorTest {

    @Test
    fun `share text contains app state and persistent log`() {
        val root = Files.createTempDirectory("dml-support-report").toFile()
        val writer = SessionLogWriter { root }
        writer.append("persisted line")
        val coordinator = SupportReportCoordinator(
            engineProvider = { FakeEngine() },
            diagnosticInfoProvider = {
                AppDiagnosticInfo("0.6.0-beta", 6, "com.test", "13", "Test Device")
            },
            lastOperationStatusProvider = { "Ready." },
            developerModeEnabledProvider = { true },
            currentLogTextProvider = { "current line" },
            sessionLogWriter = writer
        )

        val text = coordinator.buildShareText()

        assertTrue(text.contains("App Version: 0.6.0-beta (6)"))
        assertTrue(text.contains("Installed Mods: 1"))
        assertTrue(text.contains("Enabled Plugins: 1"))
        assertTrue(text.contains("Developer Mode Enabled: true"))
        assertTrue(text.contains("persisted line"))
    }

    @Test
    fun `file reports preserve complete large snapshots and isolate failed exports`() {
        val root = Files.createTempDirectory("dml-support-export").toFile()
        try {
            val writer = SessionLogWriter { File(root, "external") }
            writer.append("original persistent history")
            var current = "0123456789 Ω🚀\n".repeat(75_000)
            val largeContent = current
            var fail = false
            val coordinator = SupportReportCoordinator(
                engineProvider = { FakeEngine() },
                diagnosticInfoProvider = {
                    check(!fail) { "diagnostic failure" }
                    AppDiagnosticInfo("test", 1, "com.test", "13", "Fixture")
                },
                lastOperationStatusProvider = { "Ready." },
                developerModeEnabledProvider = { false },
                currentLogTextProvider = { current },
                sessionLogWriter = writer
            )
            val cache = File(root, "cache")
            val first = coordinator.buildShareFile(cache)
            val firstContents = first.readText()
            assertEquals(File(cache, "support-reports"), first.parentFile)
            assertTrue(first.length() > 1_104_036)
            assertTrue(firstContents.contains(largeContent))
            assertTrue(firstContents.contains("original persistent history"))

            current = "new snapshot"
            val second = coordinator.buildShareFile(cache)
            assertNotEquals(first, second)
            assertEquals(firstContents, first.readText())
            assertTrue(second.readText().contains("new snapshot"))
            val exports = first.parentFile!!.list()!!.toSet()
            fail = true
            assertThrows(IllegalStateException::class.java) { coordinator.buildShareFile(cache) }
            assertEquals(exports, first.parentFile!!.list()!!.toSet())
            assertEquals(firstContents, first.readText())

            val blockedCache = File(root, "blocked").apply { writeText("preserve me") }
            assertThrows(IllegalStateException::class.java) { coordinator.buildShareFile(blockedCache) }
            assertEquals("preserve me", blockedCache.readText())
            assertTrue(writer.readTextOrEmpty().contains("original persistent history"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `support uses the supplied shared health projection without recomputing it`() {
        val root = Files.createTempDirectory("dml-health-support").toFile()
        try {
            val state = com.shonkware.droidmodloader.ui.MutableMainActivityUiState().apply {
                activeProfileId = "profile-a"
                selectedGameId = "fallout_nv"
            }
            val health = state.currentDeploymentHealth()
            var calls = 0
            val coordinator = SupportReportCoordinator(
                { FakeEngine() }, { AppDiagnosticInfo("test", 1, "com.test", "36", "Fixture") },
                { "Ready" }, { false }, { "log" }, SessionLogWriter { root },
                deploymentHealthProvider = { calls++; health }
            )
            val text = coordinator.buildShareText()
            assertEquals(1, calls)
            assertTrue(text.contains(health.toDebugSummary()))
            assertTrue(text.contains("INTEGRITY_UNVERIFIED"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `support preserves scoped storage observations as historical logs without a new scan`() {
        val root = Files.createTempDirectory("dml-storage-support-").toFile()
        try {
            val storage = "Storage scope: profile=profile-a; game=fallout_nv\n" +
                com.shonkware.droidmodloader.engine.storage.StorageDiagnostics(0L, emptyList(),
                    com.shonkware.droidmodloader.engine.storage.ManagedArchiveInventory(details = listOf("Unavailable fixture")),
                    emptyList()).toSummary()
            val report = SupportReportCoordinator({ FakeEngine() },
                { AppDiagnosticInfo("test", 1, "com.test", "36", "Fixture") }, { "Ready" }, { false },
                { storage }, SessionLogWriter { root }).buildShareText()
            assertTrue(report.contains(storage))
            assertTrue(report.contains("historical observation"))
            assertTrue(report.contains("observed logical size: unknown"))
            assertTrue(report.contains("profile=profile-a; game=fallout_nv"))
        } finally { root.deleteRecursively() }
    }

    private class FakeEngine : SupportReportEngine {
        override fun getCurrentMods(): List<Mod> = listOf(
            Mod("mod", "Mod", "/mods/mod", true, 1, ModType.LOOSE)
        )

        override fun getCurrentPlugins(): List<PluginEntry> = listOf(
            PluginEntry(
                pluginName = "test.esp",
                normalizedPath = "test.esp",
                enabled = true,
                priority = 1,
                sourceModId = "mod",
                sourceModName = "Mod",
                pluginType = "ESP",
                sourceType = "managed_mod",
                locked = false,
                filePresentInDataFolder = true
            )
        )
    }
}
