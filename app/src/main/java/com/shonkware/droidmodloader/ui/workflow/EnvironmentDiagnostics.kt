package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidationFinding
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidationResult
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidationSeverity
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidator
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessState

/** Transient observation only. No saved configuration, executable contents or runtime discovery. */
internal data class EnvironmentDiagnostics(
    val observedAtMillis: Long,
    val context: OperationContext,
    val app: AppDiagnosticInfo,
    val allFilesAccess: AllFilesAccessState,
    val targets: List<GameTargetValidationResult>,
    val relationship: List<GameTargetValidationFinding>
) {
    fun toSummary(): String = buildString {
        appendLine("Environment facts (historical observation at $observedAtMillis)")
        appendLine("Scope: profile=${context.profileId ?: "unassigned"}; game=${context.gameId}")
        appendLine("Deployment intent: ${if (context.realDeployment) "real" else "simulated"}")
        appendLine("Paths checked are captured folder intent, not proof of saved configuration or effective deployment locations.")
        appendLine("App: ${app.versionName} (${app.versionCode}); package=${app.packageName}")
        appendLine("Android: ${app.androidVersion}; API=${app.sdkInt ?: "unavailable"}; device=${app.deviceModel}")
        appendLine("All-files access: $allFilesAccess (not proof of access to any particular folder)")
        if (context.unavailable) appendLine("Target checks unavailable: captured context is unavailable.")
        targets.forEach { appendLine(it.toDebugSummary()) }
        relationship.forEach { appendLine("${it.code}: ${it.title} ${it.details}") }
        appendLine("Checks use existing setup marker rules and require readable, writable folders; no writes were attempted.")
        appendLine("At most $MAX_TARGET_ENTRIES top-level entries per target; no recursive scan or executable content reads.")
        appendLine("Marker presence identifies names only, not readable contents, file integrity, game version or a working installation.")
        appendLine("Game/executable version: unavailable (not inspected).")
        appendLine("Launcher/Wine version and configuration: unavailable (not inspected).")
        appendLine("Runtime compatibility: unverified; no game was launched and no network or package discovery was used.")
        appendLine("Inspect again after changing permissions, files, profile or targets; observations are not continuous monitoring.")
    }

    companion object {
        const val MAX_TARGET_ENTRIES = 4096

        fun inspect(
            context: OperationContext,
            app: AppDiagnosticInfo,
            allFilesAccess: AllFilesAccessState,
            validator: GameTargetValidator = GameTargetValidator(),
            observedAtMillis: Long = System.currentTimeMillis()
        ): EnvironmentDiagnostics {
            val targets = if (context.unavailable) emptyList() else listOf(
                GameTargetType.DATA to context.dataTarget,
                GameTargetType.GAME_ROOT to context.rootTarget
            ).map { (type, path) ->
                try {
                    validator.validateTarget(context.gameId, type, path, MAX_TARGET_ENTRIES)
                } catch (_: Exception) {
                    GameTargetValidationResult(context.gameId, type, null, listOf(
                        GameTargetValidationFinding("TARGET_CHECK_UNAVAILABLE", GameTargetValidationSeverity.ERROR,
                            "${type.displayName} check is unavailable.", "Folder access or inspection failed.")
                    ))
                }
            }
            val relationship = if (targets.size == 2) validator.validateRelationship(targets[0], targets[1]) else emptyList()
            return EnvironmentDiagnostics(observedAtMillis, context, app, allFilesAccess, targets, relationship)
        }
    }
}
