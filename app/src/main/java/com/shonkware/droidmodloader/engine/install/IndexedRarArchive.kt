package com.shonkware.droidmodloader.engine.install

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.File

/** Keeps Junrar's extraction/CRC logic without rebuilding and scanning its header list per file. */
internal class IndexedRarArchive : Archive {
    private var cacheReady = false
    private var headerIndex: List<FileHeader>? = null

    constructor(file: File) : super(file) { cacheReady = true }

    constructor(volumes: VolumeManager, options: ArchiveOptions) : super(volumes, options) {
        cacheReady = true
    }

    override fun getFileHeaders(): List<FileHeader> {
        // Archive constructors and volume parsing may call overridable methods.
        if (!cacheReady) return super.getFileHeaders()
        return headerIndex ?: HeaderIndex(super.getFileHeaders()).also { headerIndex = it }
    }

    override fun setVolume(volume: Volume) {
        val wasReady = cacheReady
        cacheReady = false
        headerIndex = null
        try {
            super.setVolume(volume)
        } finally {
            headerIndex = null
            cacheReady = wasReady
        }
    }

    private class HeaderIndex(private val headers: List<FileHeader>) : AbstractList<FileHeader>() {
        private val positions = HashMap<FileHeader, Int>(headers.size).apply {
            headers.forEachIndexed { index, header -> putIfAbsent(header, index) }
        }
        override val size: Int get() = headers.size
        override fun get(index: Int): FileHeader = headers[index]
        override fun indexOf(element: FileHeader): Int = positions[element] ?: -1
    }
}
