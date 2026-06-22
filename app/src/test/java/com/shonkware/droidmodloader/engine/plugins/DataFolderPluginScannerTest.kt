package com.shonkware.droidmodloader.engine.plugins

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataFolderPluginScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
