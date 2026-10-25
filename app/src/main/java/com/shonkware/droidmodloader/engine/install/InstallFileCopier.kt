package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.storage.AndroidStorageCapacityProbe
import java.io.File
import java.io.IOException

internal object InstallFileCopier {
    private const val BUFFER_SIZE = 64 * 1024

    fun copyRecursively(
        source: File,
        target: File,
        cancellationSignal:
        InstallCancellationSignal =
            InstallCancellationSignal.NONE
    ) = copyRecursively(
        source, target, cancellationSignal,
        usableSpaceBytes = AndroidStorageCapacityProbe::availableBytes
    )

    internal fun copyRecursively(
        source: File,
        target: File,
        cancellationSignal: InstallCancellationSignal,
        spaceCheckIntervalBytes: Long = ExtractionLimits.DEFAULT_SPACE_CHECK_INTERVAL_BYTES,
        usableSpaceBytes: (File) -> Long?
    ) {
        require(spaceCheckIntervalBytes > 0L)
        cancellationSignal
            .throwIfCancellationRequested()

        when {
            source.isDirectory -> {
                copyDirectory(source, target, cancellationSignal, spaceCheckIntervalBytes, usableSpaceBytes)
            }

            source.isFile -> {
                copyFile(source, target, cancellationSignal, spaceCheckIntervalBytes, usableSpaceBytes)
            }

            else -> {
                throw IOException(
                    "Installer source does not exist or " +
                            "has an unsupported type: " +
                            source.absolutePath
                )
            }
        }
    }

    private fun copyDirectory(
        source: File,
        target: File,
        cancellationSignal: InstallCancellationSignal,
        spaceCheckIntervalBytes: Long,
        usableSpaceBytes: (File) -> Long?
    ) {
        cancellationSignal
            .throwIfCancellationRequested()

        if (target.exists() && !target.isDirectory) {
            throw IOException(
                "Installer destination is not a directory: " +
                        target.absolutePath
            )
        }

        if (!target.exists() && !target.mkdirs()) {
            throw IOException(
                "Could not create installer destination: " +
                        target.absolutePath
            )
        }

        val children = source.listFiles()
            ?: throw IOException(
                "Could not read installer source directory: " +
                        source.absolutePath
            )

        children.forEach { child ->
            cancellationSignal
                .throwIfCancellationRequested()

            copyRecursively(
                source = child,
                target = File(target, child.name),
                cancellationSignal = cancellationSignal,
                spaceCheckIntervalBytes = spaceCheckIntervalBytes,
                usableSpaceBytes = usableSpaceBytes
            )
        }
    }

    private fun copyFile(
        source: File,
        target: File,
        cancellationSignal: InstallCancellationSignal,
        spaceCheckIntervalBytes: Long,
        usableSpaceBytes: (File) -> Long?
    ) {
        cancellationSignal
            .throwIfCancellationRequested()

        if (target.exists() && target.isDirectory) {
            throw IOException(
                "Installer file destination is a directory: " +
                        target.absolutePath
            )
        }

        val parent = target.parentFile
            ?: throw IOException(
                "Installer destination has no parent: " +
                        target.absolutePath
            )

        // Check before opening the destination, so rejection cannot truncate it.
        var probePath = parent
        while (!probePath.exists() && probePath.parentFile != null) {
            probePath = probePath.parentFile!!
        }
        fun requireSpace(additionalBytes: Long) {
            val available = usableSpaceBytes(probePath)?.takeIf { it >= 0L } ?: return
            val headroom = ExtractionLimits.DEFAULT_MINIMUM_FREE_SPACE_BYTES
            if (available < headroom || additionalBytes > available - headroom) {
                throw IOException("Not enough free storage remains to copy the installer file safely.")
            }
        }
        requireSpace(source.length())
        cancellationSignal.throwIfCancellationRequested()

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException(
                "Could not create installer destination parent: " +
                        parent.absolutePath
            )
        }

        try {
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesSinceSpaceCheck = 0L

                    while (true) {
                        cancellationSignal
                            .throwIfCancellationRequested()

                        val read = input.read(buffer)

                        if (read < 0) {
                            break
                        }

                        if (read == 0) {
                            continue
                        }

                        cancellationSignal
                            .throwIfCancellationRequested()

                        if (bytesSinceSpaceCheck >= spaceCheckIntervalBytes) {
                            requireSpace(read.toLong())
                            bytesSinceSpaceCheck = 0L
                        }
                        output.write(buffer, 0, read)
                        bytesSinceSpaceCheck += read
                    }
                }
            }
        } catch (exception: Exception) {
            val cleanupFailure =
                removePartialFile(target)

            cleanupFailure?.let(
                exception::addSuppressed
            )

            throw exception
        }
    }

    private fun removePartialFile(
        target: File
    ): IOException? {
        if (!target.exists()) {
            return null
        }

        return try {
            if (target.delete()) {
                null
            } else {
                IOException(
                    "Could not remove partial installer file: " +
                            target.absolutePath
                )
            }
        } catch (exception: Exception) {
            IOException(
                "Could not remove partial installer file: " +
                        target.absolutePath,
                exception
            )
        }
    }
}