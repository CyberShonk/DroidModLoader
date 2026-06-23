package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.junit.Assert.assertTrue
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
