package com.shonkware.droidmodloader.engine.io

import com.shonkware.droidmodloader.engine.install.InstallCancellationSignal
import com.shonkware.droidmodloader.engine.install.InstallCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArchiveImportFileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun store(external: File) = ArchiveImportFileStore({ external }, {})
    private fun root(external: File) = File(external, "downloads/import_staging")

    @Test fun freshSnapshotsPreserveBasenameAndDoNotReuseSameMetadata() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply { writeText("first bytes") }
        val store = store(external)
        val timestamp = source.lastModified()
        val first = store.copyToTemporarySnapshot(source, source.name)
        source.writeText("other bytes")
        source.setLastModified(timestamp)
        val second = store.copyToTemporarySnapshot(source, source.name)
        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals("Example.zip", first.name)
        assertEquals("Example.zip", second.name)
        assertEquals("first bytes", first.readText())
        assertEquals("other bytes", second.readText())
        store.releaseSnapshot(first)
        assertTrue(second.isFile)
        store.releaseSnapshot(second)
        assertTrue(root(external).listFiles()!!.isEmpty())
        assertEquals("other bytes", source.readText())
    }

    @Test fun cancellationBeforeCopyNeverCreatesSnapshot() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip")
        assertThrows(InstallCancelledException::class.java) {
            store(external).copyToTemporarySnapshot(source, source.name, InstallCancellationSignal { throw InstallCancelledException() })
        }
        assertFalse(root(external).exists())
        assertTrue(source.exists())
    }

    @Test fun cancellationRemovesPartialSnapshotAndItsAttemptDirectory() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Large.zip").apply { writeBytes(ByteArray(128 * 1024) { 42 }) }
        var checks = 0
        assertThrows(InstallCancelledException::class.java) {
            store(external).copyToTemporarySnapshot(source, source.name, InstallCancellationSignal {
                if (++checks == 5) throw InstallCancelledException()
            })
        }
        assertTrue(root(external).listFiles()!!.isEmpty())
        assertEquals(128 * 1024L, source.length())
    }

    @Test fun observableSourceChangeDuringCopyRejectsSnapshot() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Changing.zip").apply { writeBytes(ByteArray(128 * 1024) { 42 }) }
        var checks = 0
        assertThrows(IOException::class.java) {
            store(external).copyToTemporarySnapshot(source, source.name, InstallCancellationSignal {
                if (++checks == 5) source.appendText("changed")
            })
        }
        assertTrue(root(external).listFiles()!!.isEmpty())
        assertTrue(source.exists())
    }

    @Test fun sweepPreservesActiveAttemptsAndLegacyOrUnrelatedFiles() {
        val external = temporaryFolder.newFolder("external")
        val legacy = File(external, "downloads/archive_library/Example (3).zip").apply { parentFile!!.mkdirs(); writeText("legacy") }
        val store = store(external)
        val snapshot = store.copyToTemporarySnapshot(legacy, legacy.name)
        val unrelated = File(root(external), "unrelated.txt").apply { writeText("preserve") }
        val unknown = File(root(external), "unknown-directory").apply { mkdir() }
        store.cleanAbandonedSnapshots()
        assertEquals("legacy", snapshot.readText())
        assertEquals("legacy", legacy.readText())
        assertEquals("preserve", unrelated.readText())
        assertTrue(unknown.isDirectory)
        store.releaseSnapshot(snapshot)
        assertEquals("legacy", legacy.readText())
    }

    @Test fun newSessionRemovesAbandonedCopiesButNotSourceOrExtractedProfileData() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply { writeText("original") }
        val snapshot = store(external).copyToTemporarySnapshot(source, source.name)
        val extracted = temporaryFolder.newFile("extracted-session").apply { writeText("choices and recovery") }
        // A new store represents a new process after the old owner and its work have ended.
        store(external).cleanAbandonedSnapshots()
        assertFalse(snapshot.exists())
        assertFalse(snapshot.parentFile!!.exists())
        assertEquals("original", source.readText())
        assertEquals("choices and recovery", extracted.readText())
    }

    @Test fun cleanupCannotReleaseAnOriginalOrAnotherOwnersSnapshot() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply { writeText("original") }
        val owner = store(external)
        val snapshot = owner.copyToTemporarySnapshot(source, source.name)
        assertThrows(IllegalStateException::class.java) { owner.releaseSnapshot(source) }
        assertThrows(IllegalStateException::class.java) { store(external).releaseSnapshot(snapshot) }
        assertTrue(snapshot.exists())
        assertTrue(source.exists())
        owner.releaseSnapshot(snapshot)
    }

    @Test fun stagingSymlinkCannotRedirectCopyOrSweepToOriginals() {
        val external = temporaryFolder.newFolder("external")
        val downloads = File(external, "downloads").apply { mkdir() }
        val originals = temporaryFolder.newFolder("originals")
        val original = File(originals, "Example.zip").apply { writeText("preserve") }
        Files.createSymbolicLink(File(downloads, "import_staging").toPath(), originals.toPath())
        assertThrows(IOException::class.java) { store(external).copyToTemporarySnapshot(original, original.name) }
        assertThrows(IOException::class.java) { store(external).cleanAbandonedSnapshots() }
        assertEquals("preserve", original.readText())
    }

    @Test fun attemptSymlinkAndUnexpectedSubdirectoriesAreNotFollowed() {
        val external = temporaryFolder.newFolder("external")
        root(external).mkdirs()
        val originals = temporaryFolder.newFolder("originals")
        val sentinel = File(originals, "original.zip").apply { writeText("original") }
        val link = File(root(external), "import-123").toPath()
        Files.createSymbolicLink(link, originals.toPath())
        assertThrows(IOException::class.java) { store(external).cleanAbandonedSnapshots() }
        assertEquals("original", sentinel.readText())
        Files.delete(link)
        val nested = File(root(external), "import-456/unexpected").apply { mkdirs() }
        assertThrows(IOException::class.java) { store(external).cleanAbandonedSnapshots() }
        assertTrue(nested.isDirectory)
    }

    @Test fun childSymlinkIsUnlinkedWithoutDeletingItsTarget() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply { writeText("original") }
        val owner = store(external)
        val snapshot = owner.copyToTemporarySnapshot(source, source.name)
        Files.createSymbolicLink(File(snapshot.parentFile, "link").toPath(), source.toPath())
        owner.releaseSnapshot(snapshot)
        assertEquals("original", source.readText())
        assertFalse(snapshot.parentFile!!.exists())
    }

    @Test fun failedReleaseCanBeRetriedAtStartupWithoutFollowingUnknownDirectories() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip").apply { writeText("original") }
        val owner = store(external)
        val snapshot = owner.copyToTemporarySnapshot(source, source.name)
        val obstruction = File(snapshot.parentFile, "unexpected-directory").apply { mkdir() }
        assertThrows(IOException::class.java) { owner.releaseSnapshot(snapshot) }
        assertTrue(obstruction.isDirectory)
        assertEquals("original", source.readText())
        assertTrue(obstruction.delete())
        owner.cleanAbandonedSnapshots()
        assertFalse(snapshot.parentFile!!.exists())
        assertEquals("original", source.readText())
    }

    @Test fun missingStagingNeedsNoCleanupAndUnsafeNamesAreRejected() {
        val external = temporaryFolder.newFolder("external")
        val source = temporaryFolder.newFile("Example.zip")
        val owner = store(external)
        owner.cleanAbandonedSnapshots()
        assertFalse(root(external).exists())
        for (name in listOf("", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) { owner.copyToTemporarySnapshot(source, name) }
        }
        assertTrue(root(external).listFiles()!!.isEmpty())
    }
}
