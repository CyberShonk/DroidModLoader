package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import com.shonkware.droidmodloader.engine.model.GameLibraryEntry
import com.shonkware.droidmodloader.engine.model.GameModLibrary
import com.shonkware.droidmodloader.engine.model.InstalledModRecord
import com.shonkware.droidmodloader.engine.model.ProfileLibraryReference
import com.shonkware.droidmodloader.engine.model.ProfileModChoice
import com.shonkware.droidmodloader.engine.model.ProfileModMembership
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal enum class ModLibraryRecordFailure { INVALID, UNREADABLE, UNSUPPORTED }

internal class ModLibraryRecordException(
    val failure: ModLibraryRecordFailure,
    cause: Exception
) : IOException("Mod library record is ${failure.name.lowercase()}; preserve existing evidence", cause)

/**
 * Staged catalogue and membership records. No discovery, payload mutation, migration,
 * active-reader switch or automatic initialization. null means absent; a present empty
 * list is authoritative. Other states throw and cannot be overwritten through save.
 * Callers supply captured scopes, serialize writes and prepare persisted parent dirs.
 * I/O is synchronous; callers must keep it off the UI thread.
 * Catalogue metadata is authoritative for this staged layout; later publication must
 * bind it to matching payload metadata, not leave two independent live writers.
 */
