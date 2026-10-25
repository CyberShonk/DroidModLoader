package com.shonkware.droidmodloader.engine.integrity

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

internal data class RecordedContent(
    val path: String,
    val hash: String,
    val sizeBytes: Long? = null,
    val modifiedEpochMillis: Long? = null
)

internal data class IntegrityInput(
    val label: String,
    val identity: String,
    val root: File?,
    val evidenceFile: File,
    val revision: String,
    val records: List<RecordedContent>,
    val unavailable: String? = null,
    val canonicalRootPath: String? = root?.let { runCatching { it.canonicalPath }.getOrNull() }
)

internal data class IntegrityCounts(
    val label: String,
    val identity: String,
    val recorded: Int,
    val checked: Int = 0,
    val missing: Int = 0,
    val changed: Int = 0,
    val unreadable: Int = 0,
    val unavailable: Int = 0,
    val rootStatus: String = "Available",
    val evidenceProblem: String? = null
) {
    val complete: Boolean
        get() = rootStatus == "Available" && evidenceProblem == null &&
            unreadable == 0 && unavailable == 0 && checked + missing == recorded
}

internal data class ManagedIntegrityReport(
    val observedAtEpochMillis: Long,
    val inputDigest: String,
    val groups: List<IntegrityCounts>,
    val cancelled: Boolean,
    val stale: Boolean
) {
    fun toSummary(): String = buildString {
        appendLine("Historical read-only observation at $observedAtEpochMillis (epoch ms).")
        appendLine("Input digest: $inputDigest")
        appendLine("Status: ${when { stale -> "STALE — inputs changed; rerun required"; cancelled -> "CANCELLED / PARTIAL"; groups.all { it.complete } -> "COMPLETE"; else -> "PARTIAL / UNAVAILABLE" }}")
        groups.forEach { group ->
            appendLine("${group.label}: ${group.identity}")
            appendLine("  Root: ${group.rootStatus}; recorded=${group.recorded}, checked=${group.checked}, missing=${group.missing}, changed=${group.changed}, unreadable=${group.unreadable}, unavailable=${group.unavailable}")
            group.evidenceProblem?.let { appendLine("  Evidence unavailable: $it") }
        }
        appendLine("Checked includes matching and changed hashes. No unrecorded files were scanned.")
        appendLine("Not live health or compatibility proof. Rerun after any content, profile or target change.")
    }
}

/** Bounded evidence reads and streaming hashes; never builds or replaces expected evidence. */
internal object ManagedContentIntegrity {
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    const val MAX_RECORDED_FILES = 50_000
    private val hashPattern = Regex("[a-fA-F0-9]{64}")

    fun hasRecordedHash(hash: String): Boolean = hashPattern.matches(hash)

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun readEvidence(file: File): String = readEvidenceBytes(file).toString(Charsets.UTF_8)

