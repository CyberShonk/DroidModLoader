package com.shonkware.droidmodloader.engine.install

import com.shonkware.droidmodloader.engine.model.DeployScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ManualLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tree(): File = temporary.newFolder("session").let { File(it, "raw").apply { mkdir() } }
    private fun file(root: File, path: String, text: String = "abc") = File(root, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }
    private fun inventory(root: File, limits: ExtractionLimits = ExtractionLimits()) =
        ManualLayoutInventory.capture(root.parentFile, root, limits = limits)
    private fun rejected(action: () -> Unit) {
        assertThrows(IOException::class.java) { action() }
    }

    @Test fun `nested root preview strips only chosen prefix and reset restores archive root`() {
        val root = tree()
        file(root, "wrapped/Data/textures/a.dds")
        file(root, "outside.txt")
        val inventory = inventory(root)
        val selected = ManualLayoutProposal().selectRoot("wrapped/Data")
        val preview = inventory.preview(selected)
        assertEquals(listOf("textures/a.dds"), preview.files.map { it.destinationPath })
        assertEquals(listOf("wrapped/Data/textures/a.dds"), preview.files.map { it.sourcePath })
        assertEquals(3L, preview.totalBytes)
        assertEquals(DeployScope.DATA, preview.files.single().deployScope)
        assertTrue(preview.recognizedLayout)
        assertEquals(2, inventory.preview(selected.selectRoot("")).files.size)
        assertEquals(preview, inventory.preview(selected))
    }

    @Test fun `exclude and include subtrees preserve partial parents and root change resets choices`() {
        val root = tree()
        file(root, "Data/textures/a.dds")
        file(root, "Data/textures/b.dds")
        file(root, "Data/mod.esp")
        val inventory = inventory(root)
        val all = ManualLayoutProposal("Data")
        val partial = all.excludeSubtree("Data/textures/a.dds")
        assertEquals(listOf("mod.esp", "textures/b.dds"), inventory.preview(partial).files.map { it.destinationPath })
        assertEquals(3, inventory.preview(partial.includeSubtree("Data/textures/a.dds")).files.size)
        val excluded = all.excludeSubtree("Data/textures")
        assertEquals(listOf("mod.esp"), inventory.preview(excluded).files.map { it.destinationPath })
        rejected { excluded.includeSubtree("Data/textures/a.dds") }
        assertEquals(3, inventory.preview(excluded.includeSubtree("Data/textures")).files.size)
        assertTrue(excluded.selectRoot("").excludedPaths.isEmpty())
    }

    @Test fun `per file observation accepts unchanged metadata and rejects timestamp changes and new links`() {
        val root = tree()
        val source = file(root, "mod.esp")
        assertTrue(source.setLastModified(1_700_000_000_321L))
        val inventory = inventory(root)
        val selected = inventory.preview(ManualLayoutProposal()).files.single()
        assertEquals(source, inventory.sourceFile(selected))
        assertTrue(source.setLastModified(1_700_000_010_321L))
        rejected { inventory.sourceFile(selected) }
        assertTrue(source.delete())
        val outside = temporary.newFile("outside")
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        rejected { inventory.sourceFile(selected) }
        assertTrue(outside.exists())
    }

    @Test fun `unrecognized files are safe mappings not proof of game compatibility`() {
        val root = tree()
        file(root, "unusual/custom.payload")
        val preview = inventory(root).preview(ManualLayoutProposal("unusual"))
        assertFalse(preview.recognizedLayout)
        assertEquals("custom.payload", preview.files.single().destinationPath)
    }

    @Test fun `preview keeps existing mixed root and Data routing`() {
        val root = tree()
        file(root, "nvse_loader.exe")
        file(root, "Data/nvse/plugins/plugin.dll")
        val preview = inventory(root).preview(ManualLayoutProposal())
        assertEquals(setOf(DeployScope.DATA, DeployScope.GAME_ROOT), preview.files.map { it.deployScope }.toSet())
    }

    @Test fun `empty selection file root missing root and invalid exclusions are blocked`() {
        val root = tree()
        file(root, "Data/mod.esp")
        file(root, "outside.txt")
        val inventory = inventory(root)
        for (proposal in listOf(
            ManualLayoutProposal("Data/mod.esp"), ManualLayoutProposal("missing"),
            ManualLayoutProposal("Data", setOf("Data")),
            ManualLayoutProposal("Data", setOf("outside.txt")),
            ManualLayoutProposal("Data", setOf("Data/missing"))
        )) rejected { inventory.preview(proposal) }
        val empty = File(root, "empty").apply { mkdir() }
        rejected { inventory(root).preview(ManualLayoutProposal(empty.name)) }
    }

    @Test fun `noncanonical traversal absolute and excessive proposal paths are blocked`() {
        val root = tree()
        file(root, "mod.esp")
        val inventory = inventory(root)
        for (path in listOf("../raw", "/absolute", "C:/drive", "./", ".", "a/../b", "a//b", "a\\b", "a\u0000b", "x".repeat(1025))) {
            rejected { inventory.preview(ManualLayoutProposal(path)) }
            rejected { inventory.preview(ManualLayoutProposal(excludedPaths = setOf(path))) }
        }
    }

    @Test fun `crafted inventories revalidate collisions and parent conflicts before mapping`() {
        val root = tree()
        val base = inventory(root)
        fun entry(path: String, directory: Boolean = false) = ManualLayoutEntry(path, directory, if (directory) 0 else 1, 0)
        for (entries in listOf(
            listOf(entry("mod.esp"), entry("mod.esp")),
            listOf(entry("Mod.esp"), entry("mod.esp")),
            listOf(entry("caf\u00e9.esp"), entry("cafe\u0301.esp")),
            listOf(entry("dir"), entry("dir/mod.esp")),
            listOf(entry("dir/mod.esp")),
            listOf(entry("../escape.esp")),
            listOf(entry("/escape.esp")),
            listOf(entry("dir", true), entry("DIR", true)),
            listOf(entry("mod.esp").copy(sizeBytes = -1))
        )) rejected { base.copy(entries = entries).preview(ManualLayoutProposal()) }
    }

    @Test fun `capture and crafted previews enforce count path file total and depth limits`() {
        val root = tree()
        file(root, "dir/a.esp", "123")
        file(root, "dir/b.esp", "456")
        val limits = listOf(
            ExtractionLimits(maxEntries = 2),
            ExtractionLimits(maxRelativePathCharacters = 4),
            ExtractionLimits(maxFileBytes = 2, maxTotalBytes = 4),
            ExtractionLimits(maxFileBytes = 3, maxTotalBytes = 5)
        )
        for (limit in limits) rejected { inventory(root, limit) }
        val base = inventory(root)
        for (limit in limits) {
            rejected {
                ManualLayoutInventory(base.sessionRootPath, base.extractedRootPath, base.entries, limit)
                    .preview(ManualLayoutProposal())
            }
        }
        val deep = (1..65).joinToString("/") { "d" }
        file(root, "$deep/a.esp")
        rejected { inventory(root) }
    }

    @Test fun `links within and outside session and linked roots are refused`() {
        val root = tree()
        val source = file(root, "mod.esp")
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        rejected { inventory(root) }
        Files.delete(link.toPath())
        Files.createSymbolicLink(link.toPath(), temporary.root.toPath())
        rejected { inventory(root) }
        val linkedRoot = File(root.parentFile, "alias")
        Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
        rejected { inventory(linkedRoot) }
        rejected { ManualLayoutInventory.capture(root.parentFile, temporary.root) }
    }

    @Test fun `changed removed added and missing sessions are not current`() {
        val root = tree()
        val source = file(root, "mod.esp")
        val inventory = inventory(root)
        inventory.requireCurrent()
        source.writeText("longer")
        rejected { inventory.requireCurrent() }
        val updated = inventory(root)
        val added = file(root, "added.esp")
        rejected { updated.requireCurrent() }
        added.delete()
        val beforeRemoval = inventory(root)
        source.delete()
        rejected { beforeRemoval.requireCurrent() }
        root.deleteRecursively()
        rejected { inventory.requireCurrent() }
    }

    @Test fun `cancellation stops inventory preview and freshness checks`() {
        val root = tree()
        file(root, "mod.esp")
        val inventory = inventory(root)
        val controller = InstallCancellationController().apply { cancel() }
        assertThrows(InstallCancelledException::class.java) {
            ManualLayoutInventory.capture(root.parentFile, root, controller.signal)
        }
        assertThrows(InstallCancelledException::class.java) { inventory.preview(ManualLayoutProposal(), controller.signal) }
        assertThrows(InstallCancelledException::class.java) { inventory.requireCurrent(controller.signal) }
    }
}
