package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirectPathValidatorTest {
    private val validator = DirectPathValidator()

    @Test
    fun `valid directory returns canonical path`() {
        val root = Files.createTempDirectory("dml-path").toFile()
        val nested = File(root, "one/../two").apply { mkdirs() }

        val result = validator.validateDirectory(
            path = nested.absolutePath,
            requireWritable = true
        )

        assertTrue(result.isValid)
        assertEquals(nested.canonicalPath, result.canonicalPath)
    }

    @Test
    fun `blank relative missing and file paths are rejected`() {
        val root = Files.createTempDirectory("dml-path").toFile()
        val file = File(root, "not-a-directory.txt").apply { writeText("test") }

        assertFalse(validator.validateDirectory("", false).isValid)
        assertFalse(validator.validateDirectory("relative/path", false).isValid)
        assertFalse(validator.validateDirectory(File(root, "missing").absolutePath, false).isValid)
        assertFalse(validator.validateDirectory(file.absolutePath, false).isValid)
    }

    @Test
    fun `contained child resolution rejects traversal`() {
        val root = Files.createTempDirectory("dml-root").toFile()
        val inside = validator.resolveContainedChild(root.absolutePath, "textures/a.dds")

        assertTrue(inside.absolutePath.startsWith(root.canonicalPath))

        runCatching {
            validator.resolveContainedChild(root.absolutePath, "../outside.txt")
        }.onSuccess {
            throw AssertionError("Traversal should have been rejected.")
        }
    }
}
