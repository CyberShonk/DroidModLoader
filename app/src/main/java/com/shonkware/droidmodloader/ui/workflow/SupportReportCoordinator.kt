package com.shonkware.droidmodloader.ui.workflow

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
    val deviceModel: String,
    val sdkInt: Int? = null
)

internal interface SupportReportEngine {
    fun getCurrentMods(): List<Mod>
    fun getCurrentPlugins(): List<PluginEntry>
}

/** Displayed session lists only. Engine getters can rediscover folders when saved lists are empty. */
internal class SupportReportEngineSnapshot(mods: List<Mod>, plugins: List<PluginEntry>) : SupportReportEngine {
    private val mods = mods.toList()
    private val plugins = plugins.toList()
    override fun getCurrentMods(): List<Mod> = mods
    override fun getCurrentPlugins(): List<PluginEntry> = plugins
}

internal class SupportReportCoordinator(
    private val engineProvider: () -> SupportReportEngine?,
    private val diagnosticInfoProvider: () -> AppDiagnosticInfo,
    private val lastOperationStatusProvider: () -> String,
    private val developerModeEnabledProvider: () -> Boolean,
    private val currentLogTextProvider: () -> String,
    private val sessionLogWriter: SessionLogWriter,
    private val deploymentHealthProvider: () -> DeploymentHealth? = { null },
    private val diagnosticsProvider: () -> SupportDiagnostics = { SupportDiagnostics() }
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
        val diagnostics = diagnosticsProvider()
        val engine = runCatching { engineProvider() }.getOrNull()
        val mods = runCatching { engine?.getCurrentMods() }.getOrNull()
        val plugins = runCatching { engine?.getCurrentPlugins() }.getOrNull()
        val info = diagnosticInfoProvider()
        val suppliedHealth = deploymentHealthProvider()
        val health = suppliedHealth?.takeIf { diagnostics.context == null ||
            it.isFor(diagnostics.context.profileId, diagnostics.context.gameId) }
        val summary = buildString {
            appendLine("=== Droid Mod Loader Diagnostic Summary ===")
            appendLine("Reports include full folder paths and historical logs. Only share with someone you trust.")
            appendLine("Profile: ${diagnostics.profileName ?: diagnostics.context?.profileId ?: "unavailable"}; game: ${diagnostics.context?.gameId ?: "unavailable"}")
            appendLine("Deployment: ${health?.phase?.summary ?: "unavailable"}")
            appendLine("File check: ${health?.integrity?.fact ?: "unavailable"}")
            appendLine("Recovery: ${health?.recovery ?: "unavailable"}; install recovery attention: ${health?.installRecoveryRequired ?: "unknown"}")
            appendLine("Sharing reuses recorded facts. No new file check, archive read or capacity measurement was run.")
            appendLine()
            appendLine("=== Technical details ===")
            appendLine("App Version: ${info.versionName} (${info.versionCode})")
            appendLine("Display Version: ${info.versionName}")
            appendLine("Package: ${info.packageName}")
            appendLine("Android Version: ${info.androidVersion}")
            appendLine("Android API: ${info.sdkInt ?: "unavailable"}")
            appendLine("Device: ${info.deviceModel}")
            appendLine()
            appendLine("Mod/plugin snapshot: ${if (engine == null) "unavailable" else "available"}")
            appendLine("Mod/plugin counts use the displayed session snapshot, not a new folder scan.")
            appendLine("Installed Mods: ${mods?.size ?: "unavailable"}")
            appendLine("Enabled Mods: ${mods?.count { it.enabled } ?: "unavailable"}")
            appendLine("Plugins: ${plugins?.size ?: "unavailable"}")
            appendLine("Enabled Plugins: ${plugins?.count { it.enabled } ?: "unavailable"}")
            appendLine("Last Operation Status: ${lastOperationStatusProvider()}")
            appendLine()
            appendLine("Developer Mode Enabled: ${developerModeEnabledProvider()}")
            appendLine()
            appendLine(diagnostics.toSummary())
            appendLine(health?.toDebugSummary() ?: if (suppliedHealth == null)
                "Deployment details unavailable; sharing did not refresh them." else
                "Deployment details have stale profile/game context and were omitted.")
            appendLine("Current Logs:")
            appendLine(currentLogTextProvider())
        }

        val persistentLog = sessionLogWriter.readTextOrEmpty()
            .ifBlank { "(no persistent log file)" }
        return summary + "\n\n=== Persistent Log File ===\n" + persistentLog
    }
}
