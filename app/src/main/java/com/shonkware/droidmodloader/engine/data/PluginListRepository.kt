package com.shonkware.droidmodloader.engine.data

import com.shonkware.droidmodloader.engine.model.PluginEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PluginListRepository(
    private val pluginListFile: File
) {

    fun save(entries: List<PluginEntry>) {
        val array = JSONArray()

        for (entry in entries) {
            val obj = JSONObject()
            obj.put("normalizedPath", entry.normalizedPath)
            obj.put("pluginName", entry.pluginName)
            obj.put("sourceModId", entry.sourceModId)
            obj.put("sourceModName", entry.sourceModName)
            obj.put("enabled", entry.enabled)
            obj.put("priority", entry.priority)
            obj.put("pluginType", entry.pluginType)
            array.put(obj)
        }

        pluginListFile.parentFile?.mkdirs()
        pluginListFile.writeText(array.toString(2))
    }

    fun load(): List<PluginEntry> {
        if (!pluginListFile.exists()) return emptyList()

        val text = pluginListFile.readText()
        if (text.isBlank()) return emptyList()

        val array = JSONArray(text)
        val results = mutableListOf<PluginEntry>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            results.add(
                PluginEntry(
                    normalizedPath = obj.optString("normalizedPath", ""),
                    pluginName = obj.optString("pluginName", ""),
                    sourceModId = obj.optString("sourceModId", ""),
                    sourceModName = obj.optString("sourceModName", ""),
                    enabled = obj.optBoolean("enabled", true),
                    priority = obj.optInt("priority", 0),
                    pluginType = obj.optString("pluginType", "ESP")
                )
            )
        }

        return results
    }

    fun clear() {
        if (pluginListFile.exists()) {
            pluginListFile.delete()
        }
    }
}