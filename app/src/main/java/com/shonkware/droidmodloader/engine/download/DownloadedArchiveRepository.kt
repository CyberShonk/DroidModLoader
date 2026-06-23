package com.shonkware.droidmodloader.engine.download

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DownloadedArchiveRepository(
    private val archiveLibraryDir: File,
    private val archiveListFile: File
) {
    fun load(): List<DownloadedArchiveRecord> {
        if (!archiveListFile.exists()) return emptyList()

        return try {
            val root = JSONArray(archiveListFile.readText())
            buildList {
                for (index in 0 until root.length()) {
                    val obj = root.getJSONObject(index)
                    add(fromJson(obj))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(records: List<DownloadedArchiveRecord>) {
        archiveListFile.parentFile?.mkdirs()

        val root = JSONArray()
        records.forEach { record ->
            root.put(toJson(record))
        }

        archiveListFile.writeText(root.toString(2))
    }

    fun registerArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String? = null,
        sourceUrl: String? = null
    ): DownloadedArchiveRecord {
        archiveLibraryDir.mkdirs()

        val existingRecords = load()
        val nexusInfo = sourceUrl?.let { NexusUrlParser.parse(it) }

        val record = DownloadedArchiveRecord(
            archiveId = buildArchiveId(archiveFile),
            displayName = ArchiveMetadataReader.cleanDisplayName(originalDisplayName),
            fileName = archiveFile.name,
            archiveFormat = ArchiveMetadataReader.detectArchiveFormat(archiveFile.name),
            relativePath = archiveFile.relativeToOrSelf(archiveLibraryDir).path,
            sizeBytes = archiveFile.length(),
            modifiedAtMillis = archiveFile.lastModified(),
            fingerprint = ArchiveMetadataReader.buildFingerprint(archiveFile),
            sourcePath = sourcePath,
            sourceUrl = sourceUrl,
            nexusGameDomain = nexusInfo?.gameDomain,
            nexusModId = nexusInfo?.modId,
            nexusFileId = nexusInfo?.fileId
        )

        save(existingRecords.filterNot { it.archiveId == record.archiveId } + record)

        return record
    }

    fun findById(archiveId: String?): DownloadedArchiveRecord? {
        if (archiveId.isNullOrBlank()) return null
        return load().firstOrNull { it.archiveId == archiveId }
    }

    fun markInstalled(
        archiveId: String?,
        installedModId: String
    ) {
        if (archiveId.isNullOrBlank()) return

        val updatedRecords = load().map { record ->
            if (record.archiveId == archiveId) {
                record.copy(
                    installedModId = installedModId,
                    installedAtMillis = System.currentTimeMillis()
                )
            } else {
                record
            }
        }

        save(updatedRecords)
    }

    fun buildSummary(): String {
        val records = load()

        if (records.isEmpty()) {
            return "No archives are currently saved in the archive library."
        }

        return buildString {
            appendLine("Archive count: ${records.size}")
            appendLine()

            records
                .sortedByDescending { it.createdAtMillis }
                .forEachIndexed { index, record ->
                    appendLine("${index + 1}. ${record.displayName}")
                    appendLine("   File: ${record.fileName}")
                    appendLine("   Format: ${record.archiveFormat}")
                    appendLine("   Size: ${record.sizeBytes} bytes")
                    appendLine("   Installed mod: ${record.installedModId ?: "not installed"}")

                    if (!record.sourceUrl.isNullOrBlank()) {
                        appendLine("   Source: ${record.sourceUrl}")
                    }

                    if (!record.nexusGameDomain.isNullOrBlank() || record.nexusModId != null) {
                        appendLine(
                            "   Nexus: ${record.nexusGameDomain ?: "unknown"} / ${record.nexusModId ?: "unknown"}"
                        )
                    }

                    appendLine()
                }
        }.trimEnd()
    }

    private fun toJson(record: DownloadedArchiveRecord): JSONObject {
        return JSONObject().apply {
            put("archiveId", record.archiveId)
            put("displayName", record.displayName)
            put("fileName", record.fileName)
            put("archiveFormat", record.archiveFormat)
            put("relativePath", record.relativePath)
            put("sizeBytes", record.sizeBytes)
            put("modifiedAtMillis", record.modifiedAtMillis)
            put("fingerprint", record.fingerprint)
            putNullable("sourcePath", record.sourcePath)
            putNullable("sourceUrl", record.sourceUrl)
            putNullable("nexusGameDomain", record.nexusGameDomain)
            putNullable("nexusModId", record.nexusModId)
            putNullable("nexusFileId", record.nexusFileId)
            putNullable("nexusFileName", record.nexusFileName)
            putNullable("version", record.version)
            putNullable("installedModId", record.installedModId)
            putNullable("installedAtMillis", record.installedAtMillis)
            put("createdAtMillis", record.createdAtMillis)
        }
    }

    private fun fromJson(obj: JSONObject): DownloadedArchiveRecord {
        return DownloadedArchiveRecord(
            archiveId = obj.optString("archiveId"),
            displayName = obj.optString("displayName"),
            fileName = obj.optString("fileName"),
            archiveFormat = obj.optString("archiveFormat"),
            relativePath = obj.optString("relativePath"),
            sizeBytes = obj.optLong("sizeBytes"),
            modifiedAtMillis = obj.optLong("modifiedAtMillis"),
            fingerprint = obj.optString("fingerprint"),
            sourcePath = obj.optNullableString("sourcePath") ?: obj.optNullableString("sourceUri"),
            sourceUrl = obj.optNullableString("sourceUrl"),
            nexusGameDomain = obj.optNullableString("nexusGameDomain"),
            nexusModId = obj.optNullableLong("nexusModId"),
            nexusFileId = obj.optNullableLong("nexusFileId"),
            nexusFileName = obj.optNullableString("nexusFileName"),
            version = obj.optNullableString("version"),
            installedModId = obj.optNullableString("installedModId"),
            installedAtMillis = obj.optNullableLong("installedAtMillis"),
            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
        )
    }

    private fun buildArchiveId(file: File): String {
        val base = "${file.name}|${file.length()}|${file.lastModified()}"
        return base.hashCode().toUInt().toString(16)
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        if (value == null) {
            put(name, JSONObject.NULL)
        } else {
            put(name, value)
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name)
    }
}