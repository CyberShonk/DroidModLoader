package com.shonkware.droidmodloader.engine.baseline

import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidence
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceRead
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceState
import com.shonkware.droidmodloader.engine.deploy.LegacyTargetEvidenceScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DataBaselineRepository(
    private val baselineFile: File
) {
    fun exists(): Boolean {
        return baselineFile.exists() && baselineFile.readText().isNotBlank()
    }

    fun load(): DataBaselineSnapshot? {
        if (!exists()) return null

        val obj = JSONObject(baselineFile.readText())
        val filesArray = obj.optJSONArray("files") ?: JSONArray()

        val files = mutableListOf<DataBaselineFileRecord>()

        for (i in 0 until filesArray.length()) {
            val fileObj = filesArray.getJSONObject(i)

            files.add(
                DataBaselineFileRecord(
                    normalizedPath = fileObj.getString("normalizedPath"),
                    sizeBytes = if (fileObj.isNull("sizeBytes")) null else fileObj.optLong("sizeBytes"),
                    modifiedEpochMillis = if (fileObj.isNull("modifiedEpochMillis")) null else fileObj.optLong("modifiedEpochMillis")
                )
            )
        }

        return DataBaselineSnapshot(
            gameId = obj.getString("gameId"),
            createdAtEpochMillis = obj.optLong("createdAtEpochMillis", 0L),
            targetDescription = obj.optString("targetDescription", ""),
            files = files
        )
    }

    /** Staged metadata inspection, never a complete content baseline or target scan. */
    internal fun readStagedEvidence(
        read: DeploymentEvidenceRead,
        metadataDirectory: File,
        target: LegacyTargetEvidenceScope
    ): DeploymentEvidence<DataBaselineSnapshot> = read.read(baselineFile, metadataDirectory) { text ->
        read.matches(target.baselineFile == baselineFile)
        val row = read.objectRecord(text)
        read.keys(row, setOf("gameId", "files"), setOf("createdAtEpochMillis", "targetDescription"))
        with(CriticalMetadataJson) {
            val gameId = row.requiredString("gameId")
            read.matches(gameId == target.identity.gameId)
            val createdAt = if (row.has("createdAtEpochMillis")) row.requiredLong("createdAtEpochMillis") else null
            val description = if (row.has("targetDescription")) row.requiredString("targetDescription") else null
            if (description != null) read.matches(description == target.identity.target)
            var complete = createdAt != null && description != null
            val array = row.getJSONArray("files")
            read.rows(array)
            val checkPath = read.paths()
            val files = List(array.length()) { index ->
                val file = array.getJSONObject(index)
                read.keys(file, setOf("normalizedPath"), setOf("sizeBytes", "modifiedEpochMillis"))
                val path = file.requiredString("normalizedPath")
                checkPath(path)
                val size = file.nullableLong("sizeBytes")
                val modified = file.nullableLong("modifiedEpochMillis")
                if (size == null || modified == null) complete = false
                DataBaselineFileRecord(path, size, modified)
            }
            DeploymentEvidence(when {
                !complete -> DeploymentEvidenceState.INCOMPLETE
                files.isEmpty() -> DeploymentEvidenceState.VALID_EMPTY
                else -> DeploymentEvidenceState.VALID
            }, DataBaselineSnapshot(gameId, createdAt ?: 0L, description.orEmpty(), files))
        }
    }

    fun save(snapshot: DataBaselineSnapshot) {
        val obj = JSONObject()
        obj.put("gameId", snapshot.gameId)
        obj.put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
        obj.put("targetDescription", snapshot.targetDescription)

        val filesArray = JSONArray()

        snapshot.files.forEach { file ->
            val fileObj = JSONObject()
            fileObj.put("normalizedPath", file.normalizedPath)
            fileObj.put("sizeBytes", file.sizeBytes)
            fileObj.put("modifiedEpochMillis", file.modifiedEpochMillis)
            filesArray.put(fileObj)
        }

        obj.put("files", filesArray)

        baselineFile.parentFile?.mkdirs()
        baselineFile.writeText(obj.toString(2))
    }

    fun clear() {
        if (baselineFile.exists()) {
            baselineFile.delete()
        }
    }
}