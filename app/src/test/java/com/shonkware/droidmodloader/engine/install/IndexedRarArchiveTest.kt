package com.shonkware.droidmodloader.engine.install

import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.HeaderNotInArchiveException
import com.github.junrar.volume.FileVolume
import com.github.junrar.volume.VolumeManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class IndexedRarArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `stable indexed snapshot retains ordinals and real extraction bytes`() {
        val files = Array(512) { "file-$it" to "payload-$it".toByteArray() }
        val input = temp.newFile().apply { writeBytes(RarTestArchives.rar4(*files)) }
        IndexedRarArchive(input).use { archive ->
            val headers = archive.fileHeaders
            assertSame(headers, archive.fileHeaders)
            assertEquals(files.size, headers.size)
            for ((index, header) in headers.withIndex()) {
                assertEquals(index, headers.indexOf(header))
                val out = ByteArrayOutputStream()
                archive.extractFile(header, out)
                assertArrayEquals(files[index].second, out.toByteArray())
            }
        }
    }

    @Test fun `volume replacement invalidates snapshots and rejects stale headers`() {
        val first = temp.newFile().apply { writeBytes(RarTestArchives.rar4("first", byteArrayOf(1))) }
        val second = temp.newFile().apply { writeBytes(RarTestArchives.rar4("second", byteArrayOf(2))) }
        IndexedRarArchive(first).use { archive ->
            val old = archive.fileHeaders
            archive.setVolume(FileVolume(archive, second))
            val current = archive.fileHeaders
            assertNotSame(old, current)
            assertSame(current, archive.fileHeaders)
            assertEquals("second", current.single().fileName)
            assertEquals(-1, current.indexOf(old.single()))
            assertThrows(HeaderNotInArchiveException::class.java) {
                archive.extractFile(old.single(), ByteArrayOutputStream())
            }
            val out = ByteArrayOutputStream()
            archive.extractFile(current.single(), out)
            assertArrayEquals(byteArrayOf(2), out.toByteArray())
        }
    }

    @Test fun `constructor callbacks cannot cache partially parsed headers`() {
        val input = temp.newFile().apply { writeBytes(RarTestArchives.rar4("file", byteArrayOf(1))) }
        var called = false
        val volumes = VolumeManager { owner, previous ->
            check(previous == null)
            assertTrue(owner.fileHeaders.isEmpty())
            called = true
            FileVolume(owner, input)
        }
        IndexedRarArchive(volumes, ArchiveOptions.builder().maxDictionarySize(32L shl 20).build()).use { archive ->
            assertTrue(called)
            assertEquals("file", archive.fileHeaders.single().fileName)
            assertSame(archive.fileHeaders, archive.fileHeaders)
        }
    }
}
