package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.DeploymentRecord
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

    fun clear() {
        if (manifestFile.exists()) {
            manifestFile.delete()
        }
    }
}