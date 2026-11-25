package com.shonkware.droidmodloader.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class CriticalStorageDurabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `durable save persists complete UTF8 and preserves the legacy API`() {
        val file = File(temporaryFolder.root, "records.json")
        CriticalMetadataFile(file).replaceDurably("éé", 4)
        assertEquals("éé", CriticalMetadataFile(file).read(4))
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) = throw AssertionError("Legacy path must not sync parents")
        }
        CriticalMetadataFile(file, operations).replace("legacy")
        assertEquals("legacy", file.readText())
    }

    @Test fun `durable save orders parent preflight closed file promotion and final parent sync`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        val events = mutableListOf<String>()
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                assertEquals(file.parentFile, directory)
                assertEquals(if (events.isEmpty()) "old" else "new", file.readText())
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
                events += "directory synced and closed"
            }
            override fun writeAndSync(file: File, bytes: ByteArray) {
                assertEquals(listOf("directory synced and closed"), events)
                SystemCriticalMetadataFileOperations.writeAndSync(file, bytes)
                events += "file synced and closed"
            }
            override fun promote(source: File, target: File) {
                assertEquals(listOf("directory synced and closed", "file synced and closed"), events)
                assertEquals("old", target.readText())
                SystemCriticalMetadataFileOperations.promote(source, target)
                events += "promoted"
            }
        }
        CriticalMetadataFile(file, operations).replaceDurably("new", 8)
        assertEquals(listOf("directory synced and closed", "file synced and closed", "promoted", "directory synced and closed"), events)
        assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
    }

    @Test fun `unsupported parent persistence refuses first save and replacement before writing`() {
        for (existing in listOf(false, true)) {
            val file = File(temporaryFolder.newFolder(), "records.json")
            if (existing) file.writeText("old")
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun syncDirectory(directory: File) = throw IOException("Unsupported directory sync")
                override fun writeAndSync(file: File, bytes: ByteArray) = throw AssertionError("Must preflight first")
            }
            failure(false) { CriticalMetadataFile(file, operations).replaceDurably("new", 8) }
            assertEquals(if (existing) "old" else null, CriticalMetadataFile(file).read())
            assertEquals(if (existing) listOf(file) else emptyList<File>(), file.parentFile!!.listFiles()!!.toList())
        }
    }

    @Test fun `write sync and close errors never promote first or replacement saves`() {
        for (existing in listOf(false, true)) {
            for (fault in listOf("write", "sync", "close")) {
                val file = File(temporaryFolder.newFolder(), "records.json")
                if (existing) file.writeText("old")
                val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                    override fun writeAndSync(file: File, bytes: ByteArray) {
                        FileOutputStream(file).use { output ->
                            output.write(bytes[0].toInt())
                            if (fault == "write") throw IOException("Interrupted write")
                            output.write(bytes, 1, bytes.size - 1)
                            if (fault == "sync") throw IOException("Sync failed")
                            output.fd.sync()
                        }
                        throw IOException("Close reported a failure")
                    }
                    override fun promote(source: File, target: File) = throw AssertionError("Must not promote")
                }
                failure(false) { CriticalMetadataFile(file, operations).replaceDurably("new", 8) }
                assertEquals(if (existing) "old" else null, CriticalMetadataFile(file).read())
                assertEquals(if (existing) listOf(file) else emptyList<File>(), file.parentFile!!.listFiles()!!.toList())
            }
        }
    }

    @Test fun `failed promotion is uncertain whether or not the rename happened`() {
        for (renameHappened in listOf(false, true)) {
            val file = File(temporaryFolder.newFolder(), "records.json").apply { writeText("old") }
            val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
                override fun promote(source: File, target: File) {
                    if (renameHappened) SystemCriticalMetadataFileOperations.promote(source, target)
                    throw IOException("Promotion reported a failure")
                }
            }
            failure(true) { CriticalMetadataFile(file, operations).replaceDurably("new", 8) }
            assertEquals(if (renameHappened) "new" else "old", CriticalMetadataFile(file).read())
        }
    }

    @Test fun `post promotion directory sync or close failure retains new bytes but reports uncertainty`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        var syncs = 0
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun syncDirectory(directory: File) {
                SystemCriticalMetadataFileOperations.syncDirectory(directory)
                if (++syncs == 2) throw IOException("Directory sync or close reported failure")
            }
        }
        failure(true) { CriticalMetadataFile(file, operations).replaceDurably("new", 8) }
        assertEquals("new", CriticalMetadataFile(file).read())
    }

    @Test fun `process stop after promotion leaves the new record and never promotes leftovers`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        val abandoned = temporaryFolder.newFile(".records.json.abandoned.tmp").apply { writeText("other") }
        val operations = object : CriticalMetadataFileOperations by SystemCriticalMetadataFileOperations {
            override fun promote(source: File, target: File) {
                SystemCriticalMetadataFileOperations.promote(source, target)
                throw Error("Injected process stop")
            }
        }
        assertThrows(Error::class.java) { CriticalMetadataFile(file, operations).replaceDurably("new", 8) }
        assertEquals("new", CriticalMetadataFile(file).read())
        assertEquals("other", abandoned.readText())
    }

    @Test fun `durable saves refuse oversized malformed and linked evidence without replacing it`() {
        val file = temporaryFolder.newFile("records.json").apply { writeText("old") }
        for (text in listOf("123456789", "ééééé", "\uD800")) {
            failure(false) { CriticalMetadataFile(file).replaceDurably(text, 8) }
            assertEquals("old", file.readText())
        }
        file.writeText("123456789")
        failure(false) { CriticalMetadataFile(file).replaceDurably("new", 8) }
        assertEquals("123456789", file.readText())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        failure(false) { CriticalMetadataFile(file).replaceDurably("new", 8) }
        assertEquals(listOf(0xc3.toByte(), 0x28.toByte()), file.readBytes().toList())
        val link = File(temporaryFolder.root, "link.json")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        failure(false) { CriticalMetadataFile(link).replaceDurably("new", 8) }
        Files.delete(file.toPath())
        failure(false) { CriticalMetadataFile(link).replaceDurably("new", 8) }
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun `durable saves refuse missing invalid or aliased parents without creating directories`() {
        val missing = File(temporaryFolder.root, "missing/records.json")
        failure(false) { CriticalMetadataFile(missing).replaceDurably("new", 8) }
        assertFalse(missing.parentFile!!.exists())
        val invalid = temporaryFolder.newFile("invalid").apply { writeText("old") }
        failure(false) { CriticalMetadataFile(File(invalid, "records.json")).replaceDurably("new", 8) }
        assertEquals("old", invalid.readText())
        val real = temporaryFolder.newFolder("real")
        val alias = File(temporaryFolder.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        failure(false) { CriticalMetadataFile(File(alias, "records.json")).replaceDurably("new", 8) }
        assertTrue(real.listFiles()!!.isEmpty())
    }

    @Test fun `directory move preserves a real tree and persists both parents in order`() {
        val source = directoryWithPayload()
        val destination = File(temporaryFolder.newFolder(), "shared")
        val events = mutableListOf<File>()
        val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun syncDirectory(directory: File) {
                SystemCriticalDirectoryMoveOperations.syncDirectory(directory)
                events += directory
            }
            override fun move(source: File, destination: File) {
                assertEquals(listOf(destination.parentFile, source.parentFile), events)
                SystemCriticalDirectoryMoveOperations.move(source, destination)
            }
        }
        CriticalDirectoryMove(operations).moveToAbsent(source, destination)
        assertFalse(source.exists())
        assertEquals("payload", File(destination, "nested/content.txt").readText())
        assertEquals(listOf(destination.parentFile, source.parentFile, destination.parentFile, source.parentFile), events)
    }

    @Test fun `same parent directory move only syncs that parent before and after`() {
        val source = directoryWithPayload()
        val destination = File(source.parentFile, "shared")
        var syncs = 0
        val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun syncDirectory(directory: File) { syncs++; SystemCriticalDirectoryMoveOperations.syncDirectory(directory) }
        }
        CriticalDirectoryMove(operations).moveToAbsent(source, destination)
        assertEquals(2, syncs)
        assertEquals("payload", File(destination, "nested/content.txt").readText())
    }

    @Test fun `directory move refuses every existing destination including dangling links`() {
        for (kind in listOf("empty", "nonempty", "file", "link", "dangling")) {
            val source = directoryWithPayload()
            val destination = File(temporaryFolder.newFolder(), "shared")
            when (kind) {
                "empty" -> destination.mkdir()
                "nonempty" -> { destination.mkdir(); File(destination, "sentinel").writeText("old") }
                "file" -> destination.writeText("old")
                "link" -> Files.createSymbolicLink(destination.toPath(), source.toPath())
                "dangling" -> Files.createSymbolicLink(destination.toPath(), File(destination.parentFile, "missing").toPath())
            }
            failure(false) { CriticalDirectoryMove(noMoveOperations()).moveToAbsent(source, destination) }
            assertEquals("payload", File(source, "nested/content.txt").readText())
            when (kind) {
                "empty" -> assertTrue(destination.listFiles()!!.isEmpty())
                "nonempty" -> assertEquals("old", File(destination, "sentinel").readText())
                "file" -> assertEquals("old", destination.readText())
                else -> assertTrue(Files.isSymbolicLink(destination.toPath()))
            }
        }
    }

    @Test fun `directory move refuses linked missing aliased or nested source paths`() {
        val source = directoryWithPayload()
        val link = File(source.parentFile, "link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        val destination = File(temporaryFolder.newFolder(), "shared")
        for (invalid in listOf(link, File(source.parentFile, "missing"), File(source, "nested/.."))) {
            failure(false) { CriticalDirectoryMove(noMoveOperations()).moveToAbsent(invalid, destination) }
        }
        failure(false) { CriticalDirectoryMove(noMoveOperations()).moveToAbsent(source, source) }
        failure(false) { CriticalDirectoryMove(noMoveOperations()).moveToAbsent(source, File(source, "shared")) }
        assertEquals("payload", File(source, "nested/content.txt").readText())
        assertFalse(destination.exists())
    }

    @Test fun `directory move refuses cross volume and unsupported parent persistence before moving`() {
        for (fault in listOf("volume", "first parent", "second parent")) {
            val source = directoryWithPayload()
            val destination = File(temporaryFolder.newFolder(), "shared")
            var syncs = 0
            val operations = object : CriticalDirectoryMoveOperations by noMoveOperations() {
                override fun sameFileStore(source: File, destinationParent: File): Boolean = fault != "volume"
                override fun syncDirectory(directory: File) {
                    syncs++
                    if ((fault == "first parent" && syncs == 1) || (fault == "second parent" && syncs == 2)) {
                        throw IOException("Unsupported parent persistence")
                    }
                }
            }
            failure(false) { CriticalDirectoryMove(operations).moveToAbsent(source, destination) }
            assertEquals("payload", File(source, "nested/content.txt").readText())
            assertFalse(destination.exists())
        }
    }

    @Test fun `destination appearing during preflight is preserved instead of replaced`() {
        val source = directoryWithPayload()
        val destination = File(temporaryFolder.newFolder(), "shared")
        val operations = object : CriticalDirectoryMoveOperations by noMoveOperations() {
            override fun syncDirectory(directory: File) { if (!destination.exists()) destination.writeText("unexpected") }
        }
        failure(false) { CriticalDirectoryMove(operations).moveToAbsent(source, destination) }
        assertEquals("unexpected", destination.readText())
        assertEquals("payload", File(source, "nested/content.txt").readText())
    }

    @Test fun `native move boundary rechecks absent destination`() {
        val source = directoryWithPayload()
        val destination = File(temporaryFolder.newFolder(), "shared").apply { mkdir() }
        assertThrows(IOException::class.java) { SystemCriticalDirectoryMoveOperations.move(source, destination) }
        assertTrue(source.isDirectory)
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun `failed atomic directory move never falls back or reports unchanged after attempting rename`() {
        for (fault in listOf("unsupported", "before rename", "after rename")) {
            val source = directoryWithPayload()
            val destination = File(temporaryFolder.newFolder(), "shared")
            var moves = 0
            val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
                override fun move(source: File, destination: File) {
                    moves++
                    if (fault == "after rename") SystemCriticalDirectoryMoveOperations.move(source, destination)
                    if (fault == "unsupported") throw AtomicMoveNotSupportedException(source.path, destination.path, "Unsupported")
                    throw IOException("Move reported failure")
                }
            }
            failure(true) { CriticalDirectoryMove(operations).moveToAbsent(source, destination) }
            assertEquals(1, moves)
            assertEquals(fault != "after rename", source.exists())
            assertEquals(fault == "after rename", destination.exists())
            val retained = if (fault == "after rename") destination else source
            assertEquals("payload", File(retained, "nested/content.txt").readText())
        }
    }

    @Test fun `either parent failure after move leaves the new tree but reports uncertainty`() {
        for (failedSync in listOf(3, 4)) {
            val source = directoryWithPayload()
            val destination = File(temporaryFolder.newFolder(), "shared")
            var syncs = 0
            val operations = object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
                override fun syncDirectory(directory: File) {
                    SystemCriticalDirectoryMoveOperations.syncDirectory(directory)
                    if (++syncs == failedSync) throw IOException("Post move parent sync or close failed")
                }
            }
            failure(true) { CriticalDirectoryMove(operations).moveToAbsent(source, destination) }
            assertFalse(source.exists())
            assertEquals("payload", File(destination, "nested/content.txt").readText())
        }
    }

    private fun directoryWithPayload(): File = temporaryFolder.newFolder().also { directory ->
        File(directory, "nested").mkdir()
        File(directory, "nested/content.txt").writeText("payload")
    }

    private fun noMoveOperations(): CriticalDirectoryMoveOperations =
        object : CriticalDirectoryMoveOperations by SystemCriticalDirectoryMoveOperations {
            override fun move(source: File, destination: File) = throw AssertionError("Must not move")
        }

    private fun failure(mutationMayHaveOccurred: Boolean, action: () -> Unit) {
        val exception = assertThrows(CriticalStorageException::class.java, action)
        assertEquals(mutationMayHaveOccurred, exception.mutationMayHaveOccurred)
        assertTrue(exception.cause is Exception)
    }
}
