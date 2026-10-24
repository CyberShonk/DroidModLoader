package com.shonkware.droidmodloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.attention.*
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.ModStateRepository
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.health.*
import com.shonkware.droidmodloader.engine.deploy.journal.*
import com.shonkware.droidmodloader.engine.index.*
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
import com.shonkware.droidmodloader.engine.model.*
import com.shonkware.droidmodloader.engine.service.DeploymentService
import com.shonkware.droidmodloader.ui.ReportCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Disposable fixture state only; never selects, deploys to or repairs an actual game. */
class DeploymentHealthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun engineHealthDoesNotRebuildEvidenceAndReviewCannotReviveAnOldMatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = File.createTempFile("health-fixture-", "", context.cacheDir)
        check(internal.delete() && internal.mkdir())
        val external = File.createTempFile("health-fixture-", "", checkNotNull(context.getExternalFilesDir(null)))
        check(external.delete() && external.mkdir())
        try {
            val state = File(external, "profile").apply { mkdirs() }
            val mods = File(internal, "mods").apply { mkdirs() }
            val root = File(mods, "fixture").apply { mkdirs() }
            val mod = Mod("fixture", "Fixture", root.path, true, 1, ModType.MIXED)
            val paths = listOf("textures/test.dds", "nvse_loader.exe")
            val sourceFiles = paths.map { File(root, it).apply { parentFile!!.mkdirs(); writeText("old") } }
            val hash = ManagedContentIntegrity.digest("old".toByteArray())
            val stateFile = File(state, "installed_mods.json")
            val stateRepo = ModStateRepository(stateFile)
            stateRepo.saveMods(listOf(mod))
            val indexRepo = ModFileIndexRepository(File(state, "mod_file_indexes"))
            indexRepo.save(ModFileIndexSnapshot(1, mod.id, mod.name, mod.installPath, 1,
                sourceFiles.mapIndexed { i, file -> ModFileIndexEntry(paths[i], paths[i], hash, file.length(), file.lastModified()) }))
            val indexFile = indexRepo.getIndexFile(mod)
            val originalIndex = indexFile.readText()
            val configFile = File(state, "game_deployment_configs.json")
            val manifestFile = File(state, "deployment_manifest.json")
            val deploy = File(external, "deploy/Data")
            val targets = DeploymentService(context.filesDir, File(internal, "temp"), stateFile,
                manifestFile, deploy, configFile, { emptyList() }, { emptyList() })
                .captureTargetIntegrityInputs("fallout_nv")
            targets.forEachIndexed { index, target ->
                File(target.root, paths[index]).apply { parentFile!!.mkdirs(); writeText("old") }
                DeploymentManifestRepository(target.evidenceFile).save(listOf(
                    DeploymentRecord(paths[index], mod.id, sourceFiles[index].path, mod.name, hash)))
            }
            val engine = ModEngine(context, File(internal, "temp"), mods, stateFile, manifestFile, deploy,
                configFile, File(state, "plugins.json"), File(state, "plugins.txt"), File(state, "loadorder.txt"),
                File(external, "archives"), File(state, "downloaded_archives.json"))
            fun indexes() = mapOf(mod.id to engine.indexModContent(mod))
            fun health(observation: ScopedIntegrityObservation? = null, profile: String = "fixture-profile") =
                engine.buildDeploymentHealth(profile, "fallout_nv", observation, indexes())
            fun observe(): ScopedIntegrityObservation {
                val revision = engine.deploymentHealthJournalRevision("fallout_nv")
                return ScopedIntegrityObservation("fixture-profile", "fallout_nv",
                    engine.verifyManagedContent("fallout_nv") { false }, revision)
            }
            fun snapshot(): Map<String, String> = (internal.walkTopDown() + external.walkTopDown())
                .filter { it.isFile }.associate { it.path to ManagedContentIntegrity.digest(it.readBytes()) }
            val before = snapshot()
            val initial = health()
            val diagnostics = initial.toDebugSummary() + "\nRecorded index: " + originalIndex +
                "\nCurrent paths: " + indexes().values.flatMap { it.entries }.map { it.originalPath } +
                "\nSource signatures: " + sourceFiles.map { file ->
                    val attributes = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                    "${file.name}: regular=${attributes.isRegularFile}, size=${attributes.size()}, modified=${attributes.lastModifiedTime().toMillis()}, fileModified=${file.lastModified()}"
                }
            assertEquals(diagnostics, DeploymentHealthPhase.UNVERIFIED, initial.phase)
            val observation = observe()
            assertEquals(DeploymentHealthPhase.MATCHED_AT_CHECK, health(observation).phase)
            assertEquals(4, health(observation).integrity.checked)
            assertEquals(before, snapshot())
            assertEquals(DeploymentIntegrityFact.STALE, health(observation, "other-profile").integrity.fact)

            stateRepo.saveMods(listOf(mod.copy(enabled = false)))
            assertEquals(DeploymentHealthPhase.PENDING_CHANGES, health(observation).phase)
            assertEquals(2, health(observation).targets.sumOf { it.pendingChanges!! })
            stateRepo.saveMods(listOf(mod))

            val journalRepo = DeploymentJournalRepository(File(state, "deployment_journal_fallout_nv.json"))
            journalRepo.saveStarted(DeploymentJournalRecord(operationId = "health-fixture", gameId = "fallout_nv",
                profileId = state.name, status = DeploymentJournalStatus.STARTED, startedAtEpochMillis = 1,
                completedAtEpochMillis = null, planSummary = DeploymentJournalPlanSummary(0, 0, 0, null, null, true, 0, 0),
                resultSummary = null, failureMessage = null))
            assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, health(observation).phase)
            assertTrue(engine.markDeploymentJournalReviewed("fallout_nv"))
            val reviewed = health(observation)
            assertEquals(DeploymentHealthPhase.INTEGRITY_STALE, reviewed.phase)
            assertTrue(DeploymentHealthReason.REVIEWED_NOT_REPAIRED in reviewed.reasons)
            val fresh = observe()
            assertEquals(DeploymentHealthPhase.MATCHED_AT_CHECK, health(fresh).phase)

            val recordedTime = org.json.JSONObject(originalIndex).getJSONArray("entries").getJSONObject(0).getLong("modifiedEpochMillis")
            sourceFiles[0].writeText("new")
            check(sourceFiles[0].setLastModified(recordedTime))
            assertEquals(DeploymentIntegrityFact.MATCHED_AT_CHECK, health(fresh).integrity.fact)
            assertEquals(DeploymentHealthPhase.INTEGRITY_DRIFT, health(observe()).phase)
            assertEquals(originalIndex, indexFile.readText())
            sourceFiles[0].writeText("modified")
            val changed = snapshot()
            assertEquals(DeploymentHealthPhase.UNKNOWN, health(fresh).phase)
            assertTrue(DeploymentHealthReason.PLAN_UNKNOWN in health(fresh).reasons)
            assertEquals(DeploymentHealthPhase.INTEGRITY_DRIFT, health(observe()).phase)
            assertEquals(changed, snapshot())
            assertEquals(originalIndex, indexFile.readText())
            sourceFiles[0].writeText("old")
            check(sourceFiles[0].setLastModified(recordedTime))
            val added = File(root, "textures/unrecorded.dds").apply { writeText("new") }
            assertEquals(DeploymentHealthPhase.UNKNOWN, health().phase)
            check(added.delete())
            check(indexFile.delete())
            val noIndex = snapshot()
            assertEquals(DeploymentHealthPhase.UNKNOWN, health().phase)
            assertEquals(noIndex, snapshot())
            assertFalse(indexFile.exists())
        } finally {
            check(internal.deleteRecursively())
            check(external.deleteRecursively())
        }
    }

    @Test fun compactDetailsAndAttentionConsumeTheSameProjection() {
        val facts = DeploymentHealthFacts("fixture-profile", "fallout_nv", GameTargetType.entries.map {
            DeploymentTargetHealth(it, null, TargetReadiness.UNKNOWN, null)
        }, DeploymentRecoveryFact.NONE, DeploymentIntegrityEvidence(), null, 1)
        var health by mutableStateOf(DeploymentHealthProjector.project(facts))
        var refreshes = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReportCard("", {}, deploymentHealth = health, onRefreshDeploymentHealth = {
                    refreshes++
                    health = DeploymentHealthProjector.project(facts.copy(targets = facts.targets.map {
                        it.copy(readiness = TargetReadiness.UNAVAILABLE, reselectionRequired = true)
                    }))
                })
            }
        }
        compose.onNodeWithText("Show Deployment Health Details").performClick()
        compose.onNodeWithText(health.toDebugSummary()).assertIsDisplayed()
        compose.onNodeWithText("Refresh Deployment Evidence").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(DeploymentHealthPhase.TARGET_UNAVAILABLE, health.phase)
            val attention = AppAttentionProjector.project(AppAttentionProjectionInput(true,
                "fixture-profile", "fallout_nv", health))
            assertEquals(AppAttentionKind.GAME_INSTALLATION_RESELECTION, attention.single().kind)
        }
        compose.onNodeWithText("Deployment Health: ${health.phase.summary}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(health.toDebugSummary()).assertIsDisplayed()
    }
}
