package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.FileRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PluginDiscoveryTest {

    private val discovery = PluginDiscovery()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun SRC01_03_capturedActualWinnerNeverLosingOrPhysicalCopy() {
        val low = temporaryFolder.newFolder("ModLow").resolve("ExAmPlE.ESP")
        low.writeBytes(PluginHeaderFixtures.opening(PluginHeaderFixtures.hedr + PluginHeaderFixtures.baseMaster + PluginHeaderFixtures.data, flags = 1))
        val highRoot = temporaryFolder.newFolder("ModHigh")
        val high = highRoot.resolve("Data").apply { mkdir() }.resolve("ExAmPlE.ESP")
        high.writeBytes(PluginHeaderFixtures.opening())
        fun capture(file: java.io.File, provider: String) = CapturedPluginWinners(
            "p", "snapshot", listOf(FileRecord("example.esp", provider, provider, file.absolutePath, "unused")),
            mapOf("high" to highRoot.absolutePath, "low" to low.parentFile.absolutePath), true
        )
        val highResult = discovery.observeCapturedWinners("fallout_nv", capture(high, "high"), PluginHeaderRequest())
        assertTrue(highResult.enumerationComplete)
        val observation = highResult.observations.single()
        assertTrue(observation.complete)
        assertEquals(high.absolutePath, observation.source.sourceFilePath)
        assertEquals(0L, observation.recordHeader!!.flags)
        assertEquals(emptyList<String>(), observation.declaredMasters)
        val lowResult = discovery.observeCapturedWinners("fallout_nv", capture(low, "low"), PluginHeaderRequest()).observations.single()
        assertEquals(1L, lowResult.recordHeader!!.flags)
        assertEquals(listOf("Base.esm"), lowResult.declaredMasters)
        val data = temporaryFolder.newFolder("PhysicalData")
        data.resolve("Example.esp").writeBytes(low.readBytes())
        val physical = DataFolderPluginScanner().observePhysicalData("fallout_nv", "p", "snapshot", data, PluginHeaderRequest()).observations.single()
        assertEquals(PluginSourceScope.PHYSICAL_DATA, physical.source.scope)
        assertEquals(PluginSourceScope.MANAGED_CANDIDATE, observation.source.scope)
        assertEquals(listOf("Base.esm"), physical.declaredMasters)
        assertEquals(emptyList<String>(), observation.declaredMasters)
    }

    @Test
    fun SRC05_07_incompleteCaptureHasNoReadsAndNestedPayloadIsNotPromoted() {
        val request = PluginHeaderRequest()
        val capture = CapturedPluginWinners("p", "snapshot", listOf(fileRecord("example.esp")), emptyMap(), false)
        assertEquals(PluginFactFailure.CAPTURE_UNAVAILABLE, discovery.observeCapturedWinners("fallout_nv", capture, request).enumerationFailure)
        assertEquals(0L, request.headerBytesRead)
        assertEquals(0, request.candidatesRead)
        val nested = CapturedPluginWinners("p", "snapshot", listOf(fileRecord("optional/extra.esp")), emptyMap(), true)
        val result = discovery.observeCapturedWinners("fallout_nv", nested, request)
        assertTrue(result.enumerationComplete)
        assertTrue(result.observations.isEmpty())
        val missingRoot = CapturedPluginWinners("p", "snapshot", listOf(fileRecord("example.esp")), emptyMap(), true)
        assertEquals(PluginFactFailure.CAPTURE_UNAVAILABLE, discovery.observeCapturedWinners("fallout_nv", missingRoot, request).enumerationFailure)
    }

    @Test
    fun capturedSourceOwnershipAndDuplicateIdentityFailExplicitly() {
        val root = temporaryFolder.newFolder("Owned")
        val outside = temporaryFolder.newFile("Example.esp").apply { writeBytes(PluginHeaderFixtures.opening()) }
        val record = FileRecord("example.esp", "mod", "Mod", outside.absolutePath, "unused")
        val capture = CapturedPluginWinners("p", "snapshot", listOf(record), mapOf("mod" to root.absolutePath), true)
        assertEquals(PluginFactFailure.UNSAFE_SOURCE,
            discovery.observeCapturedWinners("fallout_nv", capture, PluginHeaderRequest()).enumerationFailure)
        val duplicate = CapturedPluginWinners("p", "snapshot", listOf(record, record.copy(normalizedPath = "EXAMPLE.ESP")),
            mapOf("mod" to outside.parentFile.absolutePath), true)
        assertEquals(PluginFactFailure.AMBIGUOUS_IDENTITY,
            discovery.observeCapturedWinners("fallout_nv", duplicate, PluginHeaderRequest()).enumerationFailure)
    }

    @Test
    fun suppliedRootKeyCannotPromoteAnActualNestedPayload() {
        val root = temporaryFolder.newFolder("Mod")
        val nested = root.resolve("optional").apply { mkdir() }.resolve("Example.esp")
        nested.writeBytes(PluginHeaderFixtures.opening())
        val record = FileRecord("example.esp", "mod", "Mod", nested.absolutePath, "unused")
        val capture = CapturedPluginWinners("p", "s", listOf(record), mapOf("mod" to root.absolutePath), true)
        val request = PluginHeaderRequest()
        assertEquals(PluginFactFailure.UNSAFE_SOURCE,
            discovery.observeCapturedWinners("fallout_nv", capture, request).enumerationFailure)
        assertEquals(0L, request.headerBytesRead)
    }

    @Test
    fun BUD05_capturedCandidateOverflowIsNotTruncatedComplete() {
        val root = temporaryFolder.newFolder("Mod")
        val records = (0..8192).map {
            FileRecord("$it.esp", "mod", "Mod", root.resolve("$it.esp").absolutePath, "unused")
        }
        val request = PluginHeaderRequest()
        val result = discovery.observeCapturedWinners("fallout_nv", CapturedPluginWinners(
            "p", "s", records, mapOf("mod" to root.absolutePath), true
        ), request)
        assertEquals(8192, result.observations.size)
        assertEquals(8192, request.candidatesRead)
        assertEquals(PluginFactFailure.CANDIDATE_LIMIT, result.enumerationFailure)
        assertEquals(0L, request.headerBytesRead)
    }

    @Test
    fun discoverPluginsFromWinningRecords_onlyReturnsPluginFiles() {
        val records = listOf(
            fileRecord("textures/armor/iron.dds"),
            fileRecord("skyrim.esm"),
            fileRecord("my mod.esp"),
            fileRecord("meshes/weapons/sword.nif")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals(2, plugins.size)
        assertTrue(plugins.any { it.pluginName == "skyrim.esm" })
        assertTrue(plugins.any { it.pluginName == "my mod.esp" })
    }

    @Test
    fun discoverPluginsFromWinningRecords_assignsPriorityStartingAtOne() {
        val records = listOf(
            fileRecord("zeta.esp"),
            fileRecord("alpha.esm"),
            fileRecord("middle.esl")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals("alpha.esm", plugins[0].pluginName)
        assertEquals(1, plugins[0].priority)

        assertEquals("middle.esl", plugins[1].pluginName)
        assertEquals(2, plugins[1].priority)

        assertEquals("zeta.esp", plugins[2].pluginName)
        assertEquals(3, plugins[2].priority)
    }

    @Test
    fun discoverPluginsFromWinningRecords_detectsPluginTypes() {
        val records = listOf(
            fileRecord("skyrim.esm"),
            fileRecord("update.esl"),
            fileRecord("some mod.esp")
        )

        val plugins = discovery.discoverPluginsFromWinningRecords(records)

        assertEquals("ESM", plugins.first { it.pluginName == "skyrim.esm" }.pluginType)
        assertEquals("ESL", plugins.first { it.pluginName == "update.esl" }.pluginType)
        assertEquals("ESP", plugins.first { it.pluginName == "some mod.esp" }.pluginType)
    }

    private fun fileRecord(path: String): FileRecord {
        return FileRecord(
            normalizedPath = path,
            winningModId = "mod-1",
            winningModName = "Test Mod",
            sourceFilePath = "/fake/source/$path",
            hash = "fake-hash"
        )
    }
}