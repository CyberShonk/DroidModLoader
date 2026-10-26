package com.shonkware.droidmodloader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.model.ModType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Newly created app-owned roots only. Never scans a selected source library or actual game. */
@RunWith(AndroidJUnit4::class)
class StorageDiagnosticsTest {
    @Test
    fun nativeInspectionCountsOnlyRetainedLocationAndNeverBuildsMissingSourceIndexes() = fixture { context, internal, external ->
        val state = prepare(internal, external)
        val before = snapshot(internal, external)
        val report = engine(context, internal, external).buildStorageDiagnosticSummary("fallout_nv")
        assertTrue(report.contains("historical observation"))
        assertTrue(report.contains("verified unique files: 2"))
        assertTrue(report.contains("observed logical size: 11 bytes"))
        assertTrue(report.contains("Copy budget: unknown"))
        assertTrue(report.contains("Installed mods and installation staging"))
        assertTrue(report.contains("Temporary import snapshots"))
        assertTrue(report.contains("not archive validity, allocated disk size or deletion eligibility"))
        assertEquals(before, snapshot(internal, external))
        assertFalse(File(state, "mod_file_indexes").exists())
        assertEquals("opaque duplicate history must stay untouched", File(state, "archives.json").readText())
    }

    @Test
    fun unavailableExternalAndCorruptTargetEvidenceStayUnknownWithoutFallbackOrWrites() = fixture { context, internal, external ->
        val state = prepare(internal, external)
        File(state, "config.json").writeText("broken configuration")
        val unavailable = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val before = snapshot(internal, external)
        val report = engine(unavailable, internal, external).buildStorageDiagnosticSummary("fallout_nv")
        assertTrue(report.contains("External storage is unavailable"))
        assertTrue(report.contains("observed logical size: unknown"))
        assertTrue(report.contains("no simulated fallback was assumed"))
        assertEquals(before, snapshot(internal, external))
        assertFalse(File(state, "mod_file_indexes").exists())
    }

    private fun prepare(internal: File, external: File): File {
        val state = File(internal, "state").apply { mkdirs() }
        val modRoot = File(internal, "mods/fixture").apply { mkdirs() }
        File(modRoot, "textures/user.dds").apply { parentFile!!.mkdirs(); writeText("preserved mod") }
        ModStateRepository(File(state, "mods.json")).saveMods(listOf(Mod("fixture", "Fixture", modRoot.path, true, 1, ModType.LOOSE)))
        File(state, "archives.json").writeText("opaque duplicate history must stay untouched")
        val archives = File(external, "archives").apply { mkdirs() }
        File(archives, "one.zip").writeText("first")
        File(archives, "two.zip").writeText("second")
        File(external, "selected-source/not-in-inventory.zip").apply { parentFile!!.mkdirs(); writeText("user source") }
        return state
    }

    private fun engine(context: Context, internal: File, external: File): ModEngine {
        val state = File(internal, "state")
        return ModEngine(context, File(internal, "temp"), File(internal, "mods"), File(state, "mods.json"),
            File(state, "manifest.json"), File(external, "deploy/Data"), File(state, "config.json"),
            File(state, "plugins.json"), File(state, "plugins.txt"), File(state, "loadorder.txt"),
            File(external, "archives"), File(state, "archives.json"))
    }

    private fun snapshot(vararg roots: File) = roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }
        .associate { it.path to (it.readBytes().toList() to it.lastModified()) }

    private fun fixture(test: (Context, File, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Files.createTempDirectory(context.cacheDir.toPath(), "storage-inspect-fixture-").toFile()
        var external: File? = null
        try {
            external = Files.createTempDirectory(checkNotNull(context.getExternalFilesDir(null)).toPath(), "storage-inspect-fixture-").toFile()
            test(context, internal, external)
        } finally {
            check(internal.deleteRecursively())
            external?.let { check(it.deleteRecursively()) }
        }
    }
}
