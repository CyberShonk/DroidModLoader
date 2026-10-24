package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealth
import com.shonkware.droidmodloader.engine.io.SessionLogWriter
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.PluginEntry
import java.io.File

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
    private val sessionLogWriter: SessionLogWriter,
    private val deploymentHealthProvider: () -> DeploymentHealth? = { null }
) {
    fun buildShareFile(cacheDir: File): File {
        val directory = File(cacheDir, "support-reports")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create support report cache." }
        val report = File.createTempFile("dml-support-report-", ".txt", directory)
        try {
            report.writeText(buildShareText())
            return report
        } catch (failure: Throwable) {
            report.delete()
            throw failure
        }
    }

    fun buildShareText(): String {
        val engine = engineProvider()
        val mods = engine?.getCurrentMods().orEmpty()
        val plugins = engine?.getCurrentPlugins().orEmpty()
        val info = diagnosticInfoProvider()
        val health = deploymentHealthProvider()
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
            health?.let { appendLine(it.toDebugSummary()) }
            appendLine("Current Logs:")
            appendLine(currentLogTextProvider())
        }

        sessionLogWriter.append("=== Diagnostic Snapshot ===\n$summary")
        val persistentLog = sessionLogWriter.readTextOrEmpty()
            .ifBlank { "(no persistent log file)" }
        return summary + "\n\n=== Persistent Log File ===\n" + persistentLog
    }
}
