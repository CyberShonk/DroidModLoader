package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidator
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvironmentDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = AppDiagnosticInfo("0.8-test", 42, "test.package", "16", "fixture", 36)
    private val rules = mapOf(
        "skyrim_le" to ("TESV.exe" to "Skyrim.esm"),
        "oblivion" to ("Oblivion.exe" to "Oblivion.esm"),
        "fallout_3" to ("Fallout3.exe" to "Fallout3.esm"),
        "fallout_nv" to ("FalloutNV.exe" to "FalloutNV.esm"),
        "ttw" to ("FalloutNV.exe" to "FalloutNV.esm")
    )

    @Test fun `each supported game uses existing setup rules without inferring runtime support`() {
        assertEquals(GameCatalog.supportedGameIds.toSet(), rules.keys)
        rules.forEach { (game, markers) ->
            val context = installation(game, markers)
            val report = inspect(context)
            assertTrue(report.targets.all { it.canDeploy })
            assertTrue(report.relationship.any { it.code == "TARGET_RELATIONSHIP_VALID" })
            val text = report.toSummary()
            assertTrue(text.contains("App: 0.8-test (42)"))
            assertTrue(text.contains("API=36"))
            assertTrue(text.contains("Game/executable version: unavailable"))
            assertTrue(text.contains("Runtime compatibility: unverified"))
            assertTrue(text.contains("captured folder intent"))
            assertFalse(text.contains("RESULT: PASS"))
        }
    }

    @Test fun `TTW marker is optional before initial deployment and presence is not a version`() {
        val context = installation("ttw", rules.getValue("ttw"))
        assertTrue(inspect(context).targets.flatMap { it.findings }.any { it.code == "TTW_MARKER_NOT_YET_PRESENT" })
        File(context.dataTarget, "TaleOfTwoWastelands.esm").writeText("")
        val report = inspect(context)
        assertTrue(report.targets.all { it.canDeploy })
        assertTrue(report.targets.flatMap { it.findings }.any { it.code == "TTW_MARKER_PRESENT" })
        assertTrue(report.toSummary().contains("not readable contents, file integrity, game version"))
    }

    @Test fun `missing marker differs from unavailable folder`() {
        val context = installation("skyrim_le", rules.getValue("skyrim_le"))
        File(context.dataTarget, "Skyrim.esm").delete()
        assertTrue(inspect(context).targets.first().findings.any { it.code == "GAME_MARKERS_MISSING" })
        File(context.dataTarget).delete()
        val unavailable = inspect(context).targets.first()
        assertTrue(unavailable.findings.any { it.code == "TARGET_PATH_INVALID" })
        assertFalse(unavailable.findings.any { it.code == "GAME_MARKERS_MISSING" })
    }

    @Test fun `unsupported game and unavailable context cannot claim target evidence`() {
        val context = installation("skyrim_le", rules.getValue("skyrim_le"))
        assertTrue(inspect(context.copy(gameId = "unsupported")).targets.all {
            !it.canDeploy && it.findings.single().code == "UNKNOWN_GAME"
        })
        val unavailable = inspect(context.copy(unavailable = true))
        assertTrue(unavailable.targets.isEmpty())
        assertTrue(unavailable.toSummary().contains("Target checks unavailable"))
    }

    @Test fun `bounded enumeration counts directories and does not infer absence from partial listing`() {
        val folder = temporary.newFolder()
        File(folder, "nested").mkdir()
        File(folder, "other").writeText("")
        val result = GameTargetValidator().validateTarget("skyrim_le", GameTargetType.DATA, folder.path, 1)
        assertEquals("TARGET_INSPECTION_LIMIT", result.findings.single().code)
        assertFalse(result.canDeploy)
        assertFalse(result.toDebugSummary().contains("Missing required marker"))
        // Diagnostic opt-in must not alter ordinary deployment validation.
        assertEquals("GAME_MARKERS_MISSING", GameTargetValidator()
            .validateTarget("skyrim_le", GameTargetType.DATA, folder.path).findings.single().code)
    }

    @Test fun `exact entry limit and case insensitive marker preserve successful validation`() {
        val folder = temporary.newFolder("Data")
        File(folder, "SKYRIM.ESM").writeText("")
        val result = GameTargetValidator().validateTarget("skyrim_le", GameTargetType.DATA, folder.path, 1)
        assertTrue(result.canDeploy)
    }

    @Test fun `production cap leaves no partial target validity verdict`() {
        val context = installation("skyrim_le", rules.getValue("skyrim_le"))
        repeat(EnvironmentDiagnostics.MAX_TARGET_ENTRIES) { File(context.dataTarget, "entry$it").createNewFile() }
        val report = inspect(context)
        assertEquals("TARGET_INSPECTION_LIMIT", report.targets.first().findings.single().code)
        assertTrue(report.relationship.isEmpty())
    }

    @Test fun `simulated intent and denied permission are independent from actual folder access`() {
        val context = installation("fallout_nv", rules.getValue("fallout_nv")).copy(realDeployment = false)
        val report = EnvironmentDiagnostics.inspect(context, app, AllFilesAccessState.DENIED, observedAtMillis = 123)
        assertTrue(report.targets.all { it.canDeploy })
        assertTrue(report.toSummary().contains("Deployment intent: simulated"))
        assertTrue(report.toSummary().contains("All-files access: DENIED"))
        assertTrue(report.toSummary().contains("historical observation at 123"))
        assertTrue(report.toSummary().contains("not proof of access to any particular folder"))
    }

    @Test fun `inspection reads only and does not descend into nested content`() {
        val context = installation("oblivion", rules.getValue("oblivion"))
        val nested = File(context.dataTarget, "nested").apply { mkdir() }
        File(nested, "must-remain.txt").writeText("unchanged")
        val before = File(context.rootTarget).walkTopDown().map { it.relativeTo(File(context.rootTarget)).path to it.length() }.toList()
        val report = inspect(context)
        val after = File(context.rootTarget).walkTopDown().map { it.relativeTo(File(context.rootTarget)).path to it.length() }.toList()
        assertEquals(before, after)
        assertTrue(report.targets.all { it.canDeploy })
        assertEquals("unchanged", File(nested, "must-remain.txt").readText())
    }

    @Test fun `superseded profile game mode targets and permission discard all observation facts`() {
        val context = installation("fallout_nv", rules.getValue("fallout_nv"))
        val report = inspect(context)
        listOf(context.copy(profileId = "other"), context.copy(gameId = "oblivion"),
            context.copy(realDeployment = false), context.copy(dataTarget = "/new/Data"),
            context.copy(rootTarget = "/new")).forEach { changed ->
            val log = mutableListOf<String>()
            var current = context
            coordinator(log).inspectEnvironment({ current = changed; report }, { current == context })
            assertDiscarded(log)
        }
        val log = mutableListOf<String>()
        var permission = AllFilesAccessState.GRANTED
        coordinator(log).inspectEnvironment({ permission = AllFilesAccessState.DENIED; report }, { permission == report.allFilesAccess })
        assertDiscarded(log)
    }

    @Test fun `current observation logs historical evidence without health refresh or pass claim`() {
        val log = mutableListOf<String>()
        coordinator(log).inspectEnvironment({ inspect(installation("oblivion", rules.getValue("oblivion"))) }, { true })
        assertTrue(log.any { it.contains("Environment facts (historical observation") })
        assertTrue(log.any { it.contains("runtime compatibility remain unverified") })
        assertFalse(log.contains("RESULT: PASS"))
    }

    @Test fun `inspection failure does not claim collected evidence`() {
        val log = mutableListOf<String>()
        coordinator(log).inspectEnvironment({ throw SecurityException("permission changed") }, { true })
        assertTrue(log.any { it.startsWith("Environment inspection failed:") })
        assertFalse(log.any { it.contains("Environment facts (") || it.contains("observation collected") })
    }

    private fun inspect(context: OperationContext) = EnvironmentDiagnostics.inspect(
        context, app, AllFilesAccessState.GRANTED, observedAtMillis = 123
    )

    private fun installation(game: String, markers: Pair<String, String>): OperationContext {
        val root = temporary.newFolder()
        File(root, markers.first).writeText("")
        val data = File(root, "Data").apply { mkdir() }
        File(data, markers.second).writeText("")
        return OperationContext("profile-a", game, true, data.path, root.path)
    }

    private fun assertDiscarded(log: List<String>) {
        assertTrue(log.any { it.startsWith("Environment facts discarded:") })
        assertFalse(log.any { it.startsWith("Scope:") || it.startsWith("Environment facts (") || it.startsWith("App:") })
    }

    private fun coordinator(log: MutableList<String>) = DeveloperDiagnosticsCoordinator(
        { false }, { error("Must not create engine") }, { error("Must not read mutable game") },
        log::add, { message, _ -> log.add(message) }, log::add, log::add,
        { message, _ -> log.add(message) }, { error("Must not refresh health") }
    )
}
