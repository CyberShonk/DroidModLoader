package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.GameDeploymentConfigRepository
import com.shonkware.droidmodloader.engine.deploy.DeploymentManager
import com.shonkware.droidmodloader.engine.deploy.DeploymentResult
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalResultSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanBuilder
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanScope
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightChecker
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.model.DeploymentRecord
import com.shonkware.droidmodloader.engine.model.FileRecord
import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import java.io.File
import java.security.MessageDigest

internal class DeploymentService(
    private val appFilesDir: File,
    private val tempDir: File,
    private val stateFile: File,
    private val deploymentManifestFile: File,
    private val deployRootDir: File,
    gameConfigFile: File,
    private val currentDataWinningRecords: () -> List<FileRecord>,
    private val currentRootWinningRecords: () -> List<FileRecord>
) {
    private val gameDeploymentConfigRepository = GameDeploymentConfigRepository(gameConfigFile)
    private val directPathValidator = DirectPathValidator()

    private fun getCurrentDataWinningRecords(): List<FileRecord> = currentDataWinningRecords()

    private fun getCurrentRootWinningRecords(): List<FileRecord> = currentRootWinningRecords()

    fun saveGameDeploymentConfigs(configs: List<GameDeploymentConfig>) {
        gameDeploymentConfigRepository.save(configs)
    }


    fun loadGameDeploymentConfigs(): List<GameDeploymentConfig> {
        return gameDeploymentConfigRepository.load()
    }


    fun getGameDeploymentConfig(gameId: String): GameDeploymentConfig? {
        return loadGameDeploymentConfigs().firstOrNull { it.gameId == gameId }
    }


    fun validateTargetDataPath(path: String): Boolean {
        return directPathValidator.validateDirectory(
            path = path,
            requireWritable = true
        ).isValid
    }

    fun deployForGame(gameId: String): ScopedDeploymentResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                getEffectiveDeploymentManifestFile(gameId)
            )

            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()

            val (newDataManifest, dataResult) = deployRecordsToConfiguredTarget(
                oldManifest = oldDataManifest,
                newWinningRecords = dataWinningRecords,
                realDeployEnabled = config?.realDeployEnabled == true,
                targetPath = config?.targetDataPath ?: "",
                fallbackRootDir = deployRootDir,
                backupRootDir = getDeploymentBackupDir(
                    gameId = gameId,
                    scopeName = "data",
                    rootTarget = false
                )
            )

            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                getEffectiveRootDeploymentManifestFile(gameId)
            )

            val oldRootManifest = rootManifestRepository.load()
            val rootWinningRecords = getCurrentRootWinningRecords()

            val canDeployRoot = canDeployGameRoot(config)

            val rootResult = if (canDeployRoot && (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())) {
                val (newRootManifest, result) = deployRecordsToConfiguredTarget(
                    oldManifest = oldRootManifest,
                    newWinningRecords = rootWinningRecords,
                    realDeployEnabled = config?.realDeployEnabled == true,
                    targetPath = config?.targetRootPath ?: "",
                    fallbackRootDir = getSimulatedGameRootDir(),
                    backupRootDir = getDeploymentBackupDir(
                        gameId = gameId,
                        scopeName = "root",
                        rootTarget = true
                    )
                )

                rootManifestRepository.save(newRootManifest)
                result
            } else {
                DeploymentResult(
                    addCount = 0,
                    removeCount = 0,
                    updateCount = 0,
                    finalRecordCount = 0
                )
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = DeploymentJournalResultSummary(
                    addCount = scopedResult.addCount,
                    updateCount = scopedResult.updateCount,
                    removeCount = scopedResult.removeCount,
                    backupCount = scopedResult.dataResult.backupCount + scopedResult.rootResult.backupCount,
                    restoreCount = scopedResult.dataResult.restoreCount + scopedResult.rootResult.restoreCount,
                    protectedConflictCount = scopedResult.dataResult.protectedConflictCount + scopedResult.rootResult.protectedConflictCount,
                    finalRecordCount = scopedResult.finalRecordCount
                )
            )

            return scopedResult
        } catch (e: Exception) {
            journalRepository.markFailed(
                record = journalRecord,
                message = e.message ?: e::class.java.name
            )

            throw e
        }
    }


    fun forceFullRedeployForGame(gameId: String): ScopedDeploymentResult {
        val plan = buildFullRedeployPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        val rootPlanHasWork = plan.rootPlan.operationCount > 0
        val rootCanDeploy = canDeployGameRoot(config)

        if (rootPlanHasWork && !rootCanDeploy) {
            throw IllegalStateException(
                "Full redeploy needs Game Root work, but no Game Root target is available."
            )
        }

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                getEffectiveDeploymentManifestFile(gameId)
            )

            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()

            val forcedOldDataManifest = forceManifestToRewriteCurrentWinners(
                oldManifest = oldDataManifest,
                currentWinners = dataWinningRecords
            )

            val (newDataManifest, dataResult) = deployRecordsToConfiguredTarget(
                oldManifest = forcedOldDataManifest,
                newWinningRecords = dataWinningRecords,
                realDeployEnabled = config?.realDeployEnabled == true,
                targetPath = config?.targetDataPath ?: "",
                fallbackRootDir = deployRootDir,
                backupRootDir = getDeploymentBackupDir(
                    gameId = gameId,
                    scopeName = "data",
                    rootTarget = false
                )
            )

            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                getEffectiveRootDeploymentManifestFile(gameId)
            )

            val oldRootManifest = rootManifestRepository.load()
            val rootWinningRecords = getCurrentRootWinningRecords()

            val rootResult = if (rootCanDeploy && (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())) {
                val forcedOldRootManifest = forceManifestToRewriteCurrentWinners(
                    oldManifest = oldRootManifest,
                    currentWinners = rootWinningRecords
                )

                val (newRootManifest, result) = deployRecordsToConfiguredTarget(
                    oldManifest = forcedOldRootManifest,
                    newWinningRecords = rootWinningRecords,
                    realDeployEnabled = config?.realDeployEnabled == true,
                    targetPath = config?.targetRootPath ?: "",
                    fallbackRootDir = getSimulatedGameRootDir(),
                    backupRootDir = getDeploymentBackupDir(
                        gameId = gameId,
                        scopeName = "root",
                        rootTarget = true
                    )
                )

                rootManifestRepository.save(newRootManifest)
                result
            } else {
                DeploymentResult(
                    addCount = 0,
                    removeCount = 0,
                    updateCount = 0,
                    finalRecordCount = 0
                )
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = DeploymentJournalResultSummary(
                    addCount = scopedResult.addCount,
                    updateCount = scopedResult.updateCount,
                    removeCount = scopedResult.removeCount,
                    backupCount = scopedResult.dataResult.backupCount + scopedResult.rootResult.backupCount,
                    restoreCount = scopedResult.dataResult.restoreCount + scopedResult.rootResult.restoreCount,
                    protectedConflictCount = scopedResult.dataResult.protectedConflictCount + scopedResult.rootResult.protectedConflictCount,
                    finalRecordCount = scopedResult.finalRecordCount
                )
            )

            return scopedResult
        } catch (e: Exception) {
            journalRepository.markFailed(
                record = journalRecord,
                message = e.message ?: e::class.java.name
            )

            throw e
        }
    }


    private fun deployRecordsToConfiguredTarget(
        oldManifest: List<DeploymentRecord>,
        newWinningRecords: List<FileRecord>,
        realDeployEnabled: Boolean,
        targetPath: String,
        fallbackRootDir: File,
        backupRootDir: File
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        val deployTarget = if (realDeployEnabled && validateTargetDataPath(targetPath)) {
            File(targetPath)
        } else {
            fallbackRootDir
        }

        return DeploymentManager(
            deployRootDir = deployTarget,
            backupRootDir = backupRootDir
        ).deploy(oldManifest, newWinningRecords)
    }


    fun buildDeploymentPlanForGame(gameId: String): ScopedDeploymentPlan {
        val dataManifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val oldDataManifest = dataManifestRepository.load()
        val dataWinningRecords = getCurrentDataWinningRecords()

        val rootManifestRepository = DeploymentManifestRepository(
            getEffectiveRootDeploymentManifestFile(gameId)
        )

        val oldRootManifest = rootManifestRepository.load()
        val rootWinningRecords = getCurrentRootWinningRecords()

        val builder = DeploymentPlanBuilder()

        val dataPlan = builder.build(
            scope = DeploymentPlanScope.DATA,
            oldManifest = oldDataManifest,
            newWinningRecords = dataWinningRecords
        )

        val rootPlan = builder.build(
            scope = DeploymentPlanScope.GAME_ROOT,
            oldManifest = oldRootManifest,
            newWinningRecords = rootWinningRecords
        )

        return ScopedDeploymentPlan(
            dataPlan = dataPlan,
            rootPlan = rootPlan
        )
    }


    fun buildDeploymentPlanDebugSummary(gameId: String): String {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )

        return buildString {
            appendLine(buildDeploymentPlanContextSummary(gameId, config, plan))
            appendLine()
            appendLine(plan.toDebugSummary())
            appendLine()
            appendLine(preflight.toDebugSummary())
        }
    }


    private fun buildDeploymentPlanContextSummary(
        gameId: String,
        config: GameDeploymentConfig?,
        plan: ScopedDeploymentPlan
    ): String {
        val realDeployEnabled = config?.realDeployEnabled == true

        val dataTargetStatus = when {
            config == null -> "no config"
            config.dataPathReselectionRequired -> "reselection required"
            config.targetDataPath.isNotBlank() -> "direct path selected"
            else -> "not selected"
        }

        val rootTargetStatus = when {
            config == null -> "no config"
            config.rootPathReselectionRequired -> "reselection required"
            config.targetRootPath.isNotBlank() -> "direct path selected"
            else -> "not selected"
        }

        val rootOperationsNeedTarget =
            plan.rootPlan.operationCount > 0 && rootTargetStatus == "not selected"

        return buildString {
            appendLine("Deploy Plan Context")
            appendLine("  Game: $gameId")
            appendLine("  Mode: ${if (realDeployEnabled) "real target folders" else "test output folders"}")
            appendLine("  Data target: $dataTargetStatus")
            appendLine("  Game Root target: $rootTargetStatus")
            appendLine("  Data operations: ${plan.dataPlan.operationCount}")
            appendLine("  Game Root operations: ${plan.rootPlan.operationCount}")

            if (rootOperationsNeedTarget) {
                appendLine("  Warning: Game Root operations exist, but no Game Root target is selected.")
            }
        }
    }


    private fun canDeployGameRoot(config: GameDeploymentConfig?): Boolean {
        if (config == null) return true

        if (!config.realDeployEnabled) {
            return true
        }

        return !config.rootPathReselectionRequired &&
                validateTargetDataPath(config.targetRootPath)
    }


    private fun getSimulatedGameRootDir(): File {
        return File(
            deployRootDir.parentFile ?: deployRootDir,
            "GameRoot"
        )
    }


    private fun getDeploymentBackupDir(
        gameId: String,
        scopeName: String,
        rootTarget: Boolean
    ): File {
        val identity = if (rootTarget) {
            getRootDeploymentTargetIdentity(gameId)
        } else {
            getDeploymentTargetIdentity(gameId)
        }

        val hash = hashManifestKey(identity.stableKey())

        val baseDir = deploymentManifestFile.parentFile
            ?: File(appFilesDir, "state")

        return File(
            baseDir,
            "deployment_backups/${identity.gameId}_${identity.mode}_$hash/$scopeName"
        )
    }


    private fun getEffectiveDeploymentManifestFile(gameId: String): File {
        return File(
            deploymentManifestFile.parentFile,
            buildTargetScopedFileName("deployment_manifest", gameId)
        )
    }


    private fun getEffectiveRootDeploymentManifestFile(gameId: String): File {
        return File(
            deploymentManifestFile.parentFile,
            buildTargetScopedFileNameForIdentity(
                prefix = "deployment_manifest_root",
                identity = getRootDeploymentTargetIdentity(gameId)
            )
        )
    }

    private fun getDeploymentTargetIdentity(gameId: String): DeploymentTargetIdentity {
        val config = getGameDeploymentConfig(gameId)

        return when {
            config != null &&
                    config.realDeployEnabled &&
                    validateTargetDataPath(config.targetDataPath) -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "real_path",
                    target = config.targetDataPath
                )
            }

            else -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "simulated",
                    target = deployRootDir.absolutePath
                )
            }
        }
    }


    private fun hashManifestKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }


    private fun buildTargetScopedFileName(
        prefix: String,
        gameId: String,
        extension: String = "json"
    ): String {
        return buildTargetScopedFileNameForIdentity(
            prefix = prefix,
            identity = getDeploymentTargetIdentity(gameId),
            extension = extension
        )
    }


    private fun buildTargetScopedFileNameForIdentity(
        prefix: String,
        identity: DeploymentTargetIdentity,
        extension: String = "json"
    ): String {
        val hash = hashManifestKey(identity.stableKey())
        return "${prefix}_${identity.gameId}_${identity.mode}_$hash.$extension"
    }


    private fun getRootDeploymentTargetIdentity(gameId: String): DeploymentTargetIdentity {
        val config = getGameDeploymentConfig(gameId)

        return when {
            config != null &&
                    config.realDeployEnabled &&
                    validateTargetDataPath(config.targetRootPath) -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "root_real_path",
                    target = config.targetRootPath
                )
            }

            else -> {
                DeploymentTargetIdentity(
                    gameId = gameId,
                    mode = "root_simulated",
                    target = getSimulatedGameRootDir().absolutePath
                )
            }
        }
    }


    fun getDeploymentTargetDebugSummary(gameId: String): String {
        val identity = getDeploymentTargetIdentity(gameId)
        val manifestName = buildTargetScopedFileName("deployment_manifest", gameId)
        val rootManifestName = buildTargetScopedFileNameForIdentity(
            prefix = "deployment_manifest_root",
            identity = getRootDeploymentTargetIdentity(gameId)
        )
        val baselineName = buildTargetScopedFileName("data_baseline", gameId)


        return buildString {
            appendLine("Deployment target identity:")
            appendLine(identity.displaySummary())
            appendLine("Manifest file: $manifestName")
            appendLine("Baseline file: $baselineName")
            appendLine("Root manifest file: $rootManifestName")
        }
    }


    fun buildDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        return DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )
    }

    fun requireDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val plan = buildDeploymentPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val result = DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )

        if (!result.canDeploy) {
            throw DeploymentPreflightException(result)
        }

        return result
    }


    private fun getDeploymentJournalFile(gameId: String): File {
        val stateDir = stateFile.parentFile ?: tempDir
        return File(stateDir, "deployment_journal_${gameId}.json")
    }


    private fun getCurrentProfileIdForJournal(): String {
        return stateFile.parentFile?.name ?: "unknown_profile"
    }


    fun getDeploymentJournalDebugSummary(gameId: String): String {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load()

        return if (record == null) {
            "No deploy journal found for $gameId."
        } else {
            record.toDebugSummary()
        }
    }


    private fun createStartedDeploymentJournal(
        gameId: String,
        plan: ScopedDeploymentPlan,
        preflight: DeploymentPreflightResult
    ): DeploymentJournalRecord {
        return DeploymentJournalRecord(
            operationId = "${System.currentTimeMillis()}_$gameId",
            gameId = gameId,
            profileId = getCurrentProfileIdForJournal(),
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = System.currentTimeMillis(),
            completedAtEpochMillis = null,
            planSummary = DeploymentJournalPlanSummary(
                dataOperationCount = plan.dataPlan.operationCount,
                rootOperationCount = plan.rootPlan.operationCount,
                totalOperationCount = plan.totalOperationCount,
                dataEstimatedCopyBytes = plan.dataPlan.estimatedBytesToCopy,
                rootEstimatedCopyBytes = plan.rootPlan.estimatedBytesToCopy,
                preflightCanDeploy = preflight.canDeploy,
                preflightErrorCount = preflight.errorCount,
                preflightWarningCount = preflight.warningCount
            ),
            resultSummary = null,
            failureMessage = null
        )
    }


    fun getDeploymentJournalStartupWarning(gameId: String): String? {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load() ?: return null

        if (record.status != DeploymentJournalStatus.STARTED) {
            return null
        }


        return buildString {
            appendLine("Previous deploy may not have finished cleanly.")
            appendLine("Game: ${record.gameId}")
            appendLine("Profile: ${record.profileId}")
            appendLine("Operation ID: ${record.operationId}")
            appendLine("Status: ${record.status}")
            appendLine("Started: ${record.startedAtEpochMillis}")
            appendLine("Data operations planned: ${record.planSummary.dataOperationCount}")
            appendLine("Game Root operations planned: ${record.planSummary.rootOperationCount}")
            appendLine("Preflight errors: ${record.planSummary.preflightErrorCount}")
            appendLine("Preflight warnings: ${record.planSummary.preflightWarningCount}")
            appendLine("This build will only warn. Recovery actions will be added later.")
        }

    }


    fun markDeploymentJournalReviewed(gameId: String): Boolean {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load() ?: return false

        if (record.status != DeploymentJournalStatus.STARTED) {
            return false
        }

        repository.markReviewed(record)
        return true
    }


    fun buildFullRedeployPlanForGame(gameId: String): ScopedDeploymentPlan {
        val dataManifestRepository = DeploymentManifestRepository(
            getEffectiveDeploymentManifestFile(gameId)
        )

        val oldDataManifest = dataManifestRepository.load()
        val dataWinningRecords = getCurrentDataWinningRecords()

        val rootManifestRepository = DeploymentManifestRepository(
            getEffectiveRootDeploymentManifestFile(gameId)
        )

        val oldRootManifest = rootManifestRepository.load()
        val rootWinningRecords = getCurrentRootWinningRecords()

        val builder = DeploymentPlanBuilder()

        val dataPlan = builder.buildFullRedeploy(
            scope = DeploymentPlanScope.DATA,
            oldManifest = oldDataManifest,
            newWinningRecords = dataWinningRecords
        )

        val rootPlan = builder.buildFullRedeploy(
            scope = DeploymentPlanScope.GAME_ROOT,
            oldManifest = oldRootManifest,
            newWinningRecords = rootWinningRecords
        )

        return ScopedDeploymentPlan(
            dataPlan = dataPlan,
            rootPlan = rootPlan
        )
    }


    fun buildFullRedeployPlanDebugSummary(gameId: String): String {
        val plan = buildFullRedeployPlanForGame(gameId)
        val config = getGameDeploymentConfig(gameId)

        val preflight = DeploymentPreflightChecker().check(
            config = config,
            plan = plan
        )

        return buildString {
            appendLine("Full Redeploy Plan")
            appendLine("This is a recovery planning check only.")
            appendLine("No files were changed.")
            appendLine()
            appendLine(buildDeploymentPlanContextSummary(gameId, config, plan))
            appendLine()
            appendLine(plan.toDebugSummary())
            appendLine()
            appendLine(preflight.toDebugSummary())
        }
    }


    private fun forceManifestToRewriteCurrentWinners(
        oldManifest: List<DeploymentRecord>,
        currentWinners: List<FileRecord>
    ): List<DeploymentRecord> {
        val currentWinnerPaths = currentWinners
            .map { it.normalizedPath }
            .toSet()

        return oldManifest.map { record ->
            if (record.normalizedPath in currentWinnerPaths) {
                record.copy(
                    hash = "__force_full_redeploy__${record.hash}"
                )
            } else {
                record
            }
        }
    }

    internal fun effectiveDataManifestFile(gameId: String): File =
        getEffectiveDeploymentManifestFile(gameId)

    internal fun dataTargetIdentity(gameId: String): DeploymentTargetIdentity =
        getDeploymentTargetIdentity(gameId)

    internal fun rootTargetIdentity(gameId: String): DeploymentTargetIdentity =
        getRootDeploymentTargetIdentity(gameId)

    internal fun targetScopedFileName(prefix: String, gameId: String): String =
        buildTargetScopedFileName(prefix, gameId)
}
