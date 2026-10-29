package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.model.DeployScope
import com.shonkware.droidmodloader.engine.rules.DeployFileClassifier
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.Locale

/** Paths are relative to the full extracted archive, not the automatically resolved root. */
data class ManualLayoutProposal(
    val rootPath: String = "",
    val excludedPaths: Set<String> = emptySet()
) {
    fun selectRoot(path: String) = ManualLayoutProposal(path)

    fun excludeSubtree(path: String) = copy(excludedPaths =
        excludedPaths.filterNot { below(it, path) }.toSet() + path)

    fun includeSubtree(path: String): ManualLayoutProposal {
        if (excludedPaths.any { it != path && below(path, it) }) {
            throw IOException("Include the excluded parent folder first.")
        }
        return copy(excludedPaths = excludedPaths.filterNot { below(it, path) }.toSet())
    }
}

data class ManualLayoutEntry(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
)

data class ManualLayoutFile(
    val sourcePath: String,
    val destinationPath: String,
    val sizeBytes: Long,
    val deployScope: DeployScope,
    val modifiedAtMillis: Long
)

data class ManualLayoutPreview(
    val files: List<ManualLayoutFile>,
    val totalBytes: Long,
    /** Generic layout recognition only, not selected-game compatibility or safety. */
    val recognizedLayout: Boolean
)

/** A bounded metadata observation of an owned extracted session. Never an archive decode. */
data class ManualLayoutInventory(
    val sessionRootPath: String,
    val extractedRootPath: String,
    val entries: List<ManualLayoutEntry>,
    private val limits: ExtractionLimits = ExtractionLimits()
) {
    fun preview(
        proposal: ManualLayoutProposal,
        cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE
    ): ManualLayoutPreview {
        cancellationSignal.throwIfCancellationRequested()
        val byPath = validateEntries(cancellationSignal)
        if (proposal.rootPath.isNotEmpty()) {
            checkedPath(proposal.rootPath, limits)
            if (byPath[proposal.rootPath]?.directory != true) {
                throw IOException("Choose a folder from this prepared archive.")
            }
        }
        if (proposal.excludedPaths.size > limits.maxEntries) throw IOException("Too many excluded paths.")
        proposal.excludedPaths.forEach { path ->
            checkedPath(path, limits)
            if (!below(path, proposal.rootPath) || path !in byPath) {
                throw IOException("Excluded content is outside the chosen folder or no longer exists.")
            }
        }
        val classifier = DeployFileClassifier()
        val destinations = hashSetOf<String>()
        var total = 0L
        val files = entries.asSequence().filter { !it.directory }.filter { entry ->
            below(entry.path, proposal.rootPath) && !isExcluded(entry.path, proposal.excludedPaths)
        }.sortedBy { it.path }.map { entry ->
            cancellationSignal.throwIfCancellationRequested()
            val destination = if (proposal.rootPath.isEmpty()) entry.path
                else entry.path.removePrefix(proposal.rootPath + "/")
            checkedPath(destination, limits)
            if (!destinations.add(comparisonKey(destination))) {
                throw IOException("Selected files have conflicting destination paths: $destination")
            }
            total = boundedTotal(total, entry.sizeBytes, limits.maxTotalBytes)
            ManualLayoutFile(entry.path, destination, entry.sizeBytes, classifier.classify(destination), entry.modifiedAtMillis)
        }.toList()
        if (files.isEmpty()) throw IOException("Choose at least one file to install.")
        return ManualLayoutPreview(files, total,
            InstallerLayoutAnalyzer().hasRecognizedGamePaths(files.map { it.destinationPath }))
    }

    /** Re-enumeration catches missing, changed and added entries without hashing file contents. */
    fun requireCurrent(cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE) {
        val current = capture(File(sessionRootPath), File(extractedRootPath), cancellationSignal, limits)
        if (current != this) throw IOException("Prepared archive files changed. Import the archive again.")
    }

    internal fun sourceFile(entry: ManualLayoutFile): File {
        val root = checkedRoots(File(sessionRootPath), File(extractedRootPath))
        val file = ArchiveEntryPath.safeResolve(root, entry.sourcePath)
        // Check each component without following links, including links within the session.
        var current = root.toPath()
        for (part in entry.sourcePath.split('/')) {
            current = current.resolve(part)
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                throw IOException("Prepared archive contains a link or unsupported file.")
            }
        }
        // Use capture's no-follow metadata API so timestamp precision is compared
        // consistently, rather than mixing java.io.File and BasicFileAttributes.
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() != entry.sizeBytes ||
            attributes.lastModifiedTime().toMillis() != entry.modifiedAtMillis) {
            throw IOException("Prepared archive file changed. Import the archive again.")
        }
        return file
    }

    private fun validateEntries(signal: InstallCancellationSignal): Map<String, ManualLayoutEntry> {
        if (entries.size > limits.maxEntries) throw IOException("Prepared archive has too many entries.")
        val byPath = hashMapOf<String, ManualLayoutEntry>()
        val comparisonPaths = hashSetOf<String>()
        var total = 0L
        entries.forEach { entry ->
            signal.throwIfCancellationRequested()
            checkedPath(entry.path, limits)
            if (byPath.put(entry.path, entry) != null || !comparisonPaths.add(comparisonKey(entry.path))) {
                throw IOException("Prepared archive has conflicting paths: ${entry.path}")
            }
            if (entry.sizeBytes < 0L || entry.sizeBytes > limits.maxFileBytes || (entry.directory && entry.sizeBytes != 0L)) {
                throw IOException("Prepared archive entry exceeds size limits: ${entry.path}")
            }
            total = boundedTotal(total, entry.sizeBytes, limits.maxTotalBytes)
        }
        entries.forEach { entry ->
            var parent = entry.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (byPath[parent]?.directory != true) {
                    throw IOException("Prepared archive has a missing or conflicting parent folder: $parent")
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return byPath
    }

    companion object {
        const val MAX_PATH_DEPTH = 64

        fun capture(
            sessionRoot: File,
            extractedRoot: File,
            cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE,
            limits: ExtractionLimits = ExtractionLimits()
        ): ManualLayoutInventory {
            cancellationSignal.throwIfCancellationRequested()
            val root = checkedRoots(sessionRoot, extractedRoot)
            val entries = mutableListOf<ManualLayoutEntry>()
            val pending = ArrayDeque<File>()
            pending.add(root)
            var total = 0L
            while (pending.isNotEmpty()) {
                cancellationSignal.throwIfCancellationRequested()
                val directory = pending.removeLast()
                val directoryAttributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!directoryAttributes.isDirectory || directoryAttributes.isSymbolicLink) {
                    throw IOException("Prepared archive folder changed during the check.")
                }
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (child in children) {
                        cancellationSignal.throwIfCancellationRequested()
                        val relative = root.toPath().relativize(child).toString().replace(File.separatorChar, '/')
                        checkedPath(relative, limits)
                        if (entries.size >= limits.maxEntries) throw IOException("Prepared archive has too many entries.")
                        val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                            throw IOException("Prepared archive contains a link or unsupported file: $relative")
                        }
                        ArchiveEntryPath.safeResolve(root, relative)
                        val size = if (attributes.isDirectory) 0L else attributes.size()
                        if (size < 0L || size > limits.maxFileBytes) throw IOException("Prepared archive file exceeds size limits.")
                        total = boundedTotal(total, size, limits.maxTotalBytes)
                        entries.add(ManualLayoutEntry(relative, attributes.isDirectory, size,
                            attributes.lastModifiedTime().toMillis()))
                        if (attributes.isDirectory) pending.add(child.toFile())
                    }
                }
            }
            return ManualLayoutInventory(sessionRoot.absolutePath, root.absolutePath,
                entries.sortedBy { it.path }, limits).also { it.validateEntries(cancellationSignal) }
        }
    }
}