internal class GameModLibraryRepository(
    private val storagePaths: ProfileStoragePaths,
    private val gameId: String,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations
) {
    companion object {
        const val MAX_RECORD_BYTES = 8 * 1024 * 1024
        private val LIBRARY_KEYS = setOf("schemaVersion", "gameId", "revision", "entries")
        private val MEMBERSHIP_KEYS = setOf("schemaVersion", "gameId", "profileId", "revision", "choices")
        private val ENTRY_KEYS = setOf("installationId", "payloadRevision", "installedMetadata")
        private val CHOICE_KEYS = setOf("installationId", "enabled", "priority")
        private val METADATA_KEYS = setOf(
            "modId", "displayName", "installPath", "sourceType", "sourceArchiveName", "installedAtEpochMillis",
            "nexusGameDomain", "nexusModId", "nexusFileId", "nexusFileName", "version", "sourceUrl"
        )
    }

    fun loadLibrary(): GameModLibrary? =
        readRecord { storagePaths.getGameLibraryCatalogueFile(gameId) }?.let(::decodeLibrary)

    fun loadProfile(profileId: String): ProfileModMembership? =
        readRecord { storagePaths.getProfileMembershipFile(gameId, profileId) }?.let { decodeProfile(profileId, it) }

    fun saveLibrary(library: GameModLibrary, expectedRevision: Long?) {
        val text = encodeLibrary(library)
        val validated = decodeLibrary(text)
        checkRevision(loadLibrary()?.revision, expectedRevision, validated.revision)
        recordFile(storagePaths.getGameLibraryCatalogueFile(gameId)).replaceDurably(text, MAX_RECORD_BYTES)
    }

    fun saveProfile(profileId: String, membership: ProfileModMembership, expectedRevision: Long?) {
        val text = encodeProfile(membership)
        val validated = decodeProfile(profileId, text)
        checkRevision(loadProfile(profileId)?.revision, expectedRevision, validated.revision)
        recordFile(storagePaths.getProfileMembershipFile(gameId, profileId)).replaceDurably(text, MAX_RECORD_BYTES)
    }

    /** Explicit complete inventory supplied under session admission; never folder discovery. */
    internal fun loadProfileScope(profileIds: List<String>, requireSavedMembership: Boolean): Map<String, ProfileModMembership?> {
        check(profileIds.size == profileIds.distinct().size) { "Profile scope has duplicate identities" }
        return profileIds.associateWith { profileId ->
            loadProfile(profileId).also {
                check(!requireSavedMembership || it != null) { "A profile's saved mod choices are missing; mutation stopped" }
            }
        }
    }

    internal fun validateProfileMembership(membership: ProfileModMembership) {
        decodeProfile(membership.profileId, encodeProfile(membership))
    }

    /** Join identities only. Missing entries stay missing, even with matching names. */
    fun joinProfileRecords(
        profileId: String,
        library: GameModLibrary,
        membership: ProfileModMembership
    ): List<ProfileLibraryReference> {
        val entries = decodeLibrary(encodeLibrary(library)).entries.associateBy { it.installationId }
        val choices = decodeProfile(profileId, encodeProfile(membership)).choices
        return choices.sortedBy { it.priority }.map { choice ->
            val entry = entries[choice.installationId]
            ProfileLibraryReference(
                choice,
                entry,
                entry?.let { storagePaths.getGameLibraryEntryDir(gameId, it.installationId) }
            )
        }
    }

    private fun recordFile(file: File) = CriticalMetadataFile(file, operations)

    private fun readRecord(path: () -> File): String? = try {
        recordFile(path()).read(MAX_RECORD_BYTES)
    } catch (exception: Exception) {
        throw ModLibraryRecordException(ModLibraryRecordFailure.UNREADABLE, exception)
    }

    private fun checkRevision(current: Long?, expected: Long?, replacement: Long) {
        if (current != expected || expected == Long.MAX_VALUE || replacement != (expected ?: 0L) + 1L) {
            throw IOException("Record revision changed or replacement revision is invalid")
        }
    }

    internal fun decodeLibrary(text: String): GameModLibrary = decode(text, LIBRARY_KEYS) { json ->
        require(json.requiredString("gameId") == gameId) { "Wrong game" }
        val revision = json.requiredLong("revision").also { require(it > 0) }
        val array = json.getJSONArray("entries")
        val entries = List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            requireKeys(entry, ENTRY_KEYS)
            val installationId = entry.requiredString("installationId")
            val expectedPath = storagePaths.getGameLibraryEntryDir(gameId, installationId).absolutePath
            val payloadRevision = entry.requiredLong("payloadRevision").also { require(it > 0) }
            val metadata = decodeMetadata(entry.getJSONObject("installedMetadata"))
            require(metadata.modId == installationId && metadata.installPath == expectedPath) {
                "Installed metadata does not match its owned library entry"
            }
            GameLibraryEntry(installationId, payloadRevision, metadata)
        }
        require(entries.map { it.installationId }.distinct().size == entries.size) { "Duplicate installation identity" }
        GameModLibrary(gameId, revision, entries)
    }

    private fun decodeProfile(profileId: String, text: String): ProfileModMembership = decode(text, MEMBERSHIP_KEYS) { json ->
        require(json.requiredString("gameId") == gameId && json.requiredString("profileId") == profileId) { "Wrong profile/game" }
        storagePaths.getProfileMembershipFile(gameId, profileId)
        val revision = json.requiredLong("revision").also { require(it > 0) }
        val array = json.getJSONArray("choices")
        val choices = List(array.length()) { index ->
            val choice = array.getJSONObject(index)
            requireKeys(choice, CHOICE_KEYS)
            val installationId = choice.requiredString("installationId")
            storagePaths.getGameLibraryEntryDir(gameId, installationId)
            ProfileModChoice(
                installationId,
                choice.requiredBoolean("enabled"),
                choice.requiredInt("priority").also { require(it > 0) }
            )
        }
        require(choices.map { it.installationId }.distinct().size == choices.size) { "Duplicate profile reference" }
        require(choices.map { it.priority }.distinct().size == choices.size) { "Duplicate profile priority" }
        ProfileModMembership(gameId, profileId, revision, choices)
    }

    private fun decodeMetadata(json: JSONObject): InstalledModRecord {
        requireKeys(json, METADATA_KEYS)
        return InstalledModRecord(
            modId = json.requiredString("modId"),
            displayName = json.requiredString("displayName"),
            installPath = json.requiredString("installPath"),
            sourceType = json.requiredString("sourceType"),
            sourceArchiveName = nullableSourceString(json, "sourceArchiveName"),
            installedAtEpochMillis = json.requiredLong("installedAtEpochMillis"),
            nexusGameDomain = nullableSourceString(json, "nexusGameDomain"),
            nexusModId = json.nullableLong("nexusModId"),
            nexusFileId = json.nullableLong("nexusFileId"),
            nexusFileName = nullableSourceString(json, "nexusFileName"),
            version = nullableSourceString(json, "version"),
            sourceUrl = nullableSourceString(json, "sourceUrl")
        )
    }

    private fun nullableSourceString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.requiredString(key)

    private fun <T> decode(text: String, keys: Set<String>, parse: (JSONObject) -> T): T {
        try {
            require(text.length <= MAX_RECORD_BYTES) { "Record exceeds byte limit" }
            val json = ModLibraryJsonReader.read(text)
            if (json.requiredInt("schemaVersion") != 1) throw UnsupportedRecord()
            requireKeys(json, keys)
            return parse(json)
        } catch (exception: Exception) {
            throw ModLibraryRecordException(
                if (exception is UnsupportedRecord) ModLibraryRecordFailure.UNSUPPORTED else ModLibraryRecordFailure.INVALID,
                exception
            )
        }
    }

    private class UnsupportedRecord : IOException("Unknown fields or record version")

    private fun requireKeys(json: JSONObject, expected: Set<String>) {
        val keys = json.keys().asSequence().toSet()
        if ((keys - expected).isNotEmpty()) throw UnsupportedRecord()
        require(keys == expected) { "Missing record fields" }
    }

    internal fun encodeLibrary(library: GameModLibrary): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("gameId", library.gameId)
        put("revision", library.revision)
        put("entries", JSONArray().apply {
            library.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("installationId", entry.installationId)
                    put("payloadRevision", entry.payloadRevision)
                    put("installedMetadata", encodeMetadata(entry.installedMetadata))
                })
            }
        })
    }.toString()

    private fun encodeProfile(membership: ProfileModMembership): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("gameId", membership.gameId)
        put("profileId", membership.profileId)
        put("revision", membership.revision)
        put("choices", JSONArray().apply {
            membership.choices.forEach { choice ->
                put(JSONObject().apply {
                    put("installationId", choice.installationId)
                    put("enabled", choice.enabled)
                    put("priority", choice.priority)
                })
            }
        })
    }.toString()

    private fun encodeMetadata(metadata: InstalledModRecord): JSONObject = JSONObject().apply {
        put("modId", metadata.modId)
        put("displayName", metadata.displayName)
        put("installPath", metadata.installPath)
        put("sourceType", metadata.sourceType)
        put("sourceArchiveName", metadata.sourceArchiveName ?: JSONObject.NULL)
        put("installedAtEpochMillis", metadata.installedAtEpochMillis)
        put("nexusGameDomain", metadata.nexusGameDomain ?: JSONObject.NULL)
        put("nexusModId", metadata.nexusModId ?: JSONObject.NULL)
        put("nexusFileId", metadata.nexusFileId ?: JSONObject.NULL)
        put("nexusFileName", metadata.nexusFileName ?: JSONObject.NULL)
        put("version", metadata.version ?: JSONObject.NULL)
        put("sourceUrl", metadata.sourceUrl ?: JSONObject.NULL)
    }
}
