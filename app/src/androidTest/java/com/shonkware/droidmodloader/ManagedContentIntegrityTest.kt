package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexEntry
import com.shonkware.droidmodloader.engine.index.ModFileIndexRepository
import com.shonkware.droidmodloader.engine.index.ModFileIndexSnapshot
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import com.shonkware.droidmodloader.engine.service.DeploymentService
import com.shonkware.droidmodloader.ui.ReportCard
import com.shonkware.droidmodloader.ui.workflow.ActivityThreadRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Uses only disposable app-owned fixtures, never a real profile or game installation. */
class ManagedContentIntegrityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordedContentChecksAreReadOnlyOnAndroidFilesystems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = File.createTempFile("integrity-fixture-", "", context.cacheDir)
        check(internal.delete() && internal.mkdir())
        val external = File.createTempFile("integrity-fixture-", "", checkNotNull(context.getExternalFilesDir(null)))
        check(external.delete() && external.mkdir())
        try {
            val state = File(external, "state").apply { mkdirs() }
            val mods = File(internal, "mods").apply { mkdirs() }
            val modRoot = File(mods, "fixture").apply { mkdirs() }
            val installed = File(modRoot, "test.txt").apply { writeText("old") }
            val mod = Mod("fixture", "Fixture", modRoot.path, true, 1, ModType.LOOSE)
            val modState = File(state, "installed_mods.json")
            ModStateRepository(modState).saveMods(listOf(mod))
            val hash = ManagedContentIntegrity.digest("old".toByteArray())
            val indexes = ModFileIndexRepository(File(state, "mod_file_indexes"))
            indexes.save(ModFileIndexSnapshot(1, mod.id, mod.name, mod.installPath, 1,
                listOf(ModFileIndexEntry("test.txt", "test.txt", hash, 3, 1))))
            val manifest = File(state, "deployment_manifest.json")
            val config = File(state, "game_deployment_configs.json")
            val deploy = File(external, "deploy/Data")
            val service = DeploymentService(context.filesDir, File(internal, "temp"), modState,
                manifest, deploy, config, { emptyList() }, { emptyList() })
            val targets = service.captureTargetIntegrityInputs("fallout_nv")
            targets.forEach { input ->
                input.root!!.mkdirs()
                File(input.root, "test.txt").writeText("old")
                input.evidenceFile.writeText("""[{"normalizedPath":"test.txt","hash":"$hash"}]""")
            }
            val engine = ModEngine(context, File(internal, "temp"), mods, modState, manifest,
                deploy, config, File(state, "plugins.json"), File(state, "plugins.txt"),
                File(state, "loadorder.txt"), File(external, "archives"), File(state, "downloaded_archives.json"))
            fun snapshot(): Map<String, String> = (internal.walkTopDown() + external.walkTopDown())
                .filter { it.isFile }.associate { it.path to ManagedContentIntegrity.digest(it.readBytes()) }
            val before = snapshot()
            val first = engine.verifyManagedContent("fallout_nv") { false }
            assertFalse(first.stale)
            assertTrue(first.groups.all { it.complete && it.checked == 1 && it.changed == 0 })
            assertEquals(before, snapshot())

            installed.writeText("new")
            File(targets[1].root, "test.txt").writeText("new")
            val modified = snapshot()
            val changed = engine.verifyManagedContent("fallout_nv") { false }
            assertEquals(listOf(0, 1, 1), changed.groups.map { it.changed })
            assertEquals(modified, snapshot())
            installed.delete()
            assertEquals(1, engine.verifyManagedContent("fallout_nv") { false }.groups.last().missing)
            assertTrue(engine.verifyManagedContent("fallout_nv") { true }.cancelled)

            var revised = false
            val stale = engine.verifyManagedContent("fallout_nv") {
                if (!revised) {
                    revised = true
                    val file = indexes.getIndexFile(mod)
                    file.writeText(org.json.JSONObject(file.readText()).put("createdAtEpochMillis", 2).toString())
                }
                false
            }
            assertTrue(stale.stale)
        } finally {
            check(internal.deleteRecursively())
            check(external.deleteRecursively())
        }
    }

    @Test fun explicitCheckAndCancellationRespectAdmissionAndButtonState() {
        var running by mutableStateOf(false)
        val cancelled = AtomicBoolean(false)
        val scheduled = mutableListOf<() -> Unit>()
        val runner = ActivityThreadRunner({ true }, { it() }, { scheduled += it })
        compose.setContent {
            // The production dashboard scrolls its ReportCard; use the same host constraint.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReportCard("", {}, operationInProgress = running, integrityCheckInProgress = running,
                    onVerifyManagedContent = {
                        runner.runExclusiveInBackground {
                            check(cancelled.get())
                        }
                    }, onCancelIntegrityCheck = { cancelled.set(true) })
            }
        }
        compose.onNodeWithText("Cancel File Check").performScrollTo().assertIsNotEnabled()
        repeat(2) { compose.onNodeWithText("Check Recorded Files").performScrollTo().performClick() }
        compose.runOnIdle {
            assertEquals(1, scheduled.size)
            assertTrue(runner.isOperationAdmitted)
            running = true
        }
        compose.onNodeWithText("Check Recorded Files").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel File Check").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled.get()) }
        scheduled.single().invoke()
        compose.runOnIdle { assertFalse(runner.isOperationAdmitted) }
    }
}
