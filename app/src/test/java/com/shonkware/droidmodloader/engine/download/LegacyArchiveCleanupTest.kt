package com.shonkware.droidmodloader.engine.download

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files

class LegacyArchiveCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base by lazy { temporary.newFolder("app").canonicalFile }
    private val originals by lazy { temporary.newFolder("originals").canonicalFile }
    private val internal by lazy { temporary.newFolder("internal").canonicalFile }
    private val library by lazy { File(base, "downloads/archive_library").apply { mkdirs() } }

    private fun original(name: String = "mod.zip", content: String = "archive bytes"): File =
        File(originals, name).apply { writeText(content) }

    private fun copy(source: File, name: String = source.name): File =
        source.copyTo(File(library, name))

    private fun record(copy: File, source: File?): DownloadedArchiveRecord = DownloadedArchiveRecord(
        archiveId = copy.name, displayName = copy.name, fileName = copy.name, archiveFormat = "zip",
        relativePath = copy.name, sizeBytes = copy.length(), modifiedAtMillis = copy.lastModified(),
        fingerprint = "not-a-content-hash", sourcePath = source?.path, createdAtMillis = 1
    )

    private fun save(records: List<DownloadedArchiveRecord>, profile: String? = "active"): File {
        val file = File(base, if (profile == null) "state/downloaded_archives.json"
            else "state/profiles/$profile/downloaded_archives.json")
        DownloadedArchiveRepository(library, file).save(records)
        return file
    }

    private fun cleanup(folders: Set<String> = emptySet(), report: (String) -> Unit = {}) =
        LegacyArchiveCleanup(base, internal, { folders }, report)

    @Test fun removesVerifiedCopiesAcrossGlobalAndDeletedProfilesWithoutChangingHistoryOrOtherData() {
        val a = original("a.zip")
        val b = original("b.zip")
        val c = original("c.zip")
        val copies = listOf(copy(a), copy(b), copy(c))
        val metadata = listOf(save(listOf(record(copies[0], a))),
            save(listOf(record(copies[1], b)), "deleted-profile"), save(listOf(record(copies[2], c)), null))
        val before = metadata.map { it.readText() }
        val session = File(base, "installer_sessions/pending/extracted/sentinel").apply { parentFile.mkdirs(); writeText("keep") }
        val recovery = File(base, "profiles/other/mods/.transaction").apply { parentFile.mkdirs(); writeText("keep") }
        val unknown = File(library, "unregistered (2).zip").apply { writeText("keep") }
        val directory = File(library, "unexpected").apply { mkdirs() }
        val child = File(directory, "child").apply { writeText("keep") }
        val result = cleanup().clean()
        assertEquals(LegacyArchiveCleanupResult(3, a.length() + b.length() + c.length(), 2), result)
        assertTrue(copies.none { it.exists() })
        assertEquals(before, metadata.map { it.readText() })
        for (file in listOf(a, b, c, session, recovery, unknown, child)) assertTrue(file.exists())
        assertEquals(LegacyArchiveCleanupResult(0, 0, 2), cleanup().clean())
    }

    @Test fun sameSizeAndTimeDoNotAuthorizeDeletionWhenBytesDifferNearEnd() {
        val source = original(content = "a".repeat(150_000) + "x")
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        val time = source.lastModified()
        source.writeText("a".repeat(150_000) + "y")
        assertTrue(source.setLastModified(time))
        assertEquals(0, cleanup().clean().removedFiles)
        assertTrue(candidate.exists())
    }

    @Test fun missingSourceNullSourceAndSizeChangedRecordArePreserved() {
        val source = original()
        val a = copy(source, "missing.zip")
        val b = copy(source, "unknown.zip")
        val c = copy(source, "stale.zip")
        save(listOf(record(a, File(originals, "absent.zip")), record(b, null), record(c, source).copy(sizeBytes = 100)))
        assertEquals(LegacyArchiveCleanupResult(0, 0, 3), cleanup().clean())
    }

    @Test fun emptyLibraryDoesNotRequireAnyEvidenceOrCreateDirectories() {
        val result = LegacyArchiveCleanup(base, internal, { error("No evidence should be read") }).clean()
        assertEquals(LegacyArchiveCleanupResult(0, 0, 0), result)
        assertFalse(File(base, "downloads").exists())
        assertFalse(File(base, "state").exists())
    }

    @Test fun sameFileViaHardLinkIsNotTreatedAsASeparateOriginal() {
        val source = original()
        val candidate = File(library, source.name)
        Files.createLink(candidate.toPath(), source.toPath())
        save(listOf(record(candidate, source)))
        assertEquals(0, cleanup().clean().removedFiles)
        assertTrue(candidate.exists())
        assertTrue(source.exists())
    }

    @Test fun sourceDirectoryIsNotAnOriginalArchive() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, originals)))
        assertEquals(0, cleanup().clean().removedFiles)
        assertTrue(candidate.exists())
    }

    @Test fun librarySelectedByAnyProfileProtectsItsFiles() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)), "deleted-profile")
        assertEquals(0, cleanup(setOf(library.path)).clean().removedFiles)
        assertTrue(candidate.exists())
    }

    @Test fun copiesReferencedAsOriginalsAndAppStoredSourcesArePreserved() {
        val source = original()
        val first = copy(source, "first.zip")
        val second = copy(first, "second.zip")
        save(listOf(record(first, source)))
        save(listOf(record(second, first)), "other-profile")
        assertEquals(LegacyArchiveCleanupResult(0, 0, 2), cleanup().clean())
    }

    @Test fun internalAppSessionIsNotTreatedAsADurableOriginal() {
        val source = File(internal, "profiles/other/installer_sessions/session/extracted/mod.zip").apply {
            parentFile.mkdirs(); writeText("bytes")
        }
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        assertEquals(0, cleanup().clean().removedFiles)
        assertTrue(candidate.exists())
        assertTrue(source.exists())
    }

    @Test fun corruptOtherProfileStopsBeforeAnyDeletion() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        val broken = File(base, "state/profiles/orphan/downloaded_archives.json").apply {
            parentFile.mkdirs(); writeText("[")
        }
        assertThrows(Exception::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
        assertEquals("[", broken.readText())
    }

    @Test fun unknownMetadataAndUnresolvedUrisStopBeforeAnyDeletion() {
        val source = original()
        val candidate = copy(source)
        val file = save(listOf(record(candidate, source)))
        val array = JSONArray(file.readText())
        array.getJSONObject(0).put("futureOwnership", true)
        file.writeText(array.toString())
        assertThrows(IOException::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
        save(listOf(record(candidate, source).copy(sourcePath = "content://unknown/original")))
        assertThrows(IOException::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
    }

    @Test fun malformedUnusedMetadataFieldsStillBlockCleanup() {
        val source = original()
        val candidate = copy(source)
        val file = save(listOf(record(candidate, source)))
        val array = JSONArray(file.readText())
        array.getJSONObject(0).put("installedAtMillis", "not-a-time")
        file.writeText(array.toString())
        assertThrows(Exception::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
    }

    @Test fun unfinishedMetadataIsPreservedAndBlocksCleanup() {
        val source = original()
        val candidate = copy(source)
        val file = save(listOf(record(candidate, source)))
        val abandoned = File(file.parentFile, ".downloaded_archives.json.123.tmp").apply { writeText("evidence") }
        assertThrows(IOException::class.java) { cleanup().clean() }
        assertEquals("evidence", abandoned.readText())
        assertTrue(candidate.exists())
    }

    @Test fun symlinkedCandidateAndOriginalAreNotDeleted() {
        val source = original()
        val linkedCopy = File(library, "linked.zip")
        Files.createSymbolicLink(linkedCopy.toPath(), source.toPath())
        val sourceLink = File(originals, "source-link.zip")
        Files.createSymbolicLink(sourceLink.toPath(), source.toPath())
        val candidate = copy(source)
        save(listOf(record(linkedCopy, source), record(candidate, sourceLink)))
        assertEquals(LegacyArchiveCleanupResult(0, 0, 2), cleanup().clean())
        assertEquals("archive bytes", source.readText())
        assertTrue(Files.isSymbolicLink(linkedCopy.toPath()))
    }

    @Test fun symlinkedMetadataDirectoryStopsBeforeDeletion() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        val foreign = temporary.newFolder("foreign")
        Files.createSymbolicLink(File(base, "state/profiles/linked").toPath(), foreign.toPath())
        assertThrows(IOException::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
    }

    @Test fun symlinkedLibraryIsNeverTraversed() {
        val source = original()
        val foreign = temporary.newFolder("foreign")
        val candidate = source.copyTo(File(foreign, "mod.zip"))
        File(base, "downloads").mkdirs()
        Files.createSymbolicLink(File(base, "downloads/archive_library").toPath(), foreign.toPath())
        assertThrows(IOException::class.java) { cleanup().clean() }
        assertTrue(candidate.exists())
    }

    @Test fun nonDirectPathsAndAmbiguousAliasRecordsPreserveTheTarget() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source), record(candidate, source).copy(
            archiveId = "alias", relativePath = "../archive_library/${candidate.name}")))
        assertEquals(0, cleanup().clean().removedFiles)
        assertTrue(candidate.exists())
    }

    @Test fun changedOriginalBetweenVerificationAndUnlinkIsPreserved() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        val result = cleanup(report = { if (it.startsWith("Verified")) source.appendText("changed") }).clean()
        assertEquals(0, result.removedFiles)
        assertTrue(candidate.exists())
    }

    @Test fun replacementSymlinkBetweenVerificationAndUnlinkIsPreserved() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        cleanup(report = {
            if (it.startsWith("Verified")) {
                Files.delete(candidate.toPath())
                Files.createSymbolicLink(candidate.toPath(), source.toPath())
            }
        }).clean()
        assertTrue(Files.isSymbolicLink(candidate.toPath()))
        assertTrue(source.exists())
    }

    @Test fun metadataEditsPreservingSizeAndTimeAreDetectedBeforeUnlink() {
        val source = original()
        val candidate = copy(source)
        val file = save(listOf(record(candidate, source)))
        val before = file.readText()
        val time = file.lastModified()
        assertThrows(IOException::class.java) {
            cleanup(report = {
                if (it.startsWith("Verified")) {
                    file.writeText(before.replace("not-a-content-hash", "NOT-a-content-hash"))
                    assertTrue(file.setLastModified(time))
                }
            }).clean()
        }
        assertTrue(candidate.exists())
    }

    @Test fun newlyDiscoveredProfileReferenceStopsDeletion() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        assertThrows(IOException::class.java) {
            cleanup(report = { if (it.startsWith("Verified")) save(emptyList(), "new-profile") }).clean()
        }
        assertTrue(candidate.exists())
    }

    @Test fun interruptionStopsWorkWithoutRemovingRemainingFiles() {
        val source = original()
        val first = copy(source, "a.zip")
        val second = copy(source, "b.zip")
        save(listOf(record(first, source), record(second, source)))
        try {
            assertThrows(InterruptedIOException::class.java) {
                cleanup(report = { if (it.startsWith("Removed")) Thread.currentThread().interrupt() }).clean()
            }
        } finally { Thread.interrupted() }
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(source.exists())
    }

    @Test fun selectionChangesBeforeUnlinkStopCleanup() {
        val source = original()
        val candidate = copy(source)
        save(listOf(record(candidate, source)))
        var folders = emptySet<String>()
        assertThrows(IOException::class.java) {
            LegacyArchiveCleanup(base, internal, { folders }, {
                if (it.startsWith("Verified")) folders = setOf(library.path)
            }).clean()
        }
        assertTrue(candidate.exists())
    }

    @Test fun preferenceSnapshotIncludesDeletedProfilesAndRejectsUnresolvedEvidenceWithoutMutatingIt() {
        val values = mapOf("selected_archive_folder_path.active" to "/archives",
            "selected_archive_folder_path.deleted" to "/old", "irrelevant" to 1)
        assertEquals(setOf("/archives", "/old"), ArchiveFolderPreferences.cleanupSelectedFolderPaths(values))
        assertEquals(3, values.size)
        for (unknown in listOf(
            mapOf("selected_archive_folder_uri" to "content://old"),
            mapOf("selected_archive_folder_uri.deleted" to "content://old"),
            mapOf("archive_folder_reselection_required.deleted" to true),
            mapOf("selected_archive_folder_path.deleted" to 12)
        )) assertThrows(IOException::class.java) { ArchiveFolderPreferences.cleanupSelectedFolderPaths(unknown) }
    }
}
