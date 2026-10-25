package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableString
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class OperationHistoryAvailability { AVAILABLE, NOT_SAVED, UNAVAILABLE }

internal data class OperationHistorySnapshot(
    val records: List<OperationAttemptRecord> = emptyList(),
    val availability: OperationHistoryAvailability = OperationHistoryAvailability.AVAILABLE,
    val reason: String? = null
)

/** Non-authoritative history. All load/record calls belong on the admitted background path. */
internal class OperationHistoryRepository(private val metadata: CriticalMetadataFile) {
    constructor(file: File) : this(CriticalMetadataFile(file))

    companion object {
        const val MAX_RECORDS = 50
        const val MAX_BYTES = 256 * 1024
        const val MAX_CONTEXT_CHARS = 2048
        const val MAX_REASON_CHARS = 512
    }

    @Volatile var snapshot = OperationHistorySnapshot()
        private set
    private var loaded = false
    private var blocked = false
    private var persistedText: String? = null

    @Synchronized
    fun load(): OperationHistorySnapshot {
        if (loaded) return snapshot
        loaded = true
        try {
            persistedText = metadata.read(MAX_BYTES)
            val records = persistedText?.let(::decode).orEmpty().map {
                // An unfinished record from another process is not a live worker or known outcome.
                if (it.outcome == null) it.copy(interrupted = true) else it
            }
            snapshot = OperationHistorySnapshot(records)
        } catch (_: Exception) {
            blocked = true
            snapshot = OperationHistorySnapshot(availability = OperationHistoryAvailability.UNAVAILABLE,
                reason = "Operation history unavailable; existing evidence was not replaced.")
        }
        return snapshot
    }

    @Synchronized
    fun record(attempt: OperationAttemptRecord): OperationHistorySnapshot {
        load()
        val record = bounded(attempt)
        val existing = snapshot.records.firstOrNull { it.id == record.id }
        if (existing == record || existing?.outcome != null || existing?.interrupted == true) return snapshot
        if (existing != null && (existing.context != record.context || existing.kind != record.kind ||
                existing.startedAtMillis != record.startedAtMillis || existing.previousAttemptId != record.previousAttemptId ||
                existing.updatedAtMillis > record.updatedAtMillis || existing.phase.ordinal > record.phase.ordinal)) {
            return unsaved("Operation history update refused: inconsistent identity or lifecycle.")
        }
        val candidate = snapshot.records.toMutableList()
        if (existing == null) candidate += record else candidate[candidate.indexOf(existing)] = record
        var text: String
        try {
            text = encode(candidate)
            while (candidate.size > MAX_RECORDS || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                val oldestClosed = candidate.indexOfFirst { it.id != record.id && (it.outcome != null || it.interrupted) }
                if (oldestClosed < 0) {
                    return unsaved("Operation history limit reached; live attempts were retained.")
                }
                candidate.removeAt(oldestClosed)
                text = encode(candidate)
            }
            decode(text) // Validate generated records before any write.
        } catch (_: Exception) {
            return unsaved("Operation history update unavailable; record was not saved.")
        }
        snapshot = snapshot.copy(records = candidate.toList())
        if (blocked) return snapshot
        try {
            val currentText = metadata.read(MAX_BYTES)
            if (currentText != persistedText) {
                blocked = true
                snapshot = snapshot.copy(availability = OperationHistoryAvailability.UNAVAILABLE,
                    reason = "Operation history changed outside this session; existing evidence was not replaced.")
                return snapshot
            }
            metadata.replace(text)
            persistedText = text
            snapshot = OperationHistorySnapshot(candidate.toList())
        } catch (_: Exception) {
            return unsaved("Recent operation changes are not saved; prior history was preserved.")
        }
        return snapshot
    }

    private fun unsaved(reason: String): OperationHistorySnapshot {
        snapshot = snapshot.copy(
            availability = if (blocked) OperationHistoryAvailability.UNAVAILABLE else OperationHistoryAvailability.NOT_SAVED,
            reason = reason
        )
        return snapshot
    }

