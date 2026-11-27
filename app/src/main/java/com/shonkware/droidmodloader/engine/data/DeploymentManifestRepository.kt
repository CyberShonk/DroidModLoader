package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidence
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceRead
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceState
import com.shonkware.droidmodloader.engine.deploy.LegacyTargetEvidenceScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DeploymentManifestRepository(
    private val manifestFile: File
) {

    fun save(records: List<DeploymentRecord>) {
        val array = JSONArray()

        for (record in records) {
            val obj = JSONObject()

            obj.put("normalizedPath", record.normalizedPath)
            obj.put("winningModId", record.winningModId)
            obj.put("sourceFilePath", record.sourceFilePath)
            obj.put("winningModName", record.winningModName)
            obj.put("hash", record.hash)

            obj.put("hadPreExistingTargetFile", record.hadPreExistingTargetFile)

            if (record.backupFilePath == null) {
                obj.put("backupFilePath", JSONObject.NULL)
            } else {
                obj.put("backupFilePath", record.backupFilePath)
            }

            if (record.backupCreatedAtEpochMillis == null) {
                obj.put("backupCreatedAtEpochMillis", JSONObject.NULL)
            } else {
                obj.put("backupCreatedAtEpochMillis", record.backupCreatedAtEpochMillis)
            }

            array.put(obj)
        }

        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(array.toString(2))
    }

    fun load(): List<DeploymentRecord> {
        if (!manifestFile.exists()) return emptyList()

        val text = manifestFile.readText()
        if (text.isBlank()) return emptyList()

        return parse(text)
    }

    internal fun parse(text: String): List<DeploymentRecord> {
        val array = JSONArray(text)
        val results = mutableListOf<DeploymentRecord>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            val backupFilePath = if (obj.has("backupFilePath") && !obj.isNull("backupFilePath")) {
                obj.optString("backupFilePath").ifBlank { null }
            } else {
                null
            }

            val backupCreatedAtEpochMillis = if (
                obj.has("backupCreatedAtEpochMillis") &&
                !obj.isNull("backupCreatedAtEpochMillis")
            ) {
                obj.optLong("backupCreatedAtEpochMillis")
            } else {
                null
            }

            results.add(
                DeploymentRecord(
                    normalizedPath = obj.optString("normalizedPath", ""),
                    winningModId = obj.optString("winningModId", ""),
                    sourceFilePath = obj.optString("sourceFilePath", ""),
                    winningModName = obj.optString(
                        "winningModName",
                        obj.optString("winningModId", "")
                    ),
                    hash = obj.optString("hash", ""),

                    hadPreExistingTargetFile = obj.optBoolean(
                        "hadPreExistingTargetFile",
                        false
                    ),
                    backupFilePath = backupFilePath,
                    backupCreatedAtEpochMillis = backupCreatedAtEpochMillis
                )
            )
        }

        return results
    }

    /** Staged historical read only. Does not verify target bytes or authorize restore. */
    internal fun readStagedEvidence(
        read: DeploymentEvidenceRead,
        metadataDirectory: File,
        target: LegacyTargetEvidenceScope
    ): DeploymentEvidence<List<DeploymentRecord>> =
        read.read(manifestFile, metadataDirectory) { text ->
            read.matches(manifestFile == target.manifestFile)
            read.ownedPath(metadataDirectory, target.backupDirectory)
            val array = read.array(text)
            read.rows(array, DeploymentEvidenceRead.MAX_MANIFEST_ROWS)
            val checkPath = read.paths()
            var complete = true
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                read.keys(row, setOf("normalizedPath", "winningModId", "sourceFilePath", "hash"),
                    setOf("winningModName", "hadPreExistingTargetFile", "backupFilePath", "backupCreatedAtEpochMillis"))
                with(CriticalMetadataJson) {
                    val path = row.requiredString("normalizedPath")
                    checkPath(path)
                    row.requiredString("winningModId")
                    row.requiredString("sourceFilePath") // Historical association only; never opened.
                    if (row.has("winningModName")) row.requiredString("winningModName")
                    read.hash(row.requiredString("hash"))
                    val hadOriginal = if (row.has("hadPreExistingTargetFile")) row.requiredBoolean("hadPreExistingTargetFile")
                        else { complete = false; null }
                    val backup = if (row.has("backupFilePath") && !row.isNull("backupFilePath")) row.requiredString("backupFilePath") else null
                    val backupTime = row.nullableLong("backupCreatedAtEpochMillis")
                    require(backup != null || backupTime == null) { "Backup timestamp without backup" }
                    require(backup == null || hadOriginal != false) { "Conflicting backup facts" }
                    if (hadOriginal == true && backup == null) complete = false
                    if (backup != null) {
                        val location = File(backup)
                        read.ownedPath(target.backupDirectory, location)
                        require(location == File(target.backupDirectory, path)) { "Foreign backup locator" }
                    }
                }
            }
            val state = when {
                !complete -> DeploymentEvidenceState.INCOMPLETE
                array.length() == 0 -> DeploymentEvidenceState.VALID_EMPTY
                else -> DeploymentEvidenceState.VALID
            }
            DeploymentEvidence(state, parse(text))
        }

    fun clear() {
        if (manifestFile.exists()) {
            manifestFile.delete()
        }
    }
}