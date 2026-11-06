package com.shonkware.droidmodloader.engine.plugins

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class DataFolderPluginScanner internal constructor(
    private val openDirectory: (Path) -> DirectoryStream<Path>
) {
    constructor() : this({ Files.newDirectoryStream(it) })

    /** Bounded physical scope only. It never falls back to a simulated Data directory. */
    fun observePhysicalData(
        gameId: String,
        profileId: String,
        snapshotId: String,
        dataFolder: File,
        request: PluginHeaderRequest
    ): PluginSourceObservations {
        val observations = mutableListOf<PluginHeaderObservation>()
        val identities = mutableSetOf<String>()
        var failure: PluginFactFailure? = null
        try {
            request.checkCancellation()
            val root = dataFolder.toPath().toAbsolutePath().normalize()
            if (root.toRealPath() != root) throw PluginFactException(PluginFactFailure.UNSAFE_SOURCE)
            val before = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!before.isDirectory) throw PluginFactException(PluginFactFailure.UNSAFE_SOURCE)
            openDirectory(root).use { entries ->
                val iterator = entries.iterator()
                while (true) {
                    request.checkCancellation()
                    // At the cap only look for exhaustion. Do not retrieve/stat an extra entry.
                    if (!iterator.hasNext()) break
                    request.takeDataEntry()
                    val path = iterator.next()
                    if (!isPluginName(path.fileName.toString())) continue
                    val source = PluginHeaderSource(
                        profileId, snapshotId, PluginSourceScope.PHYSICAL_DATA,
                        root.toString(), path.toString()
                    )
                    if (path.parent != root) throw PluginFactException(PluginFactFailure.UNSAFE_SOURCE)
                    PluginHeaderReader.validateSource(source)
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attributes.isDirectory) continue
                    if (!attributes.isRegularFile) throw PluginFactException(PluginFactFailure.UNSAFE_SOURCE)
                    if (!identities.add(pluginIdentity(path.fileName.toString()))) {
                        throw PluginFactException(PluginFactFailure.AMBIGUOUS_IDENTITY)
                    }
                    val observation = PluginHeaderReader().read(gameId, source, request)
                    if (observation.failure == PluginFactFailure.CANDIDATE_LIMIT || observation.failure == PluginFactFailure.CANCELED) {
                        throw PluginFactException(observation.failure)
                    }
                    observations.add(observation)
                }
            }
            val after = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (root.toRealPath() != root || !after.isDirectory || before.fileKey() != after.fileKey() ||
                before.lastModifiedTime() != after.lastModifiedTime()) {
                failure = PluginFactFailure.SOURCE_CHANGED
            }
        } catch (exception: PluginFactException) {
            failure = exception.failure
        } catch (_: IOException) {
            failure = PluginFactFailure.IO_ERROR
        } catch (_: DirectoryIteratorException) {
            failure = PluginFactFailure.IO_ERROR
        } catch (_: java.nio.file.InvalidPathException) {
            failure = PluginFactFailure.UNSAFE_SOURCE
        } catch (_: SecurityException) {
            failure = PluginFactFailure.IO_ERROR
        }
        return PluginSourceObservations(immutablePluginFacts(observations), failure)
    }

    fun scanLocalDataFolder(dataFolder: File): List<String> {
        if (!dataFolder.exists() || !dataFolder.isDirectory) return emptyList()

        return dataFolder.listFiles()
            ?.filter { it.isFile && isPluginName(it.name) }
            ?.map { it.name }
            ?.sortedWith(pluginNameComparator())
            ?: emptyList()
    }

    private fun isPluginName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".esm") ||
                lower.endsWith(".esp") ||
                lower.endsWith(".esl")
    }

    private fun pluginNameComparator(): Comparator<String> {
        return compareBy<String> {
            when {
                it.lowercase().endsWith(".esm") -> 0
                it.lowercase().endsWith(".esl") -> 1
                else -> 2
            }
        }.thenBy { it.lowercase() }
    }
}
