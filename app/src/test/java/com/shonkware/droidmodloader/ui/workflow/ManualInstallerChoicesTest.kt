package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.install.*
import com.shonkware.droidmodloader.ui.*
import org.junit.Assert.*
import org.junit.Test

class ManualInstallerChoicesTest {
    private fun prepared(): PreparedArchiveInstall {
        val entries = listOf(
            ManualLayoutEntry("Wrapper", true, 0, 0),
            ManualLayoutEntry("Wrapper/Content", true, 0, 0),
            ManualLayoutEntry("Wrapper/Content/mod.esp", false, 3, 0),
            ManualLayoutEntry("Wrapper/Content/textures", true, 0, 0),
            ManualLayoutEntry("Wrapper/Content/textures/a.dds", false, 4, 0),
            ManualLayoutEntry("Wrapper/Content/textures/b.dds", false, 4, 0),
            ManualLayoutEntry("readme.txt", false, 2, 0)
        )
        return PreparedArchiveInstall("/missing/source.zip", "Source.zip", "Source", "/session",
            "/session/raw", "/session/raw/Wrapper/Content",
            InstallerPlan(InstallerType.SIMPLE, "Source", "/session/raw", emptyList()),
            manualLayoutInventory = ManualLayoutInventory("/session", "/session/raw", entries))
    }

    @Test fun `tree edits reuse cached inventory without the source archive or filesystem`() {
        val prepared = prepared()
        val (updated, choices) = editManualInstallerChoices(prepared, InstallerDialogState(modName = "Chosen Name"),
            emptySet(), InstallerDialogAction.ChooseFiles)
        assertSame(prepared.manualLayoutInventory, updated.manualLayoutInventory)
        assertEquals("Chosen Name", choices.modName)
        assertEquals(InstallerPage.FILES, choices.page)
        assertEquals(listOf("Wrapper", "readme.txt"), choices.rows.map { it.path })
        val (_, expanded) = editManualInstallerChoices(updated, choices, emptySet(), InstallerDialogAction.Expand("Wrapper"))
        assertEquals(listOf("Wrapper", "Wrapper/Content", "readme.txt"), expanded.rows.map { it.path })
    }

    @Test fun `root and exclusion projection matches the engine proposal`() {
        val prepared = prepared()
        val (_, rooted) = editManualInstallerChoices(prepared, InstallerDialogState(modName = "Named"),
            emptySet(), InstallerDialogAction.Root("Wrapper/Content"))
        assertEquals(listOf("Wrapper/Content/mod.esp", "Wrapper/Content/textures"), rooted.rows.map { it.path })
        val (_, excluded) = editManualInstallerChoices(prepared, rooted, emptySet(),
            InstallerDialogAction.Include("Wrapper/Content/textures", false))
        assertFalse(excluded.rows.last().included)
        val (_, expanded) = editManualInstallerChoices(prepared, excluded, emptySet(),
            InstallerDialogAction.Expand("Wrapper/Content/textures"))
        assertTrue(expanded.rows.filter { it.depth == 1 }.all { !it.included && !it.enabled })
        val preview = prepared.manualLayoutInventory!!.preview(excluded.proposal!!)
        assertEquals(listOf("mod.esp"), preview.files.map { it.destinationPath })
        val (_, restored) = editManualInstallerChoices(prepared, expanded, emptySet(),
            InstallerDialogAction.Include("Wrapper/Content/textures", true))
        assertTrue(restored.rows.all { it.included && it.enabled })
    }

    @Test fun `partial parent and reset are explicit and keep the draft name`() {
        val prepared = prepared()
        val initial = InstallerDialogState(modName = "Named", proposal = ManualLayoutProposal("Wrapper/Content"),
            expanded = setOf("Wrapper/Content/textures"))
        val (_, partial) = editManualInstallerChoices(prepared, initial, emptySet(),
            InstallerDialogAction.Include("Wrapper/Content/textures/a.dds", false))
        assertTrue(partial.rows.single { it.path == "Wrapper/Content/textures" }.partial)
        val (_, reset) = editManualInstallerChoices(prepared, partial, emptySet(), InstallerDialogAction.Root(""))
        assertEquals(ManualLayoutProposal(), reset.proposal)
        assertTrue(reset.expanded.isEmpty())
        assertEquals("Named", reset.modName)
    }

    @Test fun `empty selection has a blocking layout error independent of name edits and back`() {
        val prepared = prepared()
        val initial = InstallerDialogState(modName = "Named", proposal = ManualLayoutProposal("Wrapper/Content"))
        val (_, noTextures) = editManualInstallerChoices(prepared, initial, emptySet(),
            InstallerDialogAction.Include("Wrapper/Content/textures", false))
        val (_, empty) = editManualInstallerChoices(prepared, noTextures, emptySet(),
            InstallerDialogAction.Include("Wrapper/Content/mod.esp", false))
        assertNotNull(empty.layoutError)
        assertEquals(empty.layoutError, empty.copy(page = InstallerPage.NAME, modName = "Another Name", error = null).layoutError)
    }

    @Test fun `mapping edits invalidate acknowledgement but expansion does not`() {
        val prepared = prepared()
        val initial = InstallerDialogState(modName = "Named", proposal = ManualLayoutProposal(),
            allowUnrecognized = true, existingAction = ExistingModAction.ADD_UPDATE)
        val (_, expanded) = editManualInstallerChoices(prepared, initial, emptySet(), InstallerDialogAction.Expand("Wrapper"))
        assertTrue(expanded.allowUnrecognized)
        assertEquals(ExistingModAction.ADD_UPDATE, expanded.existingAction)
        val (_, edited) = editManualInstallerChoices(prepared, expanded, emptySet(),
            InstallerDialogAction.Include("readme.txt", false))
        assertFalse(edited.allowUnrecognized)
        assertEquals(ExistingModAction.ASK, edited.existingAction)
    }

    @Test fun `cancelled editor work does not publish a partial tree`() {
        val cancellation = InstallCancellationController().apply { cancel() }
        assertThrows(InstallCancelledException::class.java) {
            editManualInstallerChoices(prepared(), InstallerDialogState(), emptySet(),
                InstallerDialogAction.ChooseFiles, cancellation.signal)
        }
        assertThrows(InstallCancelledException::class.java) {
            manualInstallerRows(prepared().manualLayoutInventory!!, ManualLayoutProposal(), emptySet(), cancellation.signal)
        }
    }

    @Test fun `existing candidate is a shortcut and does not hide archive siblings after reset`() {
        val prepared = prepared().let { it.copy(plan = it.plan.copy(installerType = InstallerType.MANUAL_DATA_FOLDER,
            groups = listOf(InstallerGroup("root", "Root", InstallerGroupType.SELECT_EXACTLY_ONE,
                listOf(InstallerOption("candidate", "Candidate", "", ".", "", false, true)))))) }
        val (_, choices) = editManualInstallerChoices(prepared, InstallerDialogState(), setOf("candidate"), InstallerDialogAction.ChooseFiles)
        assertEquals("Wrapper/Content", choices.proposal!!.rootPath)
        val (_, reset) = editManualInstallerChoices(prepared, choices, setOf("candidate"), InstallerDialogAction.Root(""))
        assertTrue(reset.rows.any { it.path == "readme.txt" })
    }
}
