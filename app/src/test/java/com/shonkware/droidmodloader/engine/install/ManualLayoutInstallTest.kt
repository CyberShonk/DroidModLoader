package com.shonkware.droidmodloader.engine.install

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real ZIP extraction, filesystem copies and replacement transactions, without an Android UI. */
class ManualLayoutInstallTest {
    @get:Rule val temporary = TemporaryFolder()
    private var reads = 0
    private var copies = 0
    private var sessionId = 0
    private val mods get() = File(temporary.root, "mods")

    private fun installer(
        afterCopy: () -> Unit = {},
        failPromotion: Boolean = false,
        tempBase: File = File(temporary.root, "temp"),
        modsBase: File = mods
    ): PreparedArchiveInstaller {
        val reader = object : ArchiveReader {
            override fun read(archive: File, writer: ArchiveEntryWriter) {
                reads++
                ZipArchiveReader().read(archive, writer)
            }
        }
        val operations = object : InstalledModDirectoryOperations {
            override fun move(source: File, target: File) {
                if (failPromotion && source.name.startsWith("_installing_")) throw IOException("Injected promotion failure")
                Files.move(source.toPath(), target.toPath())
            }
            override fun deleteRecursively(target: File) = target.deleteRecursively()
        }
        return PreparedArchiveInstaller(
            tempDir = tempBase, modsDir = modsBase,
            directoryReplacer = InstalledModDirectoryReplacer(operations, { "backup" }, {}, InstallReplacementTransactionStore()),
            operationId = { "manual-${sessionId++}" }, debugLog = {},
            archiveExtractor = ArchiveExtractor(
                readerRegistry = ArchiveReaderRegistry(ArchiveFormatProbe(), reader, reader, reader, reader),
                createWriter = { output, signal -> ArchiveEntryWriter(output, ExtractionLimits(), {}, { Long.MAX_VALUE }, signal) }
            ),
            fileCopier = { source, target, signal ->
                copies++
                InstallFileCopier.copyRecursively(source, target, signal, usableSpaceBytes = { Long.MAX_VALUE })
                afterCopy()
            }
        )
    }

