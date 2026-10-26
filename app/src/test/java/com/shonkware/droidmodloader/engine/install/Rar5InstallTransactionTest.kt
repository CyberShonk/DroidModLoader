package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class Rar5InstallTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun installer(temp: File, mods: File): PreparedArchiveInstaller {
        var id = 0
        return PreparedArchiveInstaller(temp, mods, InstalledModDirectoryReplacer(), { "rar5-${id++}" }, {})
    }

    private fun archive(bytes: ByteArray): File = File(temporary.root, "ExampleMod.rar").apply {
        writeBytes(RarTestArchives.rar5(RarTestArchives.entry("Data/marker.txt", bytes)))
    }

    @Test fun `real prepared installer performs first install and replacement with stored rar5`() {
        val temp = temporary.newFolder("temp")
        val mods = temporary.newFolder("mods")
        val installer = installer(temp, mods)
        for (content in listOf("first", "replacement")) {
            val prepared = installer.prepare(archive(content.toByteArray()))
            val output = installer.finalizeInstall(prepared, InstallerSelection(prepared.plan.defaultSelectedOptionIds))
            assertEquals("ExampleMod", output.name)
            assertEquals(content, File(output, "marker.txt").readText())
            assertFalse(File(prepared.sessionRootPath).exists())
            assertEquals(listOf("ExampleMod"), mods.list()!!.toList())
        }
    }

    @Test fun `real prepare failure preserves previous install and cleans extracted session`() {
        val temp = temporary.newFolder("temp")
        val mods = temporary.newFolder("mods")
        val installed = File(mods, "ExampleMod").apply { mkdirs() }
        File(installed, "marker.txt").writeText("previous")
        val archive = archive(ByteArray(4096) { 1 })
        archive.writeBytes(archive.readBytes().apply { this[size - 9] = 2 })
        assertThrows(IOException::class.java) { installer(temp, mods).prepare(archive) }
        assertEquals("previous", File(installed, "marker.txt").readText())
        assertNoSessionOrStaging(temp, mods)
    }

    @Test fun `real prepare cancellation preserves previous install and cleans partial output`() {
        val temp = temporary.newFolder("temp")
        val mods = temporary.newFolder("mods")
        val installed = File(mods, "ExampleMod").apply { mkdirs() }
        File(installed, "marker.txt").writeText("previous")
        val archive = archive(ByteArray(1 shl 20) { 1 })
        var sawOutput = false
        val signal = InstallCancellationSignal {
            if (temp.walkTopDown().any { it.isFile && it.name == "marker.txt" && it.length() > 0 }) {
                sawOutput = true
                throw InstallCancelledException()
            }
        }
        assertThrows(InstallCancelledException::class.java) { installer(temp, mods).prepare(archive, signal) }
        assertTrue(sawOutput)
        assertEquals("previous", File(installed, "marker.txt").readText())
        assertNoSessionOrStaging(temp, mods)
    }

    private fun assertNoSessionOrStaging(temp: File, mods: File) {
        assertFalse(File(temp, "installer_sessions").walkTopDown().any { it.isFile || it.name == "raw" })
        assertEquals(listOf("ExampleMod"), mods.list()!!.toList())
    }
}
