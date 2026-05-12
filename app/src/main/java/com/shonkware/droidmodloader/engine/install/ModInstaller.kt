package com.shonkware.droidmodloader.engine.install

import android.util.Log
import java.io.File
import java.io.IOException

class ModInstaller(
    private val tempDir: File,
    private val modsDir: File,
    private val registry: ArchiveExtractorRegistry = ArchiveExtractorRegistry()
) {

    companion object {
        private const val TAG = "DroidModLoader"
    }

    fun installArchive(archive: File): File {
        Log.d(TAG, "ModInstaller.installArchive start: ${archive.absolutePath}")
        Log.d(TAG, "Archive exists=${archive.exists()} size=${archive.length()} extension=${archive.extension}")

        val extractor = registry.findExtractor(archive)
        Log.d(TAG, "Using extractor: ${extractor::class.java.simpleName}")

        return try {
            extractor.extract(archive, tempDir, modsDir)
        } catch (t: Throwable) {
            throw IOException(
                "Archive install failed for ${archive.name} using ${extractor::class.java.simpleName}: ${t.message}",
                t
            )
        }
    }
}