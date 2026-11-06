package com.shonkware.droidmodloader.engine.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataFolderPluginScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun SRC04_emptyPhysicalFolderIsNotUnavailableTarget() {
        val scanner = DataFolderPluginScanner()
        val empty = temporaryFolder.newFolder("EmptyData")
        val result = scanner.observePhysicalData("fallout_nv", "p", "s", empty, PluginHeaderRequest())
        assertTrue(result.enumerationComplete)
        assertTrue(result.observations.isEmpty())
        assertEquals(PluginFactFailure.IO_ERROR, scanner.observePhysicalData(
            "fallout_nv", "p", "s", empty.resolve("Missing"), PluginHeaderRequest()
        ).enumerationFailure)
        val denied = DataFolderPluginScanner { throw IOException("unavailable") }
        assertEquals(PluginFactFailure.IO_ERROR, denied.observePhysicalData(
            "fallout_nv", "p", "s", empty, PluginHeaderRequest()
        ).enumerationFailure)
    }

    @Test
    fun BUD11_13_allEntriesCountBeforeFilteringAndNoExtraEntryIsRetrieved() {
        val root = temporaryFolder.newFolder("Data")
        val request = PluginHeaderRequest()
        var retrieved = 0
        val scanner = DataFolderPluginScanner { path -> entries(8192) {
            retrieved++
            path.resolve("readme$it.txt")
        } }
        val exact = scanner.observePhysicalData("fallout_nv", "p", "s", root, request)
        assertTrue(exact.enumerationComplete)
        assertTrue(exact.observations.isEmpty())
        assertEquals(8192, retrieved)
        assertEquals(8192, request.dataEntriesProcessed)
        val overflowScanner = DataFolderPluginScanner { path -> entries(8193) {
            if (it == 8192) throw AssertionError("extra entry retrieved")
            path.resolve("readme$it.txt")
        } }
        val overflow = overflowScanner.observePhysicalData("fallout_nv", "p", "s", root, PluginHeaderRequest())
        assertEquals(PluginFactFailure.DATA_ENTRY_LIMIT, overflow.enumerationFailure)
        assertTrue(overflow.observations.isEmpty())
        val exhaustedScanner = DataFolderPluginScanner { path -> entries(1) {
            throw AssertionError("exhausted request retrieved/stat a plugin: $path")
        } }
        assertEquals(PluginFactFailure.DATA_ENTRY_LIMIT,
            exhaustedScanner.observePhysicalData("fallout_nv", "p", "s", root, request).enumerationFailure)
        assertEquals(0, request.candidatesRead)
    }

    @Test
    fun BUD12_14_sharedRootsCancellationAndIteratorFailure() {
        val first = temporaryFolder.newFolder("DataOne")
        val second = temporaryFolder.newFolder("DataTwo")
        val request = PluginHeaderRequest()
        val scanner = DataFolderPluginScanner { path -> entries(4096) { path.resolve("entry$it.txt") } }
        assertTrue(scanner.observePhysicalData("fallout_nv", "p", "s", first, request).enumerationComplete)
        assertTrue(scanner.observePhysicalData("fallout_nv", "p", "s", second, request).enumerationComplete)
        assertEquals(PluginFactFailure.DATA_ENTRY_LIMIT,
            scanner.observePhysicalData("fallout_nv", "p", "s", first, request).enumerationFailure)
        lateinit var canceled: PluginHeaderRequest
        canceled = PluginHeaderRequest { canceled.dataEntriesProcessed == 2 }
        assertEquals(PluginFactFailure.CANCELED,
            scanner.observePhysicalData("fallout_nv", "p", "s", first, canceled).enumerationFailure)
        assertEquals(2, canceled.dataEntriesProcessed)
        first.resolve("Actual.esp").writeBytes(PluginHeaderFixtures.opening())
        var closed = false
        val failing = DataFolderPluginScanner { path -> object : DirectoryStream<Path> {
            override fun close() { closed = true }
            override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
                var index = 0
                override fun hasNext(): Boolean {
                    if (index == 1) throw DirectoryIteratorException(IOException("lost enumeration"))
                    return true
                }
                override fun next(): Path { index++; return path.resolve("Actual.esp") }
                override fun remove() = throw UnsupportedOperationException()
            }
        } }
        val partial = failing.observePhysicalData("fallout_nv", "p", "s", first, PluginHeaderRequest())
        assertEquals(PluginFactFailure.IO_ERROR, partial.enumerationFailure)
        assertEquals(1, partial.observations.size)
        assertTrue(closed)
        val vanished = DataFolderPluginScanner { path -> entries(1) { path.resolve("Missing.esp") } }
        assertEquals(PluginFactFailure.IO_ERROR,
            vanished.observePhysicalData("fallout_nv", "p", "s", first, PluginHeaderRequest()).enumerationFailure)
    }

    private fun entries(count: Int, entry: (Int) -> Path): DirectoryStream<Path> = object : DirectoryStream<Path> {
        override fun close() = Unit
        override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
            var index = 0
            override fun hasNext() = index < count
            override fun next(): Path = entry(index++)
            override fun remove() = throw UnsupportedOperationException()
        }
    }

    @Test
    fun scansDirectDataFolderAndOrdersPluginTypes() {
        val dataDir = temporaryFolder.newFolder("Data")
        dataDir.resolve("Zeta.esp").writeText("esp")
        dataDir.resolve("Base.esm").writeText("esm")
        dataDir.resolve("Light.esl").writeText("esl")
        dataDir.resolve("Alpha.ESP").writeText("esp")
        dataDir.resolve("readme.txt").writeText("text")
        dataDir.resolve("nested").mkdir()

        val result = DataFolderPluginScanner().scanLocalDataFolder(dataDir)

        assertEquals(
            listOf("Base.esm", "Light.esl", "Alpha.ESP", "Zeta.esp"),
            result
        )
    }

    @Test
    fun missingDataFolderReturnsEmptyList() {
        val missing = temporaryFolder.root.resolve("missing")

        assertEquals(emptyList<String>(), DataFolderPluginScanner().scanLocalDataFolder(missing))
    }
}
