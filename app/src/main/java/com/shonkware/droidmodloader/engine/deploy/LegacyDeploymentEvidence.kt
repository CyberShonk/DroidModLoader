package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.baseline.DataBaselineSnapshot
import com.shonkware.droidmodloader.engine.data.CriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.data.ModLibraryJsonReader
import com.shonkware.droidmodloader.engine.data.SystemCriticalMetadataFileOperations
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.index.ModFileIndexService
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

/** Staged historical evidence only. No state here grants target mutation/adoption authority. */
internal enum class DeploymentEvidenceState {
    MISSING, VALID_EMPTY, VALID, INCOMPLETE, INVALID, UNREADABLE, UNSUPPORTED, CONFLICTING
}

internal data class DeploymentEvidence<out T>(
    val state: DeploymentEvidenceState,
    val value: T? = null
)

internal data class LegacyTargetEvidenceScope(
    val identity: DeploymentTargetIdentity,
    val targetType: GameTargetType,
    val manifestFile: File,
    val backupDirectory: File,
    val baselineFile: File? = null
)

/** Caller supplies the complete captured scope; no profile discovery or mutable providers. */
internal data class LegacyProfileEvidenceScope(
    val gameId: String,
    val profileId: String,
    val metadataDirectory: File,
    val configFile: File,
    val journalFile: File,
    val data: LegacyTargetEvidenceScope,
    val root: LegacyTargetEvidenceScope
) {
    internal fun validate() {
        require(gameId.isNotBlank() && profileId.isNotBlank())
        require(data.targetType == GameTargetType.DATA && root.targetType == GameTargetType.GAME_ROOT)
        listOf(data, root).forEach { target ->
            require(target.identity.gameId == gameId)
            val directory = File(target.identity.target)
            // Target identity is supplied evidence. Never enumerate/open target payloads here.
            require(directory.isAbsolute && directory.toPath().normalize() == directory.toPath())
            require(target.identity.mode == when (target.targetType) {
                GameTargetType.DATA -> if (data.identity.mode == "simulated") "simulated" else "real_path"
                GameTargetType.GAME_ROOT -> if (data.identity.mode == "simulated") "root_simulated" else "root_real_path"
            })
        }
        if (data.identity.mode == "real_path") require(File(data.identity.target).parentFile == File(root.identity.target))
        else require(File(data.identity.target).parentFile == File(root.identity.target).parentFile && data.identity.target != root.identity.target)
        require(root.baselineFile == null)
        val metadataPath = metadataDirectory.toPath()
        require(metadataDirectory.isAbsolute && metadataPath.normalize() == metadataPath)
        val files = listOfNotNull(configFile, journalFile, data.manifestFile, root.manifestFile, data.baselineFile)
        require(files.distinct().size == files.size) { "Metadata roles share a record location" }
        val backups = listOf(data.backupDirectory.toPath(), root.backupDirectory.toPath())
        require(!backups[0].startsWith(backups[1]) && !backups[1].startsWith(backups[0])) { "Backup roles overlap" }
        for (path in files.map { it.toPath() } + backups) {
            require(path.isAbsolute && path.normalize() == path && path != metadataPath && path.startsWith(metadataPath)) {
                "Evidence locator escapes captured metadata scope"
            }
        }
        require(files.none { file -> backups.any { file.toPath().startsWith(it) || it.startsWith(file.toPath()) } }) {
            "Metadata and backup locations overlap"
        }
        for (target in listOf(data, root)) {
            val path = File(target.identity.target).toPath()
            require(!path.startsWith(metadataPath) && !metadataPath.startsWith(path)) { "Target and metadata locations overlap" }
        }
    }
}

internal data class LegacyProfileEvidence(
    val scope: LegacyProfileEvidenceScope,
    val configuration: DeploymentEvidence<List<GameDeploymentConfig>>,
    val journal: DeploymentEvidence<DeploymentJournalRecord>,
    val dataManifest: DeploymentEvidence<List<DeploymentRecord>>,
    val rootManifest: DeploymentEvidence<List<DeploymentRecord>>,
    val baseline: DeploymentEvidence<DataBaselineSnapshot>
)

/** A current metadata snapshot still does not verify actual target/backup contents. */
internal data class LegacyDeploymentEvidenceSnapshot(
    val profiles: List<LegacyProfileEvidence>,
    val stale: Boolean,
    val conflictingDestinations: Set<String>
)

/** Shared bounded read machinery for the four legacy repository owners, not a writer.
 * One instance spans a captured metadata set, then rechecks exact bytes before exposure.
 * All operations are synchronous and belong off the UI thread under session admission. */
