package com.shonkware.droidmodloader

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidator
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessManager
import com.shonkware.droidmodloader.ui.workflow.AppDiagnosticInfo
import com.shonkware.droidmodloader.ui.workflow.EnvironmentDiagnostics
import com.shonkware.droidmodloader.ui.workflow.OperationContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Disposable app-owned fixtures only; no selected game targets or Android permission changes. */
class EnvironmentDiagnosticsTest {
    @Test fun nativeMarkerMatrixUsesExistingRulesWithBoundedEnumerationAndNoWrites() = fixture { root ->
        val markers = mapOf(
            "skyrim_le" to ("TESV.exe" to "Skyrim.esm"),
            "oblivion" to ("Oblivion.exe" to "Oblivion.esm"),
            "fallout_3" to ("Fallout3.exe" to "Fallout3.esm"),
            "fallout_nv" to ("FalloutNV.exe" to "FalloutNV.esm"),
            "ttw" to ("FalloutNV.exe" to "FalloutNV.esm")
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val app = AppDiagnosticInfo(info.versionName ?: "unknown", info.longVersionCode, context.packageName,
            Build.VERSION.RELEASE, Build.MODEL, Build.VERSION.SDK_INT)
        markers.forEach { (game, names) ->
            val gameRoot = File(root, game).apply { mkdir() }
            File(gameRoot, names.first).writeText("synthetic marker, not executable")
            val data = File(gameRoot, "Data").apply { mkdir() }
            File(data, names.second).writeText("synthetic marker, not master data")
            val before = snapshot(gameRoot)
            val observation = EnvironmentDiagnostics.inspect(
                OperationContext("synthetic", game, false, data.path, gameRoot.path), app,
                AllFilesAccessManager(context).state()
            )
            assertTrue(observation.targets.all { it.canDeploy })
            assertTrue(observation.toSummary().contains("API=${Build.VERSION.SDK_INT}"))
            assertTrue(observation.toSummary().contains("Runtime compatibility: unverified"))
            assertEquals(before, snapshot(gameRoot))
            val limited = GameTargetValidator().validateTarget(game, GameTargetType.GAME_ROOT, gameRoot.path, 1)
            assertEquals("TARGET_INSPECTION_LIMIT", limited.findings.single().code)
            assertFalse(limited.canDeploy)
        }
    }

    @Test fun unreadableFixtureIsUnavailableNotMissingMarkersAndDoesNotChangeAndroidPermission() = fixture { root ->
        val directory = File(root, "Data").apply { mkdir() }
        File(directory, "Skyrim.esm").writeText("synthetic")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permission = AllFilesAccessManager(context).state()
        try {
            check(directory.setReadable(false, false))
            assertFalse(directory.canRead())
            val result = GameTargetValidator().validateTarget("skyrim_le", GameTargetType.DATA, directory.path, 4096)
            assertFalse(result.canDeploy)
            assertEquals("TARGET_PATH_INVALID", result.findings.single().code)
            assertTrue(result.findings.single().details.contains("not readable"))
        } finally {
            check(directory.setReadable(true, true))
        }
        assertEquals(permission, AllFilesAccessManager(context).state())
        assertEquals("synthetic", File(directory, "Skyrim.esm").readText())
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.path to (it.readBytes().toList() to it.lastModified()) }

    private fun fixture(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "environment-fixture-").toFile()
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
}
