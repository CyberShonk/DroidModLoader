package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
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
