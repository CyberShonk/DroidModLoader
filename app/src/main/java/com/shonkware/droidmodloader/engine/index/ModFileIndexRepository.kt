package com.shonkware.droidmodloader.engine.index

import com.shonkware.droidmodloader.engine.model.Mod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ModFileIndexRepository(
    private val indexRootDir: File
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }

    fun getIndexFile(mod: Mod): File = getIndexFileForModId(mod.id)

    internal fun getIndexFileForModId(modId: String): File =
        File(indexRootDir, "${sanitizeFileName(modId)}.json")

    fun load(mod: Mod): ModFileIndexSnapshot? {
        val file = getIndexFile(mod)
        if (!file.exists()) return null

        val text = file.readText()
        if (text.isBlank()) return null

        val json = JSONObject(text)
        val entriesJson = json.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<ModFileIndexEntry>()

        for (i in 0 until entriesJson.length()) {
            val obj = entriesJson.getJSONObject(i)

            entries.add(
                ModFileIndexEntry(
                    originalPath = obj.optString("originalPath", ""),
                    normalizedPath = obj.optString("normalizedPath", ""),
                    hash = obj.optString("hash", ""),
                    sizeBytes = obj.optLong("sizeBytes", -1L),
                    modifiedEpochMillis = obj.optLong("modifiedEpochMillis", -1L)
                )
            )
        }

        return ModFileIndexSnapshot(
            schemaVersion = json.optInt("schemaVersion", 0),
            modId = json.optString("modId", mod.id),
            modName = json.optString("modName", mod.name),
            modRootPath = json.optString("modRootPath", mod.installPath),
            createdAtEpochMillis = json.optLong("createdAtEpochMillis", 0L),
            entries = entries
        )
    }

    fun save(snapshot: ModFileIndexSnapshot) {
        val entriesJson = JSONArray()

        for (entry in snapshot.entries) {
            val obj = JSONObject()
            obj.put("originalPath", entry.originalPath)
            obj.put("normalizedPath", entry.normalizedPath)
            obj.put("hash", entry.hash)
            obj.put("sizeBytes", entry.sizeBytes)
            obj.put("modifiedEpochMillis", entry.modifiedEpochMillis)
            entriesJson.put(obj)
        }

        val json = JSONObject()
        json.put("schemaVersion", snapshot.schemaVersion)
        json.put("modId", snapshot.modId)
        json.put("modName", snapshot.modName)
        json.put("modRootPath", snapshot.modRootPath)
        json.put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
        json.put("entries", entriesJson)

        indexRootDir.mkdirs()
        File(indexRootDir, "${sanitizeFileName(snapshot.modId)}.json")
            .writeText(json.toString(2))
    }

    fun delete(mod: Mod) {
        val file = getIndexFile(mod)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .trim('_')
            .ifBlank { "unknown_mod" }
    }
}