package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.deploy.ResolvedDeploymentTarget
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalTargetState
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DeploymentTargetTransitionTest {
    @Test
    fun `physical target A to B writes the unchanged resolved view to B`() {
        val fixture = fixture("physical-a-to-b")
        val targetA = validDataTarget(fixture.root, "target-a")
        val targetB = validDataTarget(fixture.root, "target-b")

        fixture.selectPhysical(targetA)
        fixture.service.deployForGame(GAME_ID)
        val targetStateA = fixture.service.resolvedDataTarget(GAME_ID)
        val manifestA = targetStateA.manifestFile
        val baselineA = requireNotNull(targetStateA.baselineFile)
        requireNotNull(baselineA.parentFile).mkdirs()
        baselineA.writeText("target-a baseline sentinel")

        assertTrue(File(targetA, MANAGED_PATH).isFile)

        fixture.selectPhysical(targetB)
        val plan = fixture.service.buildDeploymentPlanForGame(GAME_ID)
        val targetStateB = fixture.service.resolvedDataTarget(GAME_ID)
        val manifestB = targetStateB.manifestFile
        val baselineB = requireNotNull(targetStateB.baselineFile)

        assertEquals(1, plan.dataPlan.operationCount)
        assertNotEquals(manifestA.absolutePath, manifestB.absolutePath)
        assertNotEquals(baselineA.absolutePath, baselineB.absolutePath)
        assertTrue(baselineA.isFile)
        assertFalse(baselineB.exists())

        fixture.service.deployForGame(GAME_ID)

        assertEquals("managed fixture", File(targetB, MANAGED_PATH).readText())
        assertTrue(manifestA.isFile)
        assertTrue(manifestB.isFile)
        assertEquals("target-a baseline sentinel", baselineA.readText())
    }

    @Test
    fun `simulated target to physical target performs physical writes`() {
        val fixture = fixture("simulated-to-physical")
        val physical = validDataTarget(fixture.root, "physical")

        fixture.selectSimulated()
        fixture.service.deployForGame(GAME_ID)
        assertTrue(File(fixture.simulatedData, MANAGED_PATH).isFile)

        fixture.selectPhysical(physical)
        assertEquals(1, fixture.service.buildDeploymentPlanForGame(GAME_ID).dataPlan.operationCount)

        fixture.service.deployForGame(GAME_ID)
        assertEquals("managed fixture", File(physical, MANAGED_PATH).readText())
    }

    @Test
    fun `physical target to simulated target uses simulated state`() {
        val fixture = fixture("physical-to-simulated")
        val physical = validDataTarget(fixture.root, "physical")

        fixture.selectPhysical(physical)
        fixture.service.deployForGame(GAME_ID)

        fixture.selectSimulated()
        assertEquals(1, fixture.service.buildDeploymentPlanForGame(GAME_ID).dataPlan.operationCount)

        fixture.service.deployForGame(GAME_ID)
        assertEquals("managed fixture", File(fixture.simulatedData, MANAGED_PATH).readText())
    }

    @Test
    fun `returning to a previously used target reuses only that target manifest`() {
        val fixture = fixture("return-to-target")
        val targetA = validDataTarget(fixture.root, "target-a")
        val targetB = validDataTarget(fixture.root, "target-b")

        fixture.selectPhysical(targetA)
        fixture.service.deployForGame(GAME_ID)
        fixture.selectPhysical(targetB)
        fixture.service.deployForGame(GAME_ID)

        fixture.selectPhysical(targetA)
        val plan = fixture.service.buildDeploymentPlanForGame(GAME_ID)

        assertEquals(0, plan.dataPlan.operationCount)
        assertEquals("managed fixture", File(targetA, MANAGED_PATH).readText())
    }

    @Test
    fun `Data and Game Root identities and state files cannot be exchanged`() {
        val fixture = fixture("data-root-identity")
        val rootTarget = File(fixture.root, "game").apply { mkdirs() }
        val dataTarget = File(rootTarget, "Data").apply { mkdirs() }
        File(rootTarget, "FalloutNV.exe").writeText("marker")
        File(dataTarget, "FalloutNV.esm").writeText("marker")

        fixture.service.saveGameDeploymentConfigs(
            listOf(
                physicalConfig(
                    dataPath = dataTarget.absolutePath,
                    rootPath = rootTarget.absolutePath
                )
            )
        )

        val dataIdentity = fixture.service.dataTargetIdentity(GAME_ID)
        val rootIdentity = fixture.service.rootTargetIdentity(GAME_ID)

        fixture.service.deployForGame(GAME_ID)
        val journal = DeploymentJournalRepository(fixture.journalFile).load()!!
        val dataState = journal.dataTarget!!
        val rootState = journal.rootTarget!!

        assertEquals("real_path", dataIdentity.mode)
        assertEquals("root_real_path", rootIdentity.mode)
        assertNotEquals(dataIdentity.stableKey(), rootIdentity.stableKey())
        assertNotEquals(dataState.manifestFilePath, rootState.manifestFilePath)
        assertNotEquals(dataState.backupDirectoryPath, rootState.backupDirectoryPath)
        assertTrue(File(dataState.manifestFilePath).name.startsWith("deployment_manifest_"))
        assertTrue(File(rootState.manifestFilePath).name.startsWith("deployment_manifest_root_"))
        assertTrue(File(requireNotNull(dataState.baselineFilePath)).name.startsWith("data_baseline_"))
        assertNull(rootState.baselineFilePath)
    }

    @Test
    fun `profiles with separate state directories cannot reuse deployment state`() {
        val root = Files.createTempDirectory("dml-target-transition-profiles").toFile()
        val target = validDataTarget(root, "shared-target")
        val source = File(root, "source/fixture.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("managed fixture")
        }
        val winner = winner(source)
        val profileA = serviceFixture(root, "profile-a", listOf(winner))
        val profileB = serviceFixture(root, "profile-b", listOf(winner))
        val config = physicalConfig(target.absolutePath)

        profileA.service.saveGameDeploymentConfigs(listOf(config))
        profileB.service.saveGameDeploymentConfigs(listOf(config))
        profileA.service.deployForGame(GAME_ID)

        val profileBPlan = profileB.service.buildDeploymentPlanForGame(GAME_ID)

        assertEquals(1, profileBPlan.dataPlan.operationCount)
        assertNotEquals(
            profileA.service.effectiveDataManifestFile(GAME_ID).absolutePath,
            profileB.service.effectiveDataManifestFile(GAME_ID).absolutePath
        )
    }

    @Test
    fun `canonical Data path aliases resolve to one target identity`() {
        val fixture = fixture("canonical-alias")
        val target = validDataTarget(fixture.root, "target")

        fixture.selectPhysical(target)
        val directIdentity = fixture.service.dataTargetIdentity(GAME_ID)

        val aliasedPath = File(target, "../Data/.")
        fixture.service.saveGameDeploymentConfigs(
            listOf(physicalConfig(aliasedPath.path))
        )
        val aliasedIdentity = fixture.service.dataTargetIdentity(GAME_ID)

        assertEquals(directIdentity, aliasedIdentity)
        assertEquals(target.canonicalPath, aliasedIdentity.target)
    }

    @Test
    fun `symbolic Data path alias resolves to the canonical target identity`() {
        val fixture = fixture("symbolic-alias")
        val target = validDataTarget(fixture.root, "target")
        val alias = File(fixture.root, "data-alias")

        try {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        fixture.selectPhysical(target)
        val directIdentity = fixture.service.dataTargetIdentity(GAME_ID)
        fixture.selectPhysical(alias)
        val aliasIdentity = fixture.service.dataTargetIdentity(GAME_ID)

        assertEquals(directIdentity, aliasIdentity)
        assertEquals(target.canonicalPath, aliasIdentity.target)
    }

    @Test
    fun `unfinished target A journal remains bound to A and blocks target B`() {
        val fixture = fixture("unfinished-journal")
        val targetA = validDataTarget(fixture.root, "target-a")
        val targetB = validDataTarget(fixture.root, "target-b")

        fixture.selectPhysical(targetA)
        val targetStateA = fixture.service.resolvedDataTarget(GAME_ID).toJournalState()
        val repository = DeploymentJournalRepository(fixture.journalFile)
        repository.saveStarted(startedRecord(targetStateA))

        fixture.selectPhysical(targetB)

        try {
            fixture.service.deployForGame(GAME_ID)
            fail("Expected unfinished target A journal to block target B deployment.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("unfinished deployment journal"))
        }

        val retained = repository.load()!!
        assertEquals(targetStateA.identityKey, retained.dataTarget?.identityKey)
        assertFalse(File(targetB, MANAGED_PATH).exists())
    }

    @Test
    fun `invalid journal blocks ordinary and full deployment before target or manifest writes`() {
        for ((index, text) in listOf("", "{", "{\"schemaVersion\":99}").withIndex()) {
            val fixture = fixture("invalid-journal-$index")
            fixture.selectSimulated()
            fixture.journalFile.parentFile!!.mkdirs()
            fixture.journalFile.writeText(text)
            val before = fixture.journalFile.readBytes()
            val target = fixture.service.resolvedDataTarget(GAME_ID)
            assertThrows(IOException::class.java) { fixture.service.deployForGame(GAME_ID) }
            assertThrows(IOException::class.java) { fixture.service.forceFullRedeployForGame(GAME_ID) }
            assertArrayEquals(before, fixture.journalFile.readBytes())
            assertFalse(File(fixture.simulatedData, MANAGED_PATH).exists())
            assertFalse(target.manifestFile.exists())
        }
    }

    @Test
    fun `unreadable journal path blocks deployment without replacing its evidence`() {
        val fixture = fixture("unreadable-journal")
        fixture.selectSimulated()
        fixture.journalFile.mkdirs()
        val sentinel = File(fixture.journalFile, "evidence").apply { writeText("preserve") }
        assertThrows(IOException::class.java) { fixture.service.deployForGame(GAME_ID) }
        assertThrows(IOException::class.java) { fixture.service.forceFullRedeployForGame(GAME_ID) }
        assertEquals("preserve", sentinel.readText())
        assertFalse(File(fixture.simulatedData, MANAGED_PATH).exists())
    }

    private fun fixture(name: String): Fixture {
        val root = Files.createTempDirectory("dml-target-transition-$name").toFile()
        val source = File(root, "source/fixture.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("managed fixture")
        }
        val serviceFixture = serviceFixture(root, "profile", listOf(winner(source)))
        return Fixture(
            root = root,
            service = serviceFixture.service,
            simulatedData = serviceFixture.simulatedData,
            journalFile = serviceFixture.journalFile
        )
    }

    private fun serviceFixture(
        root: File,
        profileName: String,
        winners: List<FileRecord>
    ): ServiceFixture {
        val profileDir = File(root, profileName).apply { mkdirs() }
        val stateDir = File(profileDir, "state").apply { mkdirs() }
        val simulatedData = File(profileDir, "deploy/Data").apply { mkdirs() }
        val service = DeploymentService(
            appFilesDir = File(profileDir, "files").apply { mkdirs() },
            tempDir = File(profileDir, "temp").apply { mkdirs() },
            stateFile = File(stateDir, "mods.json"),
            deploymentManifestFile = File(stateDir, "deployment_manifest.json"),
            deployRootDir = simulatedData,
            gameConfigFile = File(stateDir, "game_config.json"),
            currentDataWinningRecords = { winners },
            currentRootWinningRecords = { emptyList() }
        )
        return ServiceFixture(
            service = service,
            simulatedData = simulatedData,
            journalFile = File(stateDir, "deployment_journal_${GAME_ID}.json")
        )
    }

    private fun validDataTarget(root: File, name: String): File {
        return File(root, "$name/Game/Data").apply {
            mkdirs()
            File(this, "FalloutNV.esm").writeText("marker")
        }
    }

    private fun winner(source: File): FileRecord {
        return FileRecord(
            normalizedPath = MANAGED_PATH,
            winningModId = "fixture-mod",
            winningModName = "Fixture Mod",
            sourceFilePath = source.absolutePath,
            hash = "fixture-hash"
        )
    }

    private fun physicalConfig(
        dataPath: String,
        rootPath: String = ""
    ): GameDeploymentConfig {
        return GameDeploymentConfig(
            gameId = GAME_ID,
            displayName = "Fallout New Vegas",
            targetDataPath = dataPath,
            realDeployEnabled = true,
            targetRootPath = rootPath
        )
    }

    private fun Fixture.selectPhysical(target: File) {
        service.saveGameDeploymentConfigs(listOf(physicalConfig(target.absolutePath)))
    }

    private fun Fixture.selectSimulated() {
        service.saveGameDeploymentConfigs(
            listOf(
                GameDeploymentConfig(
                    gameId = GAME_ID,
                    displayName = "Fallout New Vegas",
                    targetDataPath = "",
                    realDeployEnabled = false
                )
            )
        )
    }

    private fun ResolvedDeploymentTarget.toJournalState(): DeploymentJournalTargetState {
        return DeploymentJournalTargetState(
            targetType = targetType.name,
            gameId = identity.gameId,
            mode = identity.mode,
            target = identity.target,
            identityKey = identity.stableKey(),
            manifestFilePath = manifestFile.absolutePath,
            baselineFilePath = baselineFile?.absolutePath,
            backupDirectoryPath = backupDirectory.absolutePath
        )
    }

    private fun startedRecord(
        dataTarget: DeploymentJournalTargetState
    ): DeploymentJournalRecord {
        return DeploymentJournalRecord(
            operationId = "unfinished-target-a",
            gameId = GAME_ID,
            profileId = "profile",
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = 1000,
            completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(
                dataOperationCount = 1,
                rootOperationCount = 0,
                totalOperationCount = 1,
                dataEstimatedCopyBytes = 10,
                rootEstimatedCopyBytes = null,
                preflightCanDeploy = true,
                preflightErrorCount = 0,
                preflightWarningCount = 0
            ),
            resultSummary = null,
            failureMessage = null,
            dataTarget = dataTarget,
            rootTarget = null
        )
    }

    private data class Fixture(
        val root: File,
        val service: DeploymentService,
        val simulatedData: File,
        val journalFile: File
    )

    private data class ServiceFixture(
        val service: DeploymentService,
        val simulatedData: File,
        val journalFile: File
    )

    companion object {
        private const val GAME_ID = "fallout_nv"
        private const val MANAGED_PATH = "meshes/dml-target-identity-fixture.txt"
    }
}
