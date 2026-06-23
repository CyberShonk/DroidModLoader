package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry

internal data class AppDiagnosticInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val androidVersion: String,
    val deviceModel: String
)

internal interface SupportReportEngine {
    fun getCurrentMods(): List<Mod>
    fun getCurrentPlugins(): List<PluginEntry>
}

internal class SupportReportEngineAdapter(
    private val engine: ModEngine
) : SupportReportEngine {
    override fun getCurrentMods(): List<Mod> = engine.getCurrentMods()
    override fun getCurrentPlugins(): List<PluginEntry> = engine.getCurrentPlugins()
}

internal class SupportReportCoordinator(
    private val engineProvider: () -> SupportReportEngine?,
    private val diagnosticInfoProvider: () -> AppDiagnosticInfo,
    private val lastOperationStatusProvider: () -> String,
    private val developerModeEnabledProvider: () -> Boolean,
    private val currentLogTextProvider: () -> String,
    private val sessionLogWriter: SessionLogWriter
) {
    fun buildShareText(): String {
        val engine = engineProvider()
        val mods = engine?.getCurrentMods().orEmpty()
        val plugins = engine?.getCurrentPlugins().orEmpty()
        val info = diagnosticInfoProvider()
        val summary = buildString {
            appendLine("=== Droid Mod Loader Diagnostic Summary ===")
            appendLine()
            appendLine("App Version: ${info.versionName} (${info.versionCode})")
            appendLine("Display Version: ${info.versionName}")
            appendLine("Package: ${info.packageName}")
            appendLine("Android Version: ${info.androidVersion}")
            appendLine("Device: ${info.deviceModel}")
            appendLine()
            appendLine("Installed Mods: ${mods.size}")
            appendLine("Enabled Mods: ${mods.count { it.enabled }}")
            appendLine("Plugins: ${plugins.size}")
            appendLine("Enabled Plugins: ${plugins.count { it.enabled }}")
            appendLine("Last Operation Status: ${lastOperationStatusProvider()}")
            appendLine()
            appendLine("Developer Mode Enabled: ${developerModeEnabledProvider()}")
            appendLine()
            appendLine("Current Logs:")
            appendLine(currentLogTextProvider())
        }

        sessionLogWriter.append("=== Diagnostic Snapshot ===\n$summary")
        val persistentLog = sessionLogWriter.readTextOrEmpty()
            .ifBlank { "(no persistent log file)" }
        return summary + "\n\n=== Persistent Log File ===\n" + persistentLog
    }
}
