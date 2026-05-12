package com.shonkware.droidmodloader.engine.install

import android.util.Log
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

class SevenZipArchiveExtractor : ArchiveExtractor {

    companion object {
        private const val TAG = "DroidModLoader"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MIN_MEMORY_LIMIT_KIB = 96 * 1024
        private const val SAFETY_MARGIN_KIB = 64 * 1024
    }

    override fun supports(archive: File): Boolean {
        return archive.extension.lowercase() == "7z"
    }

    override fun extract(archive: File, tempDir: File, modsDir: File): File {
        Log.d(TAG, "SevenZipArchiveExtractor.extract start: ${archive.absolutePath}")

        val extractFolder = File(tempDir, System.currentTimeMillis().toString())
        if (!extractFolder.exists() && !extractFolder.mkdirs()) {
            throw IOException("Could not create extract folder: ${extractFolder.absolutePath}")
        }

        val modName = archive.nameWithoutExtension
        val finalDir = File(modsDir, modName)

        try {
            Log.d(TAG, "7z temp extract folder: ${extractFolder.absolutePath}")
            extractSevenZip(archive, extractFolder)

            Log.d(TAG, "7z extraction complete. Normalizing extracted structure...")
            val normalizedRoot = normalizeExtractedStructure(extractFolder)
            Log.d(TAG, "Normalized root: ${normalizedRoot.absolutePath}")

            if (finalDir.exists()) {
                Log.d(TAG, "Final mod dir already exists. Deleting: ${finalDir.absolutePath}")
                finalDir.deleteRecursively()
            }

            moveOrCopyNormalizedRoot(normalizedRoot, finalDir)

            Log.d(TAG, "SevenZipArchiveExtractor.extract success: ${finalDir.absolutePath}")
            return finalDir
        } catch (t: Throwable) {
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            throw IOException("7z install failed for ${archive.name}: ${t.message}", t)
        } finally {
            if (extractFolder.exists()) {
                extractFolder.deleteRecursively()
            }
        }
    }

    private fun extractSevenZip(archiveFile: File, outputDir: File) {
        Log.d(TAG, "Opening 7z archive: ${archiveFile.absolutePath}")

        openSevenZFile(archiveFile).use { sevenZ ->
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            var entryCount = 0

            while (entry != null) {
                entryCount++

                val entryName = entry.name ?: throw IOException("7z entry name was null")
                if (entryName.isBlank()) {
                    Log.w(TAG, "Skipping blank 7z entry name at index $entryCount")
                    entry = sevenZ.nextEntry
                    continue
                }

                try {
                    Log.d(
                        TAG,
                        "7z entry #$entryCount: name=$entryName isDirectory=${entry.isDirectory} size=${entry.size}"
                    )

                    val outFile = safeResolve(outputDir, entryName)

                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw IOException("Could not create directory: ${outFile.absolutePath}")
                        }
                    } else {
                        outFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("Could not create parent directory: ${parent.absolutePath}")
                            }
                        }

                        outFile.outputStream().use { fos ->
                            val buffer = ByteArray(BUFFER_SIZE)

                            if (entry.size >= 0) {
                                var remaining = entry.size

                                while (remaining > 0) {
                                    val maxRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = sevenZ.read(buffer, 0, maxRead)

                                    if (read < 0) {
                                        throw IOException("Unexpected end of stream for entry: $entryName")
                                    }

                                    if (read == 0) {
                                        continue
                                    }

                                    fos.write(buffer, 0, read)
                                    remaining -= read
                                }
                            } else {
                                while (true) {
                                    val read = sevenZ.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    fos.write(buffer, 0, read)
                                }
                            }

                            fos.flush()
                        }
                    }
                } catch (t: Throwable) {
                    throw IOException("7z extraction failed on entry '$entryName': ${t.message}", t)
                }

                entry = sevenZ.nextEntry
            }

            Log.d(TAG, "7z archive processed. Entry count=$entryCount")
        }
    }

    private fun openSevenZFile(archiveFile: File): SevenZFile {
        val memoryLimitKiB = computeMemoryLimitKiB()
        Log.d(TAG, "Opening SevenZFile with memoryLimitKiB=$memoryLimitKiB")

        return SevenZFile.builder()
            .setFile(archiveFile)
            .setUseDefaultNameForUnnamedEntries(true)
            .setMaxMemoryLimitKiB(memoryLimitKiB)
            .get()
    }

    private fun computeMemoryLimitKiB(): Int {
        val maxHeapKiB = (Runtime.getRuntime().maxMemory() / 1024L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        return (maxHeapKiB - SAFETY_MARGIN_KIB).coerceAtLeast(MIN_MEMORY_LIMIT_KIB)
    }

    private fun moveOrCopyNormalizedRoot(normalizedRoot: File, finalDir: File) {
        Log.d(TAG, "Moving normalized root into final dir: ${finalDir.absolutePath}")

        if (normalizedRoot.renameTo(finalDir)) {
            Log.d(TAG, "renameTo succeeded for normalized root.")
            return
        }

        Log.d(TAG, "renameTo failed, falling back to copyRecursively.")
        normalizedRoot.copyRecursively(finalDir, overwrite = true)
    }

    private fun safeResolve(root: File, relativePath: String): File {
        val outFile = File(root, relativePath)
        val rootPath = root.canonicalPath + File.separator
        val outPath = outFile.canonicalPath

        if (!outPath.startsWith(rootPath)) {
            throw IOException("Blocked suspicious archive path: $relativePath")
        }

        return outFile
    }

    private fun normalizeExtractedStructure(root: File): File {
        val children = root.listFiles() ?: return root

        if (children.size == 1 && children[0].isDirectory) {
            val child = children[0]
            val dataFolder = File(child, "Data")
            if (dataFolder.exists()) {
                return dataFolder
            }
            return child
        }

        val dataFolder = File(root, "Data")
        if (dataFolder.exists()) {
            return dataFolder
        }

        return root
    }
}