    private fun bounded(record: OperationAttemptRecord): OperationAttemptRecord {
        val context = record.context
        val oversized = listOfNotNull(context.profileId, context.gameId, context.dataTarget, context.rootTarget)
            .any { it.length > MAX_CONTEXT_CHARS }
        return record.copy(
            context = if (oversized) OperationContext(null, "", context.realDeployment, "", "", unavailable = true) else context,
            reason = record.reason.take(MAX_REASON_CHARS)
        )
    }

    private fun encode(records: List<OperationAttemptRecord>): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("records", JSONArray().apply {
            records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("previousAttemptId", record.previousAttemptId ?: JSONObject.NULL)
                    put("kind", record.kind.name)
                    put("startedAtMillis", record.startedAtMillis)
                    put("updatedAtMillis", record.updatedAtMillis)
                    put("finishedAtMillis", record.finishedAtMillis ?: JSONObject.NULL)
                    put("phase", record.phase.name)
                    put("outcome", record.outcome?.name ?: JSONObject.NULL)
                    put("reason", record.reason)
                    put("interrupted", record.interrupted)
                    put("context", JSONObject().apply {
                        put("profileId", record.context.profileId ?: JSONObject.NULL)
                        put("gameId", record.context.gameId)
                        put("realDeployment", record.context.realDeployment)
                        put("dataTarget", record.context.dataTarget)
                        put("rootTarget", record.context.rootTarget)
                        put("unavailable", record.context.unavailable)
                    })
                })
            }
        })
    }.toString()

    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Invalid $key" }
        return value
    }

    private fun decode(text: String): List<OperationAttemptRecord> {
        // Bound nesting before the platform JSON parser; a small file can still exhaust its stack.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        return CriticalMetadataJson.decode("operation history", text) { value ->
            val root = value as JSONObject
            require(root.requiredInt("schemaVersion") == 1)
            val array = root.getJSONArray("records")
            require(array.length() <= MAX_RECORDS)
            val records = List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                val context = obj.getJSONObject("context")
                val record = OperationAttemptRecord(
                    obj.requiredString("id"), obj.nullableString("previousAttemptId"),
                    OperationKind.valueOf(obj.requiredString("kind")),
                    OperationContext(context.nullableString("profileId"), context.string("gameId"),
                        context.requiredBoolean("realDeployment"), context.string("dataTarget"),
                        context.string("rootTarget"), context.requiredBoolean("unavailable")),
                    obj.requiredLong("startedAtMillis"), obj.requiredLong("updatedAtMillis"),
                    obj.nullableLong("finishedAtMillis"), OperationPhase.valueOf(obj.requiredString("phase")),
                    obj.nullableString("outcome")?.let(OperationOutcome::valueOf), obj.string("reason"),
                    obj.requiredBoolean("interrupted")
                )
                require(UUID.fromString(record.id).toString() == record.id)
                record.previousAttemptId?.let { require(UUID.fromString(it).toString() == it && it != record.id) }
                require(record.updatedAtMillis >= record.startedAtMillis)
                require((record.outcome == null) == (record.finishedAtMillis == null))
                require(record.finishedAtMillis == null || record.finishedAtMillis == record.updatedAtMillis)
                require(!record.interrupted || record.outcome == null)
                require((record.kind == OperationKind.ARCHIVE_INSTALL) == (record.phase != OperationPhase.EXECUTING))
                require(record.outcome != OperationOutcome.SUCCEEDED || record.kind != OperationKind.ARCHIVE_INSTALL ||
                    record.phase == OperationPhase.INSTALLING)
                require(record.reason.length <= MAX_REASON_CHARS)
                require(listOfNotNull(record.context.profileId, record.context.gameId, record.context.dataTarget,
                    record.context.rootTarget).all { it.length <= MAX_CONTEXT_CHARS })
                require(record.context.unavailable || record.context.gameId.isNotBlank())
                record
            }
            require(records.map { it.id }.distinct().size == records.size)
            records
        }
    }
}