internal class DeploymentEvidenceRead(
    private val operations: CriticalMetadataFileOperations = SystemCriticalMetadataFileOperations
) {
    companion object {
        const val MAX_RECORD_BYTES = 8 * 1024 * 1024
        const val MAX_CAPTURE_BYTES = 32 * 1024 * 1024
        const val MAX_RECORDS = 1_000
        const val MAX_ROWS = 200_000
        const val MAX_MANIFEST_ROWS = 50_000
    }

    private data class Observed(val root: File, val fingerprint: String?, val maximumBytes: Int)
    private val observed = linkedMapOf<File, Observed>()
    private var remainingBytes = MAX_CAPTURE_BYTES
    private var remainingRows = MAX_ROWS
    private var recordReads = 0
    private var changedDuringRead = false

    fun <T> read(
        file: File,
        metadataDirectory: File,
        parse: (String) -> DeploymentEvidence<T>
    ): DeploymentEvidence<T> {
        try {
            ownedPath(metadataDirectory, file)
        } catch (_: IOException) { return DeploymentEvidence(DeploymentEvidenceState.UNREADABLE) }
        catch (_: IllegalArgumentException) { return DeploymentEvidence(DeploymentEvidenceState.INVALID) }
        check(++recordReads <= MAX_RECORDS) { "Deployment evidence record limit exceeded" }
        val previous = observed[file]
        if (previous != null && previous.root != metadataDirectory) return DeploymentEvidence(DeploymentEvidenceState.CONFLICTING)
        if (remainingBytes == 0) return DeploymentEvidence(DeploymentEvidenceState.UNREADABLE)
        val maximumBytes = minOf(MAX_RECORD_BYTES, remainingBytes)
        val bytes = try {
            operations.readBounded(file, maximumBytes).also { require(it.size <= maximumBytes) }
        } catch (_: NoSuchFileException) {
            if (previous != null && previous.fingerprint != null) changedDuringRead = true
            observed.putIfAbsent(file, Observed(metadataDirectory, null, maximumBytes))
            return DeploymentEvidence(DeploymentEvidenceState.MISSING)
        } catch (_: Exception) { return DeploymentEvidence(DeploymentEvidenceState.UNREADABLE) }
        remainingBytes -= bytes.size
        val currentFingerprint = fingerprint(bytes)
        if (previous != null && previous.fingerprint != currentFingerprint) changedDuringRead = true
        observed.putIfAbsent(file, Observed(metadataDirectory, currentFingerprint, maximumBytes))
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            parse(text)
        } catch (_: UnsupportedEvidence) { DeploymentEvidence(DeploymentEvidenceState.UNSUPPORTED) }
        catch (_: ConflictingEvidence) { DeploymentEvidence(DeploymentEvidenceState.CONFLICTING) }
        catch (_: CharacterCodingException) { DeploymentEvidence(DeploymentEvidenceState.INVALID) }
        catch (_: Exception) { DeploymentEvidence(DeploymentEvidenceState.INVALID) }
    }

    fun isCurrent(): Boolean = !changedDuringRead && observed.all { (file, expected) ->
        try {
            ownedPath(expected.root, file)
            fingerprint(operations.readBounded(file, expected.maximumBytes)) == expected.fingerprint
        } catch (_: NoSuchFileException) { expected.fingerprint == null }
        catch (_: Exception) { false }
    }

    fun rows(array: JSONArray, maximum: Int = MAX_ROWS) {
        require(array.length() <= minOf(maximum, remainingRows)) { "Deployment evidence row limit exceeded" }
        remainingRows -= array.length()
    }

    fun array(text: String): JSONArray = ModLibraryJsonReader.readArray(text)
    fun objectRecord(text: String): JSONObject = ModLibraryJsonReader.read(text)

    fun keys(record: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = record.keys().asSequence().toSet()
        if ((actual - required - optional).isNotEmpty()) throw UnsupportedEvidence()
        require(actual.containsAll(required)) { "Missing legacy evidence fields" }
    }

    fun matches(condition: Boolean) { if (!condition) throw ConflictingEvidence() }
    fun supports(condition: Boolean) { if (!condition) throw UnsupportedEvidence() }

    fun ownedPath(root: File, file: File) {
        require(root.isAbsolute && file.isAbsolute && root == root.canonicalFile && file == file.canonicalFile &&
            file.toPath().startsWith(root.toPath()) && file != root) { "Unsafe legacy metadata/backup location" }
    }

    fun hash(value: String) { require(ManagedContentIntegrity.hasRecordedHash(value)) { "Invalid recorded hash" } }

    /** Detect duplicates and file/directory/case/Unicode collisions without walking payloads. */
    fun paths(): (String) -> Unit {
        val entries = hashMapOf<String, Pair<String, Boolean>>()
        return { path ->
            val normalized = ModFileIndexService.normalizedSourcePath(path)
            val rawParts = path.split('/'); val normalizedParts = normalized.split('/')
            rawParts.indices.forEach { index ->
                val raw = rawParts.take(index + 1).joinToString("/")
                val key = normalizedParts.take(index + 1).joinToString("/")
                val value = raw to (index < rawParts.lastIndex)
                val previous = entries.putIfAbsent(key, value)
                require(previous == null || (previous == value && value.second)) { "Duplicate or colliding evidence path" }
                require(entries.size <= MAX_ROWS) { "Deployment evidence path limit exceeded" }
            }
        }
    }

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class UnsupportedEvidence : IllegalArgumentException()
    private class ConflictingEvidence : IllegalArgumentException()
}
