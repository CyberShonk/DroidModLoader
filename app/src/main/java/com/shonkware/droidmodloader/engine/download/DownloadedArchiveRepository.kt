package com.shonkware.droidmodloader.engine.download

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableString
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.shonkware.droidmodloader.engine.install.ArchiveFormatProbe

class DownloadedArchiveRepository internal constructor(
    private val archiveLibraryDir: File,
    private val archiveFormatProbe: ArchiveFormatProbe,
    private val metadataFile: CriticalMetadataFile
) {
    constructor(
        archiveLibraryDir: File,
        archiveListFile: File,
        archiveFormatProbe: ArchiveFormatProbe = ArchiveFormatProbe()
    ) : this(archiveLibraryDir, archiveFormatProbe, CriticalMetadataFile(archiveListFile))

    /** Only an absent file means an empty store. Invalid/unreadable evidence throws. */
    fun load(): List<DownloadedArchiveRecord> {
        val text = metadataFile.read() ?: return emptyList()
        return decode(text)
    }

    internal fun decode(text: String): List<DownloadedArchiveRecord> =
        CriticalMetadataJson.decode("archive records", text) { value ->
            val root = value as JSONArray
            val records = List(root.length()) { index -> fromJson(root.getJSONObject(index)) }
            require(records.map { it.archiveId }.distinct().size == records.size) {
                "Duplicate archive IDs"
            }
            records
        }

    fun save(records: List<DownloadedArchiveRecord>) {
        // Even direct saves must not replace unreadable/corrupt evidence with an empty list.
        load()
        val root = JSONArray()
        records.forEach { record -> root.put(toJson(record)) }
        val text = root.toString(2)
        decode(text)
        metadataFile.replace(text)
    }

    fun registerArchive(
        archiveFile: File,
        originalDisplayName: String,
        sourcePath: String? = null,
        sourceUrl: String? = null
    ): DownloadedArchiveRecord {
        archiveLibraryDir.mkdirs()
        val archiveProbeResult = archiveFormatProbe.probe(archiveFile)

        val existingRecords = load()
        val nexusInfo = sourceUrl?.let { NexusUrlParser.parse(it) }

        val record = DownloadedArchiveRecord(
            archiveId = buildArchiveId(archiveFile),
            displayName = ArchiveMetadataReader.cleanDisplayName(originalDisplayName),
            fileName = archiveFile.name,
            archiveFormat = archiveProbeResult.format.metadataLabel,
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
            return "No archive imports are recorded."
        }

        return buildString {
            appendLine("Archive import records: ${records.size}")
            appendLine("History only; archive copies may no longer exist.")
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
            archiveId = obj.requiredString("archiveId"),
            displayName = obj.requiredString("displayName"),
            fileName = obj.requiredString("fileName"),
            archiveFormat = obj.requiredString("archiveFormat"),
            relativePath = obj.requiredString("relativePath"),
            sizeBytes = obj.requiredLong("sizeBytes"),
            modifiedAtMillis = obj.requiredLong("modifiedAtMillis"),
            fingerprint = obj.requiredString("fingerprint"),
            sourcePath = obj.nullableString("sourcePath") ?: obj.nullableString("sourceUri"),
            sourceUrl = obj.nullableString("sourceUrl"),
            nexusGameDomain = obj.nullableString("nexusGameDomain"),
            nexusModId = obj.nullableLong("nexusModId"),
            nexusFileId = obj.nullableLong("nexusFileId"),
            nexusFileName = obj.nullableString("nexusFileName"),
            version = obj.nullableString("version"),
            installedModId = obj.nullableString("installedModId"),
            installedAtMillis = obj.nullableLong("installedAtMillis"),
            createdAtMillis = obj.requiredLong("createdAtMillis")
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

}