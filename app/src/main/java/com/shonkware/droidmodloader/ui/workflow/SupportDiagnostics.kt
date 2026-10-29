package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.storage.AllFilesAccessState
import com.shonkware.droidmodloader.ui.archive.ArchiveInspectionReport
import org.json.JSONObject

/** One previous explicit inspection, not cached authority or a reason to repeat its I/O. */
internal data class SupportStorageObservation(
    val context: OperationContext,
    val observedAtMillis: Long,
    val summary: String
)

/** Immutable session facts captured together; no scanner, filesystem probe or persistence here. */
internal data class SupportDiagnostics(
    val context: OperationContext? = null,
    val profileName: String? = null,
    val history: OperationHistorySnapshot? = null,
    val environment: EnvironmentDiagnostics? = null,
    val storage: SupportStorageObservation? = null,
    val archive: ArchiveInspectionReport? = null,
    val allFilesAccess: AllFilesAccessState? = null,
    val buildProvenance: Map<String, String> = emptyMap()
) {
    fun toSummary(): String = buildString {
        appendLine("Profile: ${context?.profileId ?: "unavailable"}; name: ${profileName ?: "unavailable"}")
        appendLine("Game: ${context?.gameId ?: "unavailable"}")
        appendLine("Deployment intent: ${context?.let { if (it.realDeployment) "physical" else "simulated" } ?: "unavailable"}")
        appendLine("Data folder intent: ${context?.dataTarget ?: "unavailable"}")
        appendLine("Game Root folder intent: ${context?.rootTarget ?: "unavailable"}")
        appendLine("Folder intent is not validated target identity or evidence that files match.")
        appendLine("All-files access: ${allFilesAccess ?: "unavailable"}; not proof of access to a particular folder.")
        appendLine()
        appendLine("Build provenance (embedded build record, not installed APK verification):")
        if (buildProvenance.isEmpty()) appendLine("Unavailable")
        // Allowlist again at the output boundary; never export arbitrary asset/config fields.
        BUILD_FIELDS.forEach { key -> buildProvenance[key]?.let { appendLine("$key: $it") } }
        appendLine()
        appendLine("Recent operations (historical; each keeps its own captured context):")
        appendLine("History: ${history?.availability ?: "unavailable"}")
        history?.reason?.let { appendLine(it) }
        val records = history?.records.orEmpty()
        appendLine("Showing ${minOf(records.size, 10)} of ${records.size} retained attempts.")
        records.takeLast(10).reversed().forEach { record ->
            val outcome = if (record.interrupted) "Interrupted; outcome unknown" else
                record.outcome?.name ?: "Unfinished; outcome unknown"
            appendLine("${record.kind} [${record.id}]: $outcome; phase=${record.phase}; updated=${record.updatedAtMillis}")
            appendLine("  profile=${record.context.profileId ?: "unassigned"}; game=${record.context.gameId}; context unavailable=${record.context.unavailable}")
            appendLine("  physical=${record.context.realDeployment}; Data=${record.context.dataTarget}; Game Root=${record.context.rootTarget}")
            appendLine("  ${record.reason}")
        }
        appendLine()
        appendLine("Environment observation:")
        when {
            environment == null -> appendLine("Unavailable; no retained game setup check in this session. Sharing did not run one.")
            environment.context != context || environment.allFilesAccess != allFilesAccess ->
                appendLine("Stale context or permission; previous game setup details omitted. Sharing did not run another check.")
            else -> appendLine(environment.toSummary())
        }
        appendLine()
        appendLine("Storage observation:")
        when {
            storage == null -> appendLine("Unavailable; capacity and required space are unknown. Sharing did not measure storage.")
            storage.context != context -> appendLine("Stale context; previous storage details omitted. Sharing did not measure storage.")
            else -> {
                appendLine("Historical observation at ${storage.observedAtMillis}; not a current space guarantee.")
                appendLine(storage.summary)
            }
        }
        appendLine()
        appendLine("Archive preparation observation:")
        val attempt = archive?.attemptId?.let { id -> records.firstOrNull { it.id == id } }
        when {
            archive == null -> appendLine("Unavailable; no retained archive observation. See historical operation reasons and logs.")
            attempt == null || attempt.context != context ->
                appendLine("Context unavailable or stale; archive details omitted. See historical operation reasons and logs.")
            else -> {
                appendLine("Historical observation at ${archive.observedAtMillis}; attempt=${archive.attemptId}")
                appendLine("Archive: ${archive.archiveName}; preparation=${archive.preparationStatus}")
                appendLine("Failure code: ${archive.failureCode ?: "none recorded"}")
                archive.message?.let { appendLine(it) }
                archive.inspection?.let { inspection ->
                    val facts = inspection.extraction
                    appendLine("Format: ${facts.format}; extension mismatch=${facts.extensionMismatch}; installer=${inspection.installerType ?: "unrecognized"}")
                    appendLine("Observed archive bytes=${facts.archiveBytes}; expanded bytes=${facts.expandedBytes}; entries=${facts.acceptedEntries}; completed files=${facts.completedFiles}")
                    appendLine("Variant: encrypted=${facts.variant.encrypted ?: "unknown"}; multipart=${facts.variant.multipart ?: "unknown"}; solid=${facts.variant.solid ?: "unknown"}; methods=${facts.variant.compressionMethods ?: "unknown"}")
                    inspection.layoutIssue?.let { appendLine(it) }
                    facts.storage?.let { storage ->
                        appendLine("Extraction storage sample at ${storage.observedAtMillis}: ${storage.locationPath}; available bytes=${storage.availableBytes ?: "unknown"}; minimum headroom=${storage.minimumFreeSpaceBytes}")
                        appendLine("Historical extraction sample only, not remaining capacity or an installation guarantee.")
                    }
                }
                appendLine("Preparation is not installation success or proof of game compatibility.")
            }
        }
        appendLine("Observations are not continuous monitoring. Logs can include older profiles and folders.")
    }

    companion object {
        private val BUILD_FIELDS = listOf("schemaVersion", "commit", "dirty", "trackedDiffSha256",
            "untrackedFilesSha256", "applicationId", "versionName", "versionCode", "variant", "builtAtUtc")

        fun readBuildProvenance(text: String): Map<String, String> = try {
            val json = JSONObject(text)
            BUILD_FIELDS.filter { json.has(it) && !json.isNull(it) }.associateWith { json.get(it).toString() }
        } catch (_: Exception) { emptyMap() }
    }
}