    private fun archive(): File = File(temporary.root, "Example.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            for (path in listOf("First/Content/mod.esp", "First/Content/textures/a.dds",
                "First/Content/textures/b.dds", "Second/Content/other.esp", "outside.txt")) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(path.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun selection(prepared: PreparedArchiveInstall) = InstallerSelection(setOf(
        prepared.plan.groups.flatMap { it.options }.single { it.sourcePath == "First/Content" }.id))

    private fun oldInstall() = File(mods, "Example").apply { mkdirs(); File(this, "old.txt").writeText("original") }
    private fun assertNoWork() {
        assertTrue(mods.listFiles().orEmpty().none {
            it.name.startsWith("_installing_") || it.name.startsWith("_dml_backup_") ||
                it.name.startsWith("_dml_transaction_")
        })
    }
    private fun paths(root: File) = root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()

    @Test fun `existing chooser installs the exact validated preview with one archive decode`() {
        val installer = installer()
        val prepared = installer.prepare(archive())
        assertEquals(InstallerType.MANUAL_DATA_FOLDER, prepared.plan.installerType)
        val inventory = checkNotNull(prepared.manualLayoutInventory)
        val preview = inventory.preview(ManualLayoutProposal("First/Content"))
        val result = installer.finalizeInstall(prepared, selection(prepared))
        assertEquals(preview.files.map { it.destinationPath }.toSet(), paths(result))
        for (file in preview.files) {
            assertEquals(file.sourcePath, File(result, file.destinationPath).readText())
        }
        assertEquals(1, reads)
        assertEquals(3, copies)
        assertFalse(File(prepared.sessionRootPath).exists())
        assertNoWork()
    }

    @Test fun `explicit exclusions install only previewed files and replace old bytes`() {
        val old = oldInstall()
        val installer = installer()
        val prepared = installer.prepare(archive())
        val proposal = ManualLayoutProposal("First/Content").excludeSubtree("First/Content/textures/a.dds")
        val preview = checkNotNull(prepared.manualLayoutInventory).preview(proposal)
        val result = installer.finalizeInstall(prepared, selection(prepared), manualLayoutProposal = proposal)
        assertEquals(old, result)
        assertEquals(preview.files.map { it.destinationPath }.toSet(), paths(result))
        assertFalse(File(result, "old.txt").exists())
        assertFalse(File(result, "textures/a.dds").exists())
        assertEquals(1, reads)
        assertNoWork()
    }

    @Test fun `invalid duplicate unknown and absent choices do not stage or replace`() {
        val old = oldInstall()
        val installer = installer()
        val prepared = installer.prepare(archive())
        val allIds = prepared.plan.groups.flatMap { it.options }.map { it.id }.toSet()
        for (ids in listOf(emptySet(), setOf("missing"), allIds, selection(prepared).selectedOptionIds + "missing")) {
            assertThrows(IOException::class.java) { installer.finalizeInstall(prepared, InstallerSelection(ids)) }
        }
        val option = prepared.plan.groups.single().options.first()
        val duplicatePlan = prepared.plan.copy(groups = listOf(prepared.plan.groups.single().copy(options = listOf(option, option))))
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(prepared.copy(plan = duplicatePlan), InstallerSelection(setOf(option.id)))
        }
        assertEquals(0, copies)
        assertEquals("original", File(old, "old.txt").readText())
        assertTrue(File(prepared.sessionRootPath).exists())
        assertNoWork()
    }

    @Test fun `invalid empty and different-root proposals refuse before writes`() {
        val installer = installer()
        val prepared = installer.prepare(archive())
        for (proposal in listOf(ManualLayoutProposal("Second/Content"),
            ManualLayoutProposal("First/Content", setOf("First/Content")),
            ManualLayoutProposal("First/Content", setOf("../outside")))) {
            assertThrows(IOException::class.java) {
                installer.finalizeInstall(prepared, selection(prepared), manualLayoutProposal = proposal)
            }
        }
        assertEquals(0, copies)
        assertFalse(mods.exists())
    }

    @Test fun `stale changed and mismatched session details preserve the old installation`() {
        val old = oldInstall()
        val installer = installer()
        val prepared = installer.prepare(archive())
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(prepared.copy(sessionRootPath = temporary.root.absolutePath), selection(prepared))
        }
        File(prepared.extractedRootPath, "First/Content/mod.esp").writeText("changed")
        assertThrows(IOException::class.java) { installer.finalizeInstall(prepared, selection(prepared)) }
        assertEquals("original", File(old, "old.txt").readText())
        assertEquals(0, copies)
        assertNoWork()
    }

    @Test fun `source changes during copying prevent promotion and remove staged files`() {
        val old = oldInstall()
        lateinit var prepared: PreparedArchiveInstall
        val installer = installer(afterCopy = {
            File(prepared.extractedRootPath, "First/Content/mod.esp").writeText("modified during copy")
        })
        prepared = installer.prepare(archive())
        assertThrows(IOException::class.java) { installer.finalizeInstall(prepared, selection(prepared)) }
        assertEquals("original", File(old, "old.txt").readText())
        assertNoWork()
    }

    @Test fun `cancellation during manual copying preserves old bytes and cleans owned session`() {
        val old = oldInstall()
        val cancellation = InstallCancellationController()
        val installer = installer(afterCopy = { cancellation.cancel() })
        val prepared = installer.prepare(archive())
        assertThrows(InstallCancelledException::class.java) {
            installer.finalizeInstall(prepared, selection(prepared), cancellation.signal)
        }
        assertEquals("original", File(old, "old.txt").readText())
        assertFalse(File(prepared.sessionRootPath).exists())
        assertNoWork()
    }

    @Test fun `ordinary simple BAIN and FOMOD preparation do not build manual inventories`() {
        val installer = installer()
        val cases = listOf(
            InstallerType.SIMPLE to mapOf("Data/mod.esp" to "plugin"),
            InstallerType.BAIN to mapOf("00 Core/mod.esp" to "plugin", "10 Optional/patch.esp" to "patch"),
            InstallerType.FOMOD to mapOf("fomod/ModuleConfig.xml" to """
                <config><moduleName>Example</moduleName>
                <group name="Main" type="SelectExactlyOne"><plugins><plugin name="Main">
                <files><file source="mod.esp" destination="mod.esp" /></files>
                <typeDescriptor><type name="Required" /></typeDescriptor>
                </plugin></plugins></group></config>
                """.trimIndent(),
                "mod.esp" to "plugin")
        )
        for ((type, contents) in cases) {
            val source = archive()
            ZipOutputStream(source.outputStream()).use { zip ->
                for ((path, text) in contents) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val prepared = installer.prepare(source)
            assertEquals(type, prepared.plan.installerType)
            assertNull(prepared.manualLayoutInventory)
            installer.cancel(prepared)
        }
        assertEquals(3, reads)
        assertEquals(0, copies)
        assertFalse(mods.exists())
    }

    private fun confirmed(
        prepared: PreparedArchiveInstall,
        name: String = "Chosen Name",
        action: ExistingModAction = ExistingModAction.ASK,
        proposal: ManualLayoutProposal = ManualLayoutProposal("First/Content"),
        allowUnknown: Boolean = false
    ) = prepared.copy(confirmation = ArchiveInstallConfirmation(name, proposal, allowUnknown, action))

    @Test fun `confirmed name is the library folder and never an inner content folder`() {
        val installer = installer()
        val prepared = installer.prepare(archive())
        val result = installer.finalizeInstall(confirmed(prepared), InstallerSelection(emptySet()))
        assertEquals("Chosen Name", result.name)
        assertTrue(File(result, "mod.esp").isFile)
        assertFalse(File(result, "Chosen Name").exists())
        assertFalse(File(mods, "Example").exists())
        assertEquals(1, reads)
    }

    @Test fun `add update keeps old only files and overwrites matches while replace removes old files`() {
        for (action in listOf(ExistingModAction.ADD_UPDATE, ExistingModAction.REPLACE)) {
            val old = oldInstall()
            File(old, "mod.esp").writeText("old plugin")
            val installer = installer()
            val prepared = installer.prepare(archive())
            val proposal = ManualLayoutProposal("First/Content").excludeSubtree("First/Content/textures/b.dds")
            val result = installer.finalizeInstall(confirmed(prepared, "Example", action, proposal), InstallerSelection(emptySet()))
            assertEquals("First/Content/mod.esp", File(result, "mod.esp").readText())
            assertEquals(action == ExistingModAction.ADD_UPDATE, File(result, "old.txt").exists())
            assertFalse(File(result, "textures/b.dds").exists())
            assertNoWork()
        }
    }

    @Test fun `invalid names and unconfirmed collisions never copy or replace`() {
        val old = oldInstall()
        val installer = installer()
        val prepared = installer.prepare(archive())
        for (name in listOf("", ".", "..", "../outside", "bad/name", "bad\\name", "_dml_backup_x", "_installing_x", " Name", "x".repeat(161), "Example")) {
            assertThrows(IOException::class.java) {
                installer.finalizeInstall(confirmed(prepared, name), InstallerSelection(emptySet()))
            }
        }
        assertEquals(0, copies)
        assertEquals("original", File(old, "old.txt").readText())
        assertNoWork()
    }

    @Test fun `unrecognized fallback needs explicit acknowledgement and can select arbitrary safe root`() {
        val source = archive()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Unusual/asset.custom"))
            zip.write("custom".toByteArray())
            zip.closeEntry()
        }
        val installer = installer()
        val prepared = installer.prepare(source)
        assertEquals(InstallerType.MANUAL_DATA_FOLDER, prepared.plan.installerType)
        assertTrue(prepared.plan.groups.isEmpty())
        val chosen = confirmed(prepared, proposal = ManualLayoutProposal("Unusual"))
        assertThrows(IOException::class.java) { installer.finalizeInstall(chosen, InstallerSelection(emptySet())) }
        assertFalse(mods.exists())
        val result = installer.finalizeInstall(chosen.copy(confirmation = chosen.confirmation!!.copy(allowUnrecognizedLayout = true)), InstallerSelection(emptySet()))
        assertEquals(setOf("asset.custom"), paths(result))
        assertEquals(1, reads)
    }

    @Test fun `merge cancellation and failed promotion leave original untouched`() {
        val old = oldInstall()
        val cancellation = InstallCancellationController()
        val cancelling = installer(afterCopy = { cancellation.cancel() })
        val first = cancelling.prepare(archive())
        assertThrows(InstallCancelledException::class.java) {
            cancelling.finalizeInstall(confirmed(first, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()), cancellation.signal)
        }
        assertEquals(setOf("old.txt"), paths(old))
        assertFalse(File(first.sessionRootPath).exists())
        assertNoWork()
        val failing = installer(failPromotion = true)
        val second = failing.prepare(archive())
        assertThrows(IOException::class.java) {
            failing.finalizeInstall(confirmed(second, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        }
        assertEquals(setOf("old.txt"), paths(old))
        assertEquals("original", File(old, "old.txt").readText())
        assertNoWork()
    }

    @Test fun `merge refuses conflicting case paths and links without changing old files`() {
        val old = oldInstall()
        File(old, "MOD.ESP").writeText("old plugin")
        val installer = installer()
        val prepared = installer.prepare(archive())
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(confirmed(prepared, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        }
        assertEquals("old plugin", File(old, "MOD.ESP").readText())
        val outside = temporary.newFile("outside")
        Files.createSymbolicLink(File(old, "linked").toPath(), outside.toPath())
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(confirmed(prepared, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        }
        assertTrue(outside.exists())
        assertNoWork()
    }

    @Test fun `merge preserves empty folders and blocks file folder conflicts`() {
        val old = oldInstall()
        File(old, "empty").mkdir()
        File(old, "mod.esp").mkdir()
        val installer = installer()
        val prepared = installer.prepare(archive())
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(confirmed(prepared, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        }
        assertTrue(File(old, "mod.esp").isDirectory)
        assertEquals("original", File(old, "old.txt").readText())
        assertNoWork()
        File(old, "mod.esp").delete()
        val result = installer.finalizeInstall(confirmed(prepared, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        assertTrue(File(result, "empty").isDirectory)
    }

    @Test fun `acknowledgement does not bypass mapping or stale session validation`() {
        val installer = installer()
        val prepared = installer.prepare(archive())
        for (proposal in listOf(ManualLayoutProposal("../outside"), ManualLayoutProposal("First/Content/mod.esp"),
            ManualLayoutProposal("First/Content", setOf("First/Content")))) {
            assertThrows(IOException::class.java) {
                installer.finalizeInstall(confirmed(prepared, proposal = proposal, allowUnknown = true), InstallerSelection(emptySet()))
            }
        }
        File(prepared.extractedRootPath, "First/Content/mod.esp").writeText("changed bytes")
        assertThrows(IOException::class.java) {
            installer.finalizeInstall(confirmed(prepared, allowUnknown = true), InstallerSelection(emptySet()))
        }
        assertEquals(0, copies)
        assertFalse(mods.exists())
    }

    @Test fun `malformed FOMOD is still a preparation failure not manual fallback`() {
        val source = archive()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fomod/ModuleConfig.xml"))
            zip.write("<config".toByteArray())
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) { installer().prepare(source) }
        assertEquals(1, reads)
        assertFalse(mods.exists())
        assertTrue(File(temporary.root, "temp/installer_sessions").listFiles().orEmpty().isEmpty())
    }

    @Test fun `managed parent aliases produce canonical owned sessions and support add update`() {
        val realTemp = temporary.newFolder("real-temp")
        val realMods = temporary.newFolder("real-mods")
        val tempAlias = File(temporary.root, "temp-alias")
        val modsAlias = File(temporary.root, "mods-alias")
        Files.createSymbolicLink(tempAlias.toPath(), realTemp.toPath())
        Files.createSymbolicLink(modsAlias.toPath(), realMods.toPath())
        File(realMods, "Example/old.txt").apply { parentFile!!.mkdirs(); writeText("old") }
        val installer = installer(tempBase = tempAlias, modsBase = modsAlias)
        val prepared = installer.prepare(archive())
        assertEquals(File(prepared.sessionRootPath).canonicalPath, prepared.sessionRootPath)
        assertTrue(prepared.manualLayoutInventory!!.entries.isNotEmpty())
        val result = installer.finalizeInstall(confirmed(prepared, "Example", ExistingModAction.ADD_UPDATE), InstallerSelection(emptySet()))
        assertEquals(File(realMods, "Example"), result)
        assertEquals("old", File(result, "old.txt").readText())
        assertTrue(File(result, "mod.esp").isFile)
        assertFalse(File(prepared.sessionRootPath).exists())
        assertEquals(1, reads)
    }

    @Test fun `manual promotion failure rolls back replacement`() {
        val old = oldInstall()
        val installer = installer(failPromotion = true)
        val prepared = installer.prepare(archive())
        assertThrows(IOException::class.java) { installer.finalizeInstall(prepared, selection(prepared)) }
        assertEquals(setOf("old.txt"), paths(old))
        assertEquals("original", File(old, "old.txt").readText())
        assertTrue(File(prepared.sessionRootPath).exists())
        assertNoWork()
    }
}
