package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.CriticalStorageException
import com.shonkware.droidmodloader.engine.data.requireCriticalDirectory
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import com.shonkware.droidmodloader.engine.data.GameModLibraryRepository
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacity
import com.shonkware.droidmodloader.engine.model.ModType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A preparation record only. PLANNED never admits ordinary readers or authorizes
 * relocation. Original state/metadata texts retain legacy identity/type/source facts;
 * candidate normal records are not published by this owner. */
internal data class GameLibraryMigrationPlan(
    val gameId: String,
    val inputs: List<MigrationInputFingerprint>,
    val profiles: List<MigrationProfilePlan>,
    val installations: List<MigrationInstallationPlan>,
    val candidateCatalogue: String
)

internal data class MigrationInputFingerprint(val path: String, val kind: String, val sha256: String?)
internal data class MigrationProfilePlan(val profileId: String, val legacyState: String?, val candidateMembership: String)
internal data class MigrationInstallationPlan(
    val profileId: String,
    val legacyModId: String,
    val installationId: String,
    val sourcePath: String,
    val destinationPath: String,
    val tree: MigrationTreeDigest,
    val legacyMetadata: String
)

/** Caller prepares persisted parent directories and serializes access; this owner
 * revalidates inventory before saving. A changed plan requires review, not
 * replacement of existing intent. No directory preparation, moves or cleanup here. */
