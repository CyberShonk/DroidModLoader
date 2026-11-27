package com.shonkware.droidmodloader.engine.deploy.journal

import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import org.json.JSONObject
import java.io.File
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal enum class TargetStorageMode(val directoryName: String) { PHYSICAL("physical"), SIMULATED("simulated") }

/** Supplied by future qualified admission; validation here does not probe physical aliases. */
internal data class TargetDestinationIdentity(
    val mode: TargetStorageMode,
    val gameId: String,
    val dataDirectory: String,
    val rootDirectory: String
) {
    fun validate() {
        TargetJournalCodec.identifier(gameId)
        listOf(dataDirectory, rootDirectory).forEach(TargetJournalCodec::canonicalPathText)
        require(dataDirectory != rootDirectory)
        if (mode == TargetStorageMode.PHYSICAL) require(File(dataDirectory).parentFile == File(rootDirectory))
        else require(File(dataDirectory).parentFile == File(rootDirectory).parentFile)
    }

    fun directory(role: GameTargetType): String = if (role == GameTargetType.DATA) dataDirectory else rootDirectory
    fun digest(): String {
        // Logical identity must not depend on Android/JVM JSON escaping conventions.
        val framed = listOf(mode.name, gameId, dataDirectory, rootDirectory).joinToString("") {
            "${TargetJournalCodec.bytes(it).size}:$it"
        }
        return TargetJournalCodec.hash(TargetJournalCodec.bytes(framed))
    }
}

internal enum class TargetMetadataKind(val fileName: String, val maximumBytes: Int, val maximumEntries: Int) {
    DATA_MANIFEST("data_manifest.json", 8 * 1024 * 1024, 50_000),
    ROOT_MANIFEST("root_manifest.json", 8 * 1024 * 1024, 50_000),
    DATA_INVENTORY("data_inventory.json", 32 * 1024 * 1024, 200_000)
}

internal data class TargetMetadataPin(val hash: String, val sizeBytes: Long, val entryCount: Int)

internal data class TargetGenerationReference(
    val generationId: String,
    val profileId: String,
    val sourceRevisionDigest: String,
    val dataManifest: TargetMetadataPin,
    val rootManifest: TargetMetadataPin,
    val dataInventory: TargetMetadataPin
) {
    fun pin(kind: TargetMetadataKind): TargetMetadataPin = when (kind) {
        TargetMetadataKind.DATA_MANIFEST -> dataManifest
        TargetMetadataKind.ROOT_MANIFEST -> rootManifest
        TargetMetadataKind.DATA_INVENTORY -> dataInventory
    }
}

internal data class TargetMetadataTexts(val dataManifest: String, val rootManifest: String, val dataInventory: String) {
    fun text(kind: TargetMetadataKind): String = when (kind) {
        TargetMetadataKind.DATA_MANIFEST -> dataManifest
        TargetMetadataKind.ROOT_MANIFEST -> rootManifest
        TargetMetadataKind.DATA_INVENTORY -> dataInventory
    }
}

/** Only metadata operations are implemented in this staged unit. Other kinds stay unsupported. */
internal enum class TargetMetadataOperation { REGISTRATION, PREPARATION }
internal data class TargetPendingMetadata(val operationId: String, val kind: TargetMetadataOperation, val candidate: TargetGenerationReference?)
internal data class TargetJournalEnvelope(
    val destination: TargetDestinationIdentity,
    val revision: Long,
    val completed: TargetGenerationReference?,
    val pending: TargetPendingMetadata?
)

internal enum class TargetAssociationState { MISSING, REGISTRATION_PENDING, BOUND, PREPARATION_PENDING }
internal data class TargetAssociation(val state: TargetAssociationState, val journal: TargetJournalEnvelope?)
/** Verified metadata pins only, never proof that game files or original backups currently match. */
internal data class TargetRecordedMetadata(val reference: TargetGenerationReference)

internal object TargetJournalCodec {
    const val JOURNAL_BYTES = 8 * 1024 * 1024
    const val BINDING_BYTES = 64 * 1024
    private val digestPattern = Regex("[a-f0-9]{64}")

