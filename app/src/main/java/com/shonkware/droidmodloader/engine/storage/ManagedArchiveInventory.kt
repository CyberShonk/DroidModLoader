package com.shonkware.droidmodloader.engine.storage

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class ManagedArchiveInventory(
    val uniqueFiles: Int = 0,
    val bytes: Long? = null,
    val duplicates: Int = 0,
    val excluded: Int = 0,
    val complete: Boolean = false,
    val details: List<String> = emptyList()
) {
    fun toSummary(): String {
        val size = when {
            bytes == null -> "unknown"
            complete -> "$bytes bytes"
            else -> "at least $bytes bytes (partial)"
        }
        return "Managed archive inventory: ${if (complete) "complete observation" else "partial or unavailable"}; " +
            "verified unique files: $uniqueFiles; observed logical size: $size; " +
            "duplicate links: $duplicates; excluded/unreadable: $excluded.\n" + details.joinToString(" ")
    }

    companion object {
        /** Metadata only, restricted to a strict descendant of an accessible app-owned base. */
        fun read(
            ownerBase: File?,
            archiveDirectory: File,
            cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
            maxEntries: Int = 2_000,
            maxDepth: Int = 8
        ): ManagedArchiveInventory {
            require(maxEntries > 0 && maxDepth in 1..32)
            val base = ownerBase?.absoluteFile?.toPath()?.normalize()
                ?: return ManagedArchiveInventory(details = listOf("Archive storage base is unavailable."))
            val root = archiveDirectory.absoluteFile.toPath().normalize()
            try {
                require(root != base && root.startsWith(base))
                require(Files.readAttributes(base, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory && base.toFile().canRead())
                var candidate = base
                for (part in base.relativize(root)) {
                    candidate = candidate.resolve(part)
                    val attributes = try {
                        Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    } catch (_: NoSuchFileException) {
                        return ManagedArchiveInventory(bytes = 0L, complete = true,
                            details = listOf("Managed archive directory is absent; import history is not retained-file evidence."))
                    }
                    require(attributes.isDirectory && !attributes.isSymbolicLink)
                }
            } catch (_: Exception) {
                return ManagedArchiveInventory(details = listOf("Archive root is inaccessible, linked or outside its owned base."))
            }
            val canonicalRoot = try {
                root.toFile().canonicalFile.toPath().also {
                    require(it.startsWith(base.toFile().canonicalFile.toPath()))
                }
            } catch (_: Exception) {
                return ManagedArchiveInventory(details = listOf("Archive root changed or became unavailable."))
            }
            val identities = mutableSetOf<Any>()
            val reasons = mutableSetOf<String>()
            var visited = 0
            var files = 0
            var bytes = 0L
            var duplicates = 0
            var excluded = 0
            fun canVisit(): Boolean {
                if (cancelled()) { reasons.add("Inventory cancelled; partial evidence only."); return false }
                if (visited >= maxEntries) { reasons.add("Inventory entry limit reached."); return false }
                visited++
                return true
            }
            val visitor = object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (canVisit()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!canVisit()) return FileVisitResult.TERMINATE
                    if (!attrs.isRegularFile || !file.toFile().canonicalFile.toPath().startsWith(canonicalRoot)) {
                        excluded++
                        reasons.add("Linked, unsupported or depth-limited entries were excluded.")
                    } else if (attrs.fileKey() == null) {
                        excluded++
                        reasons.add("Some file identities are unavailable; no duplicate byte total was invented.")
                    } else if (!identities.add(checkNotNull(attrs.fileKey()))) {
                        duplicates++
                    } else {
                        files++
                        try { bytes = Math.addExact(bytes, attrs.size()) } catch (_: ArithmeticException) {
                            reasons.add("Inventory size overflowed; only a partial byte bound remains.")
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    if (!canVisit()) return FileVisitResult.TERMINATE
                    excluded++
                    reasons.add("Some archive entries were missing or unreadable during observation.")
                    return FileVisitResult.CONTINUE
                }
            }
            try {
                Files.walkFileTree(root, emptySet(), maxDepth + 1, visitor)
            } catch (_: Exception) {
                reasons.add("Archive traversal became unavailable; partial evidence only.")
            }
            return ManagedArchiveInventory(files, if (files > 0 || reasons.isEmpty()) bytes else null,
                duplicates, excluded, reasons.isEmpty(), reasons.toList())
        }
    }
}