internal class GameLibraryMigrationPlanRepository(
    private val paths: ProfileStoragePaths,
    private val gameId: String,
    private val inventory: GameLibraryMigrationInventory,
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations,
    private val availableBytes: (File) -> Long? = AndroidStorageCapacityProbe::availableBytes
) {
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }

    fun load(): GameLibraryMigrationPlan? = metadata().read(MAX_BYTES)?.let(::decode)

    fun save(plan: GameLibraryMigrationPlan) {
        val text = encode(plan)
        val validated = decode(text)
        val existing = load()
        if (existing != null && existing != validated) throw IOException("Migration mapping already exists; preserve evidence")
        inventory.requireMatching(validated)
        if (existing == null) {
            StorageCapacity.requireCopySpace(paths.getGameLibraryMigrationFile(gameId), text.toByteArray(Charsets.UTF_8).size.toLong(), availableBytes)
            metadata().replaceDurably(text, MAX_BYTES)
        } else {
            // A previous save may have promoted the file but failed its final
            // directory barrier. An identical retry must establish that barrier,
            // not claim durable intent merely because the bytes are readable.
            try {
                operations.syncDirectory(requireCriticalDirectory(paths.getGameLibraryMigrationFile(gameId).parentFile!!))
                check(load() == validated) { "Migration mapping changed during persistence confirmation" }
            } catch (failure: Exception) {
                throw CriticalStorageException("Could not confirm migration mapping persistence", true, failure)
            }
        }
        // Uncertain promotion is deliberately propagated. PLANNED is never a
        // migration completion or permission to use a partially moved layout.
    }

    private fun metadata() = CriticalMetadataFile(paths.getGameLibraryMigrationFile(gameId), operations)

    internal fun encode(plan: GameLibraryMigrationPlan): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("phase", "PLANNED")
        put("gameId", plan.gameId)
        put("inputs", JSONArray().apply { plan.inputs.forEach { input ->
            put(JSONObject().put("path", input.path).put("kind", input.kind).put("sha256", input.sha256 ?: JSONObject.NULL))
        } })
        put("profiles", JSONArray().apply { plan.profiles.forEach { profile ->
            put(JSONObject().put("profileId", profile.profileId).put("legacyState", profile.legacyState ?: JSONObject.NULL)
                .put("candidateMembership", profile.candidateMembership))
        } })
        put("installations", JSONArray().apply { plan.installations.forEach { installation ->
            put(JSONObject().put("profileId", installation.profileId).put("legacyModId", installation.legacyModId)
                .put("installationId", installation.installationId).put("sourcePath", installation.sourcePath)
                .put("destinationPath", installation.destinationPath).put("sha256", installation.tree.sha256)
                .put("entryCount", installation.tree.entryCount).put("payloadBytes", installation.tree.payloadBytes)
                .put("legacyMetadata", installation.legacyMetadata))
        } })
        put("candidateCatalogue", plan.candidateCatalogue)
    }.toString().also { requireMigrationRecordSize(it) }

    internal fun decode(text: String): GameLibraryMigrationPlan {
        requireMigrationRecordSize(text)
        val json = ModLibraryJsonReader.read(text)
        migrationKeys(json, setOf("schemaVersion", "phase", "gameId", "inputs", "profiles", "installations", "candidateCatalogue"))
        require(json.requiredInt("schemaVersion") == 1 && json.requiredString("phase") == "PLANNED") { "Unsupported migration record" }
        require(json.requiredString("gameId") == gameId) { "Wrong migration game" }
        val inputs = json.getJSONArray("inputs").objects().map { input ->
            migrationKeys(input, setOf("path", "kind", "sha256"))
            val kind = input.requiredString("kind")
            require(kind in setOf("ABSENT", "FILE", "TREE", "NAMES"))
            val hash = if (input.isNull("sha256")) null else input.requiredString("sha256").also(::requireMigrationHash)
            require((kind == "ABSENT") == (hash == null))
            val path = input.requiredString("path")
            inventory.validateInputPath(File(path))
            MigrationInputFingerprint(path, kind, hash)
        }
        require(inputs.isNotEmpty() && inputs.map { it.path }.distinct().size == inputs.size) { "Missing or duplicate migration input" }
        val repository = GameModLibraryRepository(paths, gameId)
        val catalogueText = json.requiredString("candidateCatalogue")
        val catalogue = repository.decodeLibrary(catalogueText)
        require(catalogue.revision == 1L && catalogue.entries.all { it.payloadRevision == 1L })
        val profiles = json.getJSONArray("profiles").objects().map { profile ->
            migrationKeys(profile, setOf("profileId", "legacyState", "candidateMembership"))
            val profileId = profile.requiredString("profileId")
            paths.getProfileMembershipFile(gameId, profileId)
            MigrationProfilePlan(profileId, if (profile.isNull("legacyState")) null else profile.getString("legacyState"),
                profile.requiredString("candidateMembership"))
        }
        require(profiles.isNotEmpty() && profiles.map { it.profileId }.distinct().size == profiles.size)
        val installations = json.getJSONArray("installations").objects().map { installation ->
            migrationKeys(installation, setOf("profileId", "legacyModId", "installationId", "sourcePath", "destinationPath",
                "sha256", "entryCount", "payloadBytes", "legacyMetadata"))
            val installationId = installation.requiredString("installationId")
            val destinationPath = installation.requiredString("destinationPath")
            require(destinationPath == paths.getGameLibraryEntryDir(gameId, installationId).absolutePath)
            val legacyModId = installation.requiredString("legacyModId")
            require(legacyModId !in setOf(".", "..") && '/' !in legacyModId && '\\' !in legacyModId && '\u0000' !in legacyModId)
            val profileId = installation.requiredString("profileId")
            require(installation.requiredString("sourcePath") == File(paths.getLegacyProfileInternalDir(profileId), "mods/$legacyModId").absolutePath)
            require(installationId == "legacy_" + migrationHash(JSONArray(listOf(gameId, profileId, legacyModId)).toString().toByteArray(Charsets.UTF_8)))
            val tree = MigrationTreeDigest(installation.requiredString("sha256").also(::requireMigrationHash),
                installation.requiredInt("entryCount").also { require(it <= 200_000) }, installation.requiredLong("payloadBytes"))
            MigrationInstallationPlan(installation.requiredString("profileId"), installation.requiredString("legacyModId"),
                installationId, installation.requiredString("sourcePath"), destinationPath, tree, installation.requiredString("legacyMetadata"))
        }
        require(installations.map { it.installationId }.distinct().size == installations.size)
        require(installations.map { it.sourcePath }.distinct().size == installations.size)
        require(installations.map { it.profileId to it.legacyModId }.distinct().size == installations.size)
        val profileIds = profiles.map { it.profileId }.toSet()
        require(installations.all { it.profileId in profileIds })
        require(catalogue.entries.map { it.installationId }.toSet() == installations.map { it.installationId }.toSet())
        return GameLibraryMigrationPlan(gameId, inputs, profiles, installations, catalogueText).also { plan ->
            // Structural relationships are checked without consulting filesystem or
            // permitting normal-reader discovery. Full legacy semantics and input
            // paths are checked by the inventory owner before save/use.
            val byProfile = installations.groupBy { it.profileId }
            val catalogueByIdentity = catalogue.entries.associateBy { it.installationId }
            plan.profiles.forEach { profile ->
                val membership = repository.decodeProfile(profile.profileId, profile.candidateMembership)
                require(membership.revision == 1L)
                val profileInstallations = byProfile[profile.profileId].orEmpty()
                val byLegacyIdentity = profileInstallations.associateBy { it.legacyModId }
                val choicesByIdentity = membership.choices.associateBy { it.installationId }
                require(membership.choices.map { it.installationId }.toSet() == profileInstallations.map { it.installationId }.toSet())
                val legacyMods = profile.legacyState?.let(::migrationArray)?.objects() ?: emptyList()
                require(legacyMods.size == profileInstallations.size)
                require(legacyMods.map { it.requiredString("id") }.toSet() == profileInstallations.map { it.legacyModId }.toSet())
                legacyMods.forEach { legacyMod ->
                    migrationKeys(legacyMod, setOf("id", "name", "installPath", "enabled", "priority", "modType"))
                    legacyMod.requiredString("name")
                    ModType.valueOf(legacyMod.requiredString("modType"))
                    val entry = byLegacyIdentity.getValue(legacyMod.requiredString("id"))
                    val choice = choicesByIdentity.getValue(entry.installationId)
                    require(legacyMod.requiredString("installPath") == entry.sourcePath)
                    require(choice.enabled == legacyMod.requiredBoolean("enabled") && choice.priority == legacyMod.requiredInt("priority"))
                    val metadata = ModLibraryJsonReader.read(entry.legacyMetadata)
                    require(metadata.requiredString("modId") == entry.legacyModId && metadata.requiredString("installPath") == entry.sourcePath)
                    val translated = migrationCandidateMetadata(entry.legacyMetadata, entry.installationId, entry.destinationPath)
                    val candidateEntry = JSONObject().put("installationId", entry.installationId).put("payloadRevision", 1)
                        .put("installedMetadata", translated)
                    val expected = repository.decodeLibrary(JSONObject().put("schemaVersion", 1).put("gameId", gameId)
                        .put("revision", 1).put("entries", JSONArray().put(candidateEntry)).toString()).entries.single()
                    require(catalogueByIdentity.getValue(entry.installationId) == expected) { "Migration source facts changed" }
                }
            }
        }
    }
}

internal fun migrationCandidateMetadata(text: String, installationId: String, destinationPath: String): JSONObject =
    ModLibraryJsonReader.read(text).apply {
        put("modId", installationId).put("installPath", destinationPath)
        // Known older source-field absence remains null, never an invented fact.
        for (field in listOf("sourceArchiveName", "nexusGameDomain", "nexusModId", "nexusFileId", "nexusFileName", "version", "sourceUrl")) {
            if (!has(field)) put(field, JSONObject.NULL)
        }
    }

internal fun migrationKeys(json: JSONObject, expected: Set<String>) {
    require(json.keys().asSequence().toSet() == expected) { "Missing or unsupported migration fields; preserve evidence" }
}
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
internal fun migrationArray(text: String): JSONArray = ModLibraryJsonReader.read("{\"items\":$text}").getJSONArray("items")
internal fun migrationHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun requireMigrationHash(hash: String) { require(hash.matches(Regex("[0-9a-f]{64}"))) }
internal fun requireMigrationRecordSize(text: String) {
    require(text.length <= GameLibraryMigrationPlanRepository.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= GameLibraryMigrationPlanRepository.MAX_BYTES) {
        "Migration mapping exceeds byte limit"
    }
}
