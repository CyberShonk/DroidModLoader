package com.shonkware.droidmodloader.engine.plugins

import java.util.Collections
import java.util.Locale

/** Request-local facts, never persisted plugin policy or proof about the rest of the file. */
enum class PluginFactFailure {
    UNSUPPORTED_GAME, UNSUPPORTED_HEADER, COMPRESSED_HEADER, MALFORMED_HEADER,
    MALFORMED_MASTER, DUPLICATE_MASTER, SELF_MASTER, OPENING_LIMIT, SUBRECORD_LIMIT,
    MASTER_LIMIT, NAME_LIMIT, CANDIDATE_LIMIT, BYTE_LIMIT, REFERENCE_LIMIT,
    DATA_ENTRY_LIMIT, CANCELED, UNSAFE_SOURCE, SOURCE_CHANGED, IO_ERROR,
    CAPTURE_UNAVAILABLE, AMBIGUOUS_IDENTITY
}

enum class PluginSourceScope { MANAGED_CANDIDATE, PHYSICAL_DATA }

data class PluginHeaderSource(
    val profileId: String,
    val snapshotId: String,
    val scope: PluginSourceScope,
    val ownedRootPath: String,
    val sourceFilePath: String,
    val providerId: String? = null
)

data class PluginRecordHeader(
    val flags: Long,
    val formId: Long,
    val revision: Long,
    val formVersion: Int?,
    val unknown: Int?
) {
    val masterFlag: Boolean get() = flags and 1L != 0L
    val lightFlag: Boolean get() = flags and 0x200L != 0L
}

data class PluginHedr(val version: Float, val recordCount: Long, val nextObjectId: Long)

data class PluginHeaderObservation(
    val gameId: String,
    val source: PluginHeaderSource,
    val extension: String,
    val recordHeader: PluginRecordHeader? = null,
    val hedr: PluginHedr? = null,
    /** Null means unproven, never an inferred empty declaration list. */
    val declaredMasters: List<String>? = null,
    val openingByteCount: Int? = null,
    val openingSha256: String? = null,
    val failure: PluginFactFailure? = null
) {
    val complete: Boolean get() = failure == null && declaredMasters != null

    /** Classification only; neither type nor suffix establishes an ordering constraint. */
    val isMaster: Boolean? get() = recordHeader?.let {
        it.masterFlag || (gameId in setOf("skyrim_se", "fallout_4") && extension in setOf("esm", "esl"))
    }
}

/** Share one instance across all sources and retries of a single, serial request. */
class PluginHeaderRequest(val isCanceled: () -> Boolean = { false }) {
    var candidatesRead: Int = 0
        private set
    var headerBytesRead: Long = 0
        private set
    var referencesRead: Int = 0
        private set
    var dataEntriesProcessed: Int = 0
        private set

    internal fun checkCancellation() {
        if (isCanceled()) throw PluginFactException(PluginFactFailure.CANCELED)
    }

    internal fun takeCandidate() {
        checkCancellation()
        if (candidatesRead == MAX_CANDIDATES) throw PluginFactException(PluginFactFailure.CANDIDATE_LIMIT)
        candidatesRead++
    }

    internal fun permitBytes(count: Int) {
        checkCancellation()
        if (count > MAX_HEADER_BYTES - headerBytesRead) throw PluginFactException(PluginFactFailure.BYTE_LIMIT)
    }

    internal fun recordBytes(count: Int) { headerBytesRead += count }

    internal fun takeReference() {
        checkCancellation()
        if (referencesRead == MAX_REFERENCES) throw PluginFactException(PluginFactFailure.REFERENCE_LIMIT)
        referencesRead++
    }

    internal fun takeDataEntry() {
        checkCancellation()
        if (dataEntriesProcessed == MAX_DATA_ENTRIES) throw PluginFactException(PluginFactFailure.DATA_ENTRY_LIMIT)
        dataEntriesProcessed++
    }

    companion object {
        const val MAX_OPENING_BYTES = 4 * 1024 * 1024
        const val MAX_SUBRECORDS = 65536
        const val MAX_MASTERS = 4096
        const val MAX_NAME_BYTES = 256
        const val MAX_CANDIDATES = 8192
        const val MAX_HEADER_BYTES = 32L * 1024 * 1024
        const val MAX_REFERENCES = 65536
        const val MAX_DATA_ENTRIES = 8192
    }
}

internal class PluginFactException(val failure: PluginFactFailure) : Exception()

internal fun pluginIdentity(name: String): String = name.lowercase(Locale.ROOT)
internal fun <T> immutablePluginFacts(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())