    fun readEvidenceBytes(file: File): ByteArray =
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { it.readBytesBounded() }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_METADATA_BYTES) throw IOException("Metadata exceeds 8 MiB limit")
            output.write(buffer, 0, count)
        }
    }

    fun revision(file: File): String = try {
        digest(readEvidence(file).toByteArray(Charsets.UTF_8))
    } catch (_: NoSuchFileException) {
        "missing"
    } catch (_: IOException) {
        if (Files.notExists(file.toPath())) "missing" else "unreadable"
    } catch (_: SecurityException) {
        "unreadable"
    }

    fun capture(
        label: String,
        identity: String,
        root: File?,
        evidenceFile: File,
        parse: (String) -> List<RecordedContent>
    ): IntegrityInput {
        return try {
            val text = readEvidence(evidenceFile)
            val records = parse(text)
            require(records.map { it.path.lowercase(java.util.Locale.ROOT) }.distinct().size == records.size) {
                "Duplicate recorded paths"
            }
            records.forEach { requireSafePath(it.path) }
            IntegrityInput(label, identity, root, evidenceFile, digest(text.toByteArray(Charsets.UTF_8)), records)
        } catch (failure: Exception) {
            IntegrityInput(label, identity, root, evidenceFile, revision(evidenceFile), emptyList(),
                evidenceProblem(evidenceFile, failure))
        }
    }

    fun evidenceProblem(file: File, failure: Exception): String = when {
        Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> "Recorded metadata is missing"
        failure is IOException -> "Recorded metadata is unreadable or exceeds the 8 MiB evidence limit"
        failure is SecurityException -> "Recorded metadata access denied"
        else -> "Recorded metadata is invalid, unsafe, unsupported or exceeds the recorded-entry limit"
    }

    fun manifestRecords(text: String): List<RecordedContent> {
        val array = JSONArray(text)
        require(array.length() <= MAX_RECORDED_FILES) { "Recorded file limit exceeded" }
        return (0 until array.length()).map { index ->
            val record = array.getJSONObject(index)
            RecordedContent(record.getString("normalizedPath"), record.opt("hash") as? String ?: "")
        }
    }

    fun verify(
        capture: () -> List<IntegrityInput>,
        extraRevision: () -> String,
        cancelled: () -> Boolean
    ): ManagedIntegrityReport {
        val extra = extraRevision()
        val inputs = capture()
        val before = inputDigest(inputs, extra)
        val counts = inputs.map { check(it, cancelled) }
        val stale = before != inputDigest(capture(), extraRevision())
        return ManagedIntegrityReport(System.currentTimeMillis(), before, counts, cancelled(), stale)
    }

    fun indexRecords(text: String, modId: String, installPath: String): List<RecordedContent> {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == 1) { "Unsupported index schema" }
        require(json.getString("modId") == modId && json.getString("modRootPath") == installPath) {
            "Index identity mismatch"
        }
        val array = json.getJSONArray("entries")
        require(array.length() <= MAX_RECORDED_FILES) { "Recorded file limit exceeded" }
        return (0 until array.length()).map { index ->
            val record = array.getJSONObject(index)
            val normalized = record.getString("normalizedPath")
            val original = record.getString("originalPath")
            requireSafePath(normalized)
            require(com.shonkware.droidmodloader.engine.util.PathUtils.normalize(original) == normalized) {
                "Index path identity mismatch"
            }
            RecordedContent(original, record.opt("hash") as? String ?: "",
                record.optLong("sizeBytes", -1).takeIf { it >= 0 },
                record.optLong("modifiedEpochMillis", -1).takeIf { it >= 0 })
        }
    }

    private fun requireSafePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe recorded path" }
    }

    fun inputDigest(inputs: List<IntegrityInput>, extra: String): String = digest(
        (extra + inputs.joinToString("") { input ->
            listOf(input.label, input.identity, input.root?.absolutePath, input.canonicalRootPath,
                input.evidenceFile.absolutePath, input.revision, input.unavailable).joinToString("\u0000", postfix = "\u0000")
        }).toByteArray(Charsets.UTF_8)
    )

    fun resolveRecordedFile(input: IntegrityInput, record: RecordedContent): File {
        val root = checkNotNull(input.root)
        requireSafePath(record.path)
        require(input.canonicalRootPath != null && root.canonicalPath == input.canonicalRootPath &&
            !Files.isSymbolicLink(root.toPath())) { "Recorded root identity changed" }
        val rootPath = root.canonicalFile.toPath()
        val file = File(root, record.path)
        val canonical = file.canonicalFile.toPath()
        require(canonical != rootPath && canonical.startsWith(rootPath)) { "Path escapes recorded root" }
        var path = root.toPath()
        record.path.split('/').forEach { part ->
            path = path.resolve(part)
            require(!Files.isSymbolicLink(path)) { "Aliased recorded path" }
        }
        return file
    }

    fun check(input: IntegrityInput, cancelled: () -> Boolean): IntegrityCounts {
        var counts = IntegrityCounts(input.label, input.identity, input.records.size, evidenceProblem = input.unavailable)
        if (cancelled()) return counts.copy(rootStatus = "Not checked (cancelled)")
        val root = input.root ?: return counts.copy(rootStatus = "Unavailable")
        try {
            require(input.canonicalRootPath != null && root.canonicalPath == input.canonicalRootPath &&
                !Files.isSymbolicLink(root.toPath())) { "Recorded root identity changed" }
            val attributes = Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            if (!attributes.isDirectory) return counts.copy(rootStatus = "Not a directory")
            if (!Files.isReadable(root.toPath()) || !Files.isExecutable(root.toPath())) {
                return counts.copy(rootStatus = "Unreadable")
            }
        } catch (_: NoSuchFileException) {
            return counts.copy(rootStatus = "Missing")
        } catch (_: Exception) {
            return counts.copy(rootStatus = "Unreadable")
        }
        if (input.unavailable != null) return counts
        for (record in input.records) {
            if (cancelled()) break
            try {
                val file = resolveRecordedFile(input, record)
                val canonical = file.canonicalFile.toPath()
                val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!attributes.isRegularFile) {
                    counts = counts.copy(unreadable = counts.unreadable + 1)
                } else if (!hashPattern.matches(record.hash)) {
                    counts = counts.copy(unavailable = counts.unavailable + 1)
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (!cancelled()) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    if (cancelled()) break
                    val after = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (attributes.size() != after.size() || attributes.lastModifiedTime() != after.lastModifiedTime() ||
                        attributes.fileKey() != after.fileKey() || file.canonicalFile.toPath() != canonical) {
                        counts = counts.copy(unreadable = counts.unreadable + 1)
                    } else {
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        counts = counts.copy(checked = counts.checked + 1,
                            changed = counts.changed + if (actual.equals(record.hash, ignoreCase = true)) 0 else 1)
                    }
                }
            } catch (_: NoSuchFileException) {
                counts = counts.copy(missing = counts.missing + 1)
            } catch (_: Exception) {
                counts = counts.copy(unreadable = counts.unreadable + 1)
            }
        }
        return counts
    }
}
