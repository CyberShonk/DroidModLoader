package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidence
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceRead
import com.shonkware.droidmodloader.engine.deploy.DeploymentEvidenceState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GameDeploymentConfigRepository(
    private val configFile: File
) {

    fun save(configs: List<GameDeploymentConfig>) {
        val array = JSONArray()

        for (config in configs) {
            val obj = JSONObject()
            obj.put("gameId", config.gameId)
            obj.put("displayName", config.displayName)
            obj.put("targetDataPath", config.targetDataPath)
            obj.put("targetRootPath", config.targetRootPath)
            obj.put("realDeployEnabled", config.realDeployEnabled)
            obj.put("dataPathReselectionRequired", config.dataPathReselectionRequired)
            obj.put("rootPathReselectionRequired", config.rootPathReselectionRequired)
            array.put(obj)
        }

        configFile.parentFile?.mkdirs()
        configFile.writeText(array.toString(2))
    }

    fun load(): List<GameDeploymentConfig> {
        if (!configFile.exists()) return emptyList()

        val text = configFile.readText()
        if (text.isBlank()) return emptyList()

        return parse(text)
    }

    /** Opt-in strict legacy read; active load/save behavior is unchanged. */
    internal fun readStagedEvidence(read: DeploymentEvidenceRead, metadataDirectory: File): DeploymentEvidence<List<GameDeploymentConfig>> =
        read.read(configFile, metadataDirectory) { text ->
            val array = read.array(text)
            read.rows(array)
            val gameIds = hashSetOf<String>()
            var complete = true
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                read.keys(row, setOf("gameId", "displayName"), setOf("targetDataPath", "targetRootPath",
                    "realDeployEnabled", "dataPathReselectionRequired", "rootPathReselectionRequired", "targetTreeUri", "targetRootTreeUri"))
                with(CriticalMetadataJson) {
                    require(gameIds.add(row.requiredString("gameId"))) { "Duplicate game config" }
                    row.requiredString("displayName")
                    for (key in listOf("targetDataPath", "targetRootPath", "targetTreeUri", "targetRootTreeUri")) {
                        if (row.has(key)) require(row.get(key) is String) { "Invalid target locator" }
                    }
                    for (key in listOf("realDeployEnabled", "dataPathReselectionRequired", "rootPathReselectionRequired")) {
                        if (row.has(key)) row.requiredBoolean(key)
                    }
                    if (!row.has("realDeployEnabled") || !row.has("targetDataPath") || !row.has("targetRootPath") ||
                        row.optString("targetTreeUri").isNotBlank() || row.optString("targetRootTreeUri").isNotBlank()) complete = false
                    for (key in listOf("targetDataPath", "targetRootPath")) {
                        val path = row.optString(key)
                        if (path.isNotBlank()) require(File(path).isAbsolute && File(path).toPath().normalize() == File(path).toPath()) {
                            "Unsafe target identity"
                        }
                    }
                }
            }
            DeploymentEvidence(when {
                !complete -> DeploymentEvidenceState.INCOMPLETE
                array.length() == 0 -> DeploymentEvidenceState.VALID_EMPTY
                else -> DeploymentEvidenceState.VALID
            }, parse(text))
        }

    internal fun parse(text: String): List<GameDeploymentConfig> {
        val array = JSONArray(text)
        val results = mutableListOf<GameDeploymentConfig>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val targetDataPath = obj.optString("targetDataPath", "").trim()
            val targetRootPath = obj.optString("targetRootPath", "").trim()
            val legacyDataUri = obj.optString("targetTreeUri", "").trim()
            val legacyRootUri = obj.optString("targetRootTreeUri", "").trim()

            results.add(
                GameDeploymentConfig(
                    gameId = obj.optString("gameId", ""),
                    displayName = obj.optString("displayName", ""),
                    targetDataPath = targetDataPath,
                    realDeployEnabled = obj.optBoolean("realDeployEnabled", false),
                    targetRootPath = targetRootPath,
                    dataPathReselectionRequired = obj.optBoolean(
                        "dataPathReselectionRequired",
                        targetDataPath.isBlank() && legacyDataUri.isNotBlank()
                    ),
                    rootPathReselectionRequired = obj.optBoolean(
                        "rootPathReselectionRequired",
                        targetRootPath.isBlank() && legacyRootUri.isNotBlank()
                    )
                )
            )
        }

        return results
    }
}