    fun bytes(text: String, maximumBytes: Int = JOURNAL_BYTES): ByteArray {
        require(maximumBytes in 1 until Int.MAX_VALUE && text.length <= maximumBytes) { "Metadata exceeds byte limit" }
        // UTF-8 needs at most three bytes per UTF-16 code unit. Cap the encoder's
        // buffer before encoding, including for multibyte text that exceeds a limit.
        val capacity = minOf(maximumBytes.toLong() + 1, maxOf(1L, text.length.toLong() * 3)).toInt()
        val output = ByteBuffer.allocate(capacity)
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = encoder.encode(CharBuffer.wrap(text), output, true)
        if (result.isError) result.throwException()
        require(!result.isOverflow) { "Metadata exceeds byte limit" }
        val flushed = encoder.flush(output)
        if (flushed.isError) flushed.throwException()
        require(!flushed.isOverflow && output.position() <= maximumBytes) { "Metadata exceeds byte limit" }
        return output.array().copyOf(output.position())
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun identifier(value: String) {
        require(value.isNotBlank() && value.none { it.isISOControl() } && value.length <= 96 && bytes(value).size <= 96) { "Invalid metadata identity" }
    }
    fun canonicalPathText(value: String) {
        require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() || it == '\\' })
        bytes(value)
        val path = File(value).toPath()
        require(path.isAbsolute && path.normalize().toString() == value) { "Destination must be a captured canonical path" }
    }
    fun keys(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "Invalid or unsupported target metadata fields" }
    }
    fun digestText(value: String) { require(digestPattern.matches(value)) { "Invalid metadata digest" } }

    fun destination(json: JSONObject): TargetDestinationIdentity {
        keys(json, setOf("mode", "gameId", "dataDirectory", "rootDirectory"))
        return TargetDestinationIdentity(TargetStorageMode.valueOf(json.requiredString("mode")), json.requiredString("gameId"),
            json.requiredString("dataDirectory"), json.requiredString("rootDirectory")).also { it.validate() }
    }
    fun destination(identity: TargetDestinationIdentity): JSONObject = JSONObject().put("mode", identity.mode.name)
        .put("gameId", identity.gameId).put("dataDirectory", identity.dataDirectory).put("rootDirectory", identity.rootDirectory)

    fun validate(reference: TargetGenerationReference) {
        identifier(reference.generationId); identifier(reference.profileId); digestText(reference.sourceRevisionDigest)
        TargetMetadataKind.entries.forEach { kind ->
            val pin = reference.pin(kind)
            digestText(pin.hash)
            require(pin.sizeBytes in 1..kind.maximumBytes.toLong() && pin.entryCount in 0..kind.maximumEntries)
        }
    }
    fun reference(json: JSONObject): TargetGenerationReference {
        keys(json, setOf("generationId", "profileId", "sourceRevisionDigest", "dataManifest", "rootManifest", "dataInventory"))
        fun pin(key: String): TargetMetadataPin {
            val row = json.getJSONObject(key)
            keys(row, setOf("hash", "sizeBytes", "entryCount"))
            return TargetMetadataPin(row.requiredString("hash"), row.requiredLong("sizeBytes"), row.requiredInt("entryCount"))
        }
        return TargetGenerationReference(json.requiredString("generationId"), json.requiredString("profileId"), json.requiredString("sourceRevisionDigest"),
            pin("dataManifest"), pin("rootManifest"), pin("dataInventory")).also(::validate)
    }
    fun reference(reference: TargetGenerationReference): JSONObject {
        validate(reference)
        fun pin(pin: TargetMetadataPin) = JSONObject().put("hash", pin.hash).put("sizeBytes", pin.sizeBytes).put("entryCount", pin.entryCount)
        return JSONObject().put("generationId", reference.generationId).put("profileId", reference.profileId)
            .put("sourceRevisionDigest", reference.sourceRevisionDigest).put("dataManifest", pin(reference.dataManifest))
            .put("rootManifest", pin(reference.rootManifest)).put("dataInventory", pin(reference.dataInventory))
    }

    fun decode(text: String): TargetJournalEnvelope {
        val json = ModLibraryJsonReader.read(text)
        keys(json, setOf("schemaVersion", "destination", "revision", "completed", "pending"))
        require(json.requiredInt("schemaVersion") == 3) { "Unsupported target journal version" }
        val completed = if (json.isNull("completed")) null else reference(json.getJSONObject("completed"))
        val pending = if (json.isNull("pending")) null else json.getJSONObject("pending").let { row ->
            keys(row, setOf("operationId", "kind", "candidate"))
            TargetPendingMetadata(row.requiredString("operationId"), TargetMetadataOperation.valueOf(row.requiredString("kind")),
                if (row.isNull("candidate")) null else reference(row.getJSONObject("candidate")))
        }
        return TargetJournalEnvelope(destination(json.getJSONObject("destination")), json.requiredLong("revision"), completed, pending).also(::validate)
    }
    fun validate(record: TargetJournalEnvelope) {
        record.destination.validate()
        require(record.revision > 0)
        if (record.pending?.kind != TargetMetadataOperation.REGISTRATION) require(record.revision >= 2)
        record.completed?.let(::validate)
        record.pending?.let { pending ->
            identifier(pending.operationId)
            if (pending.kind == TargetMetadataOperation.REGISTRATION) require(record.completed == null && pending.candidate == null && record.revision == 1L)
            else {
                validate(checkNotNull(pending.candidate))
                require(pending.candidate.generationId != record.completed?.generationId) { "Candidate replaces completed metadata in place" }
            }
        }
    }
    fun encode(record: TargetJournalEnvelope): String {
        validate(record)
        val pending = record.pending?.let { JSONObject().put("operationId", it.operationId).put("kind", it.kind.name)
            .put("candidate", it.candidate?.let(::reference) ?: JSONObject.NULL) }
        return JSONObject().put("schemaVersion", 3).put("destination", destination(record.destination)).put("revision", record.revision)
            .put("completed", record.completed?.let(::reference) ?: JSONObject.NULL).put("pending", pending ?: JSONObject.NULL).toString()
    }
}
