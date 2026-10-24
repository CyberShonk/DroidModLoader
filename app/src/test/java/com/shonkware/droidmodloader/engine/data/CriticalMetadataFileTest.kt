package com.shonkware.droidmodloader.engine.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CriticalMetadataFileTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `only a missing file returns null`() {
        val file = File(temporaryFolder.root, "records.json")
        assertNull(CriticalMetadataFile(file).read())
        file.mkdir()
        assertThrows(IOException::class.java) { CriticalMetadataFile(file).read() }
    }

    @Test fun `dangling symbolic link is not treated as missing metadata`() {
        val link = File(temporaryFolder.root, "records.json")
        Files.createSymbolicLink(link.toPath(), File(temporaryFolder.root, "absent.json").toPath())
        assertThrows(IOException::class.java) { CriticalMetadataFile(link).read() }
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun `malformed UTF8 is not silently replaced`() {
        val file = temporaryFolder.newFile("records.json")
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        file.writeBytes(bytes)
        assertThrows(IOException::class.java) { CriticalMetadataFile(file).read() }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `old state stays visible until synced sibling is promoted`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        val events = mutableListOf<String>()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun writeAndSync(fileToWrite: File, bytes: ByteArray) {
                assertEquals(file.parentFile, fileToWrite.parentFile)
                assertTrue(fileToWrite != file)
                assertEquals("old", CriticalMetadataFile(file).read())
                SystemCriticalMetadataFileOperations.writeAndSync(fileToWrite, bytes)
                events += "synced and closed"
            }
            override fun promote(source: File, target: File) {
                assertEquals(listOf("synced and closed"), events)
                assertEquals("new", source.readText())
                assertEquals("old", target.readText())
                SystemCriticalMetadataFileOperations.promote(source, target)
                events += "promoted"
            }
        }
        CriticalMetadataFile(file, operations).replace("new")
        assertEquals(listOf("synced and closed", "promoted"), events)
        assertEquals("new", CriticalMetadataFile(file).read())
        assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
    }

    @Test fun `failed first persistence never creates an authoritative file`() {
        for (fault in MetadataFault.entries.filter { it != MetadataFault.READ }) {
            val file = File(temporaryFolder.newFolder(fault.name), "records.json")
            assertThrows(IOException::class.java) {
                CriticalMetadataFile(file, MetadataFaultOperations(fault)).replace("new")
            }
            assertFalse(file.exists())
            assertNull(CriticalMetadataFile(file).read())
            assertTrue(file.parentFile!!.listFiles()!!.isEmpty())
        }
    }

    @Test fun `abandoned temporary writes are ignored and never reused`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        val abandoned = temporaryFolder.newFile(".records.json.abandoned.tmp")
            .apply { writeText("partial new") }
        assertEquals("old", CriticalMetadataFile(file).read())
        CriticalMetadataFile(file).replace("complete new")
        assertEquals("partial new", abandoned.readText())
        assertEquals("complete new", CriticalMetadataFile(file).read())
    }

    @Test fun `interruption after atomic promotion leaves the complete new file authoritative`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                SystemCriticalMetadataFileOperations.promote(source, target)
                throw Error("Injected stop after successful promotion")
            }
        }
        assertThrows(Error::class.java) { CriticalMetadataFile(file, operations).replace("complete new") }
        assertEquals("complete new", CriticalMetadataFile(file).read())
        assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
    }

    @Test fun `invalid parent fails without modifying its bytes`() {
        val parent = temporaryFolder.newFile("not-a-directory").apply { writeText("sentinel") }
        assertThrows(IOException::class.java) {
            CriticalMetadataFile(File(parent, "records.json")).replace("new")
        }
        assertEquals("sentinel", parent.readText())
    }
}
