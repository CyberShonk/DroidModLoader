package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirectFolderBrowserTest {
    @Test
    fun `browser lists readable directories and navigates to roots`() {
        val root = Files.createTempDirectory("dml-browser").toFile()
        val alpha = File(root, "Alpha").apply { mkdirs() }
        File(root, "beta").mkdirs()
        File(root, "file.txt").writeText("ignored")

        val browser = DirectFolderBrowser(listOf(root))
        val rootList = browser.openRoots()
        assertEquals(listOf(root.canonicalPath), rootList.entries.map { it.path })

        val openedRoot = browser.open(root.absolutePath)
        assertEquals(root.canonicalPath, openedRoot.currentPath)
        assertNull(openedRoot.parentPath)
        assertEquals(listOf("Alpha", "beta"), openedRoot.entries.map { it.name })

        val openedAlpha = browser.open(alpha.absolutePath)
        assertEquals(root.canonicalPath, openedAlpha.parentPath)
        assertTrue(openedAlpha.canSelectCurrent)

        assertNull(browser.navigateUp(openedRoot).currentPath)
    }

    @Test
    fun `browser rejects folders outside configured roots`() {
        val root = Files.createTempDirectory("dml-browser-root").toFile()
        val outside = Files.createTempDirectory("dml-browser-outside").toFile()
        val browser = DirectFolderBrowser(listOf(root))

        val state = browser.open(outside.absolutePath)

        assertNull(state.currentPath)
        assertTrue(state.errorMessage?.contains("outside") == true)
    }
}