private fun below(path: String, root: String): Boolean =
    root.isEmpty() || path == root || path.startsWith(root + "/")

private fun isExcluded(path: String, excluded: Set<String>): Boolean {
    var current = path
    while (current.isNotEmpty()) {
        if (current in excluded) return true
        current = current.substringBeforeLast('/', "")
    }
    return false
}

private fun comparisonKey(path: String): String =
    Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private fun checkedPath(path: String, limits: ExtractionLimits) {
    val normalized = ArchiveEntryPath.normalize(path) as? ArchiveEntryPathResult.Valid
    if (normalized?.relativePath != path || path.length > limits.maxRelativePathCharacters ||
        path.count { it == '/' } >= ManualLayoutInventory.MAX_PATH_DEPTH) {
        throw IOException("Invalid or excessive prepared archive path: $path")
    }
}

private fun boundedTotal(total: Long, size: Long, maximum: Long): Long {
    if (size < 0L || size > maximum - total) throw IOException("Prepared archive exceeds the total size limit.")
    return total + size
}

private fun checkedRoots(sessionRoot: File, extractedRoot: File): File {
    val session = sessionRoot.absoluteFile.toPath().normalize()
    val root = extractedRoot.absoluteFile.toPath().normalize()
    if (root == session || !root.startsWith(session) || session.toFile().canonicalFile.toPath() != session) {
        throw IOException("Prepared archive is outside its installer session.")
    }
    var current = session
    val components = listOf(session) + session.relativize(root).map { part ->
        current = current.resolve(part)
        current
    }
    for (component in components) {
        val attributes = Files.readAttributes(component, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw IOException("Prepared archive folder is missing or is a link.")
        }
    }
    return root.toFile()
}
