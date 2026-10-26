package com.shonkware.droidmodloader.engine.service

import com.shonkware.droidmodloader.engine.data.DeploymentManifestRepository
import com.shonkware.droidmodloader.engine.data.GameDeploymentConfigRepository
import com.shonkware.droidmodloader.engine.deploy.DeploymentManager
import com.shonkware.droidmodloader.engine.deploy.DeploymentResult
import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidationSeverity
import com.shonkware.droidmodloader.engine.deploy.GameTargetValidator
import com.shonkware.droidmodloader.engine.deploy.ResolvedDeploymentTarget
import com.shonkware.droidmodloader.engine.deploy.ResolvedDeploymentTargets
import com.shonkware.droidmodloader.engine.deploy.ScopedDeploymentResult
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalPlanSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRecord
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalRepository
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalResultSummary
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalStatus
import com.shonkware.droidmodloader.engine.deploy.journal.DeploymentJournalTargetState
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanBuilder
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPlanScope
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightChecker
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightException
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightResult
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightIssue
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentPreflightSeverity
import com.shonkware.droidmodloader.engine.deploy.plan.DeploymentStorageCapacity
import com.shonkware.droidmodloader.engine.storage.StorageCapacityMeasurement
import com.shonkware.droidmodloader.engine.storage.StorageCapacityProbe
import com.shonkware.droidmodloader.engine.storage.StorageCapacityStatus
import com.shonkware.droidmodloader.engine.deploy.plan.ScopedDeploymentPlan
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentHealthFacts
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentIntegrityEvidence
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryCapabilities
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentRecoveryFact
import com.shonkware.droidmodloader.engine.deploy.health.DeploymentTargetHealth
import com.shonkware.droidmodloader.engine.deploy.health.TargetReadiness
import com.shonkware.droidmodloader.engine.integrity.IntegrityInput
import com.shonkware.droidmodloader.engine.integrity.ManagedContentIntegrity
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
    private val gameConfigFile: File,
    private val currentDataWinningRecords: () -> List<FileRecord>,
    private val currentRootWinningRecords: () -> List<FileRecord>,
    private val storageCapacityProbe: StorageCapacityProbe = StorageCapacityProbe {
        StorageCapacityMeasurement(details = "No backing volume probe is available.")
    }
) {
    private val gameDeploymentConfigRepository = GameDeploymentConfigRepository(gameConfigFile)
    private val directPathValidator = DirectPathValidator()
    private val gameTargetValidator = GameTargetValidator(directPathValidator)
    private val deploymentPreflightChecker = DeploymentPreflightChecker(gameTargetValidator)

    private fun getCurrentDataWinningRecords(): List<FileRecord> = currentDataWinningRecords()

    private fun getCurrentRootWinningRecords(): List<FileRecord> = currentRootWinningRecords()

    fun captureTargetIntegrityInputs(gameId: String): List<IntegrityInput> {
        val integrity = ManagedContentIntegrity
        return try {
            val targets = resolveInspectionTargets(gameId)
            listOf(targets.data, targets.root).map { target ->
                integrity.capture(target.targetType.displayName, target.identity.stableKey(), target.deployDirectory,
                    target.manifestFile, integrity::manifestRecords).let { input ->
                    if (target.unavailableReason == null) input
                    else input.copy(unavailable = target.unavailableReason)
                }
            }
        } catch (failure: Exception) {
            listOf(IntegrityInput(
                "Deployment targets", gameId, null, gameConfigFile, integrity.revision(gameConfigFile),
                emptyList(), integrity.evidenceProblem(gameConfigFile, failure)
            ))
        }
    }

    private fun resolveInspectionTargets(gameId: String): ResolvedDeploymentTargets {
        val configText = try {
            ManagedContentIntegrity.readEvidence(gameConfigFile)
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
        if (configText != null) {
            val configs = org.json.JSONArray(configText)
            val ids = mutableSetOf<String>()
            for (index in 0 until configs.length()) {
                val config = configs.getJSONObject(index)
                require(ids.add(config.getString("gameId"))) { "Duplicate game config" }
                config.getBoolean("realDeployEnabled")
            }
        }
        val config = configText?.let { gameDeploymentConfigRepository.parse(it).firstOrNull { it.gameId == gameId } }
        return resolveDeploymentTargets(gameId, config)
    }

    fun deploymentHealthJournalRevision(gameId: String): String =
        ManagedContentIntegrity.revision(getDeploymentJournalFile(gameId))

    fun collectDeploymentHealthFacts(
        profileId: String,
        gameId: String,
        winningRecords: Pair<List<FileRecord>, List<FileRecord>>?
    ): DeploymentHealthFacts {
        val recovery = collectRecoveryEvidence(gameId)
        var preflight: Boolean? = null
        var preflightDetails = ""
        val targetHealth = try {
            val targets = resolveInspectionTargets(gameId)
            val resolved = listOf(targets.data, targets.root)
            val plans = resolved.mapIndexed { index, target ->
                if (!target.canDeploy || winningRecords == null) null else try {
                    var oldRecords = emptyList<DeploymentRecord>()
                    val input = ManagedContentIntegrity.capture(target.targetType.displayName,
                        target.identity.stableKey(), target.deployDirectory, target.manifestFile) { text ->
                        oldRecords = DeploymentManifestRepository(target.manifestFile).parse(text)
                        ManagedContentIntegrity.manifestRecords(text)
                    }
                    require(input.unavailable == null || input.revision == "missing") { "Manifest unavailable" }
                    require(oldRecords.all { it.winningModId.isNotBlank() && ManagedContentIntegrity.hasRecordedHash(it.hash) }) {
                        "Manifest has incomplete comparison evidence"
                    }
                    DeploymentPlanBuilder().build(
                        if (index == 0) DeploymentPlanScope.DATA else DeploymentPlanScope.GAME_ROOT,
                        oldRecords, if (index == 0) winningRecords.first else winningRecords.second
                    )
                } catch (_: Exception) { null }
            }
            if (plans.all { it != null }) {
                val result = checkPreflight(targets,
                    ScopedDeploymentPlan(checkNotNull(plans[0]), checkNotNull(plans[1])))
                preflight = result.canDeploy
                preflightDetails = result.issues.take(8).joinToString("\n") { "${it.severity}: ${it.title} ${it.details}" }
                if (result.issues.size > 8) preflightDetails += "\n${result.issues.size - 8} additional preflight findings."
            }
            resolved.mapIndexed { index, target ->
                val reselect = when (target.targetType) {
                    GameTargetType.DATA -> targets.configSnapshot?.dataPathReselectionRequired == true
                    GameTargetType.GAME_ROOT -> targets.configSnapshot?.rootPathReselectionRequired == true
                }
                DeploymentTargetHealth(target.targetType, target.identity, when {
                    !target.canDeploy -> TargetReadiness.UNAVAILABLE
                    target.identity.mode in setOf("simulated", "root_simulated") -> TargetReadiness.SIMULATED
                    else -> TargetReadiness.READY
                }, plans[index]?.operationCount, reselect, listOfNotNull(target.unavailableReason,
                    if (winningRecords == null) "Recorded source comparison is unavailable or stale; no empty plan was assumed." else null
                ).joinToString("\n"))
            }
        } catch (_: Exception) {
            GameTargetType.entries.map { DeploymentTargetHealth(it, null, TargetReadiness.UNKNOWN, null,
                details = "Target configuration is missing expected fields, unsafe or unreadable.") }
        }
        return DeploymentHealthFacts(profileId, gameId, targetHealth, recovery.fact,
            DeploymentIntegrityEvidence(), preflight, System.currentTimeMillis(), recovery.details,
            preflightDetails = preflightDetails, recoveryCapabilities = recovery.capabilities)
    }

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
        val targets = resolveDeploymentTargets(gameId)
        val plan = buildDeploymentPlanForTargets(targets)
        val preflight = checkPreflight(targets, plan)

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        requireTargetSnapshotStillCurrent(targets)

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )
        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight,
            targets = targets
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                targets.data.manifestFile
            )
            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()

            val (newDataManifest, dataResult) = deployRecordsToTarget(
                target = targets.data,
                oldManifest = oldDataManifest,
                newWinningRecords = dataWinningRecords
            )
            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                targets.root.manifestFile
            )
            val oldRootManifest = loadManifestForPlan(targets.root)
            val rootWinningRecords = getCurrentRootWinningRecords()

            val rootResult = if (
                targets.root.canDeploy &&
                (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())
            ) {
                val (newRootManifest, result) = deployRecordsToTarget(
                    target = targets.root,
                    oldManifest = oldRootManifest,
                    newWinningRecords = rootWinningRecords
                )
                rootManifestRepository.save(newRootManifest)
                result
            } else {
                emptyDeploymentResult()
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = scopedResult.toJournalResultSummary()
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
        val targets = resolveDeploymentTargets(gameId)
        val plan = buildFullRedeployPlanForTargets(targets)
        val preflight = checkPreflight(targets, plan)

        if (!preflight.canDeploy) {
            throw DeploymentPreflightException(preflight)
        }

        if (plan.rootPlan.operationCount > 0 && !targets.root.canDeploy) {
            throw IllegalStateException(
                "Full redeploy needs Game Root work, but no Game Root target is available."
            )
        }

        requireTargetSnapshotStillCurrent(targets)

        val journalRepository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )
        val journalRecord = createStartedDeploymentJournal(
            gameId = gameId,
            plan = plan,
            preflight = preflight,
            targets = targets
        )

        journalRepository.saveStarted(journalRecord)

        try {
            val dataManifestRepository = DeploymentManifestRepository(
                targets.data.manifestFile
            )
            val oldDataManifest = dataManifestRepository.load()
            val dataWinningRecords = getCurrentDataWinningRecords()
            val forcedOldDataManifest = forceManifestToRewriteCurrentWinners(
                oldManifest = oldDataManifest,
                currentWinners = dataWinningRecords
            )

            val (newDataManifest, dataResult) = deployRecordsToTarget(
                target = targets.data,
                oldManifest = forcedOldDataManifest,
                newWinningRecords = dataWinningRecords
            )
            dataManifestRepository.save(newDataManifest)

            val rootManifestRepository = DeploymentManifestRepository(
                targets.root.manifestFile
            )
            val oldRootManifest = loadManifestForPlan(targets.root)
            val rootWinningRecords = getCurrentRootWinningRecords()

            val rootResult = if (
                targets.root.canDeploy &&
                (rootWinningRecords.isNotEmpty() || oldRootManifest.isNotEmpty())
            ) {
                val forcedOldRootManifest = forceManifestToRewriteCurrentWinners(
                    oldManifest = oldRootManifest,
                    currentWinners = rootWinningRecords
                )
                val (newRootManifest, result) = deployRecordsToTarget(
                    target = targets.root,
                    oldManifest = forcedOldRootManifest,
                    newWinningRecords = rootWinningRecords
                )
                rootManifestRepository.save(newRootManifest)
                result
            } else {
                emptyDeploymentResult()
            }

            val scopedResult = ScopedDeploymentResult(
                dataResult = dataResult,
                rootResult = rootResult
            )

            journalRepository.markCompleted(
                record = journalRecord,
                resultSummary = scopedResult.toJournalResultSummary()
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


    private fun deployRecordsToTarget(
        target: ResolvedDeploymentTarget,
        oldManifest: List<DeploymentRecord>,
        newWinningRecords: List<FileRecord>
    ): Pair<List<DeploymentRecord>, DeploymentResult> {
        return DeploymentManager(
            deployRootDir = target.requireDeployDirectory(),
            backupRootDir = target.backupDirectory
        ).deploy(oldManifest, newWinningRecords)
    }


    private fun emptyDeploymentResult(): DeploymentResult {
        return DeploymentResult(
            addCount = 0,
            removeCount = 0,
            updateCount = 0,
            finalRecordCount = 0
        )
    }


    private fun ScopedDeploymentResult.toJournalResultSummary(): DeploymentJournalResultSummary {
        return DeploymentJournalResultSummary(
            addCount = addCount,
            updateCount = updateCount,
            removeCount = removeCount,
            backupCount = dataResult.backupCount + rootResult.backupCount,
            restoreCount = dataResult.restoreCount + rootResult.restoreCount,
            protectedConflictCount = dataResult.protectedConflictCount + rootResult.protectedConflictCount,
            finalRecordCount = finalRecordCount
        )
    }


    fun buildDeploymentPlanForGame(gameId: String): ScopedDeploymentPlan {
        return buildDeploymentPlanForTargets(resolveDeploymentTargets(gameId))
    }


    private fun buildDeploymentPlanForTargets(
        targets: ResolvedDeploymentTargets
    ): ScopedDeploymentPlan {
        val oldDataManifest = loadManifestForPlan(targets.data)
        val dataWinningRecords = getCurrentDataWinningRecords()
        val oldRootManifest = loadManifestForPlan(targets.root)
        val rootWinningRecords = getCurrentRootWinningRecords()
        val builder = DeploymentPlanBuilder()

        return ScopedDeploymentPlan(
            dataPlan = builder.build(
                scope = DeploymentPlanScope.DATA,
                oldManifest = oldDataManifest,
                newWinningRecords = dataWinningRecords
            ),
            rootPlan = builder.build(
                scope = DeploymentPlanScope.GAME_ROOT,
                oldManifest = oldRootManifest,
                newWinningRecords = rootWinningRecords
            )
        )
    }


    private fun loadManifestForPlan(target: ResolvedDeploymentTarget): List<DeploymentRecord> {
        if (!target.canDeploy) return emptyList()
        return DeploymentManifestRepository(target.manifestFile).load()
    }


    fun buildDeploymentPlanDebugSummary(gameId: String): String {
        val targets = resolveDeploymentTargets(gameId)
        val plan = buildDeploymentPlanForTargets(targets)
        val preflight = checkPreflight(targets, plan)

        return buildString {
            appendLine(buildDeploymentPlanContextSummary(gameId, targets.configSnapshot, plan))
            appendLine()
            appendLine(targets.data.toDebugSummary())
            appendLine(targets.root.toDebugSummary())
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


    private fun getSimulatedGameRootDir(): File {
        return File(
            deployRootDir.parentFile ?: deployRootDir,
            "GameRoot"
        )
    }


    private fun resolveDeploymentTargets(
        gameId: String,
        config: GameDeploymentConfig? = getGameDeploymentConfig(gameId)
    ): ResolvedDeploymentTargets {
        return ResolvedDeploymentTargets(
            gameId = gameId,
            configSnapshot = config,
            data = resolveTarget(
                gameId = gameId,
                config = config,
                targetType = GameTargetType.DATA,
                fallbackDirectory = deployRootDir,
                manifestPrefix = "deployment_manifest",
                baselinePrefix = "data_baseline",
                backupScope = "data"
            ),
            root = resolveTarget(
                gameId = gameId,
                config = config,
                targetType = GameTargetType.GAME_ROOT,
                fallbackDirectory = getSimulatedGameRootDir(),
                manifestPrefix = "deployment_manifest_root",
                baselinePrefix = null,
                backupScope = "root"
            )
        )
    }


    private fun resolveTarget(
        gameId: String,
        config: GameDeploymentConfig?,
        targetType: GameTargetType,
        fallbackDirectory: File,
        manifestPrefix: String,
        baselinePrefix: String?,
        backupScope: String
    ): ResolvedDeploymentTarget {
        val realDeployEnabled = config?.realDeployEnabled == true
        val configuredPath = when (targetType) {
            GameTargetType.DATA -> config?.targetDataPath.orEmpty()
            GameTargetType.GAME_ROOT -> config?.targetRootPath.orEmpty()
        }
        val reselectionRequired = when (targetType) {
            GameTargetType.DATA -> config?.dataPathReselectionRequired == true
            GameTargetType.GAME_ROOT -> config?.rootPathReselectionRequired == true
        }

        val validation = if (realDeployEnabled && !reselectionRequired) {
            gameTargetValidator.validateTarget(
                gameId = gameId,
                targetType = targetType,
                path = configuredPath
            )
        } else {
            null
        }

        val validCanonicalPath = validation
            ?.takeIf { it.canDeploy }
            ?.canonicalPath
        val simulatedDirectory = canonicalOrAbsolute(fallbackDirectory)
        val realMode = when (targetType) {
            GameTargetType.DATA -> "real_path"
            GameTargetType.GAME_ROOT -> "root_real_path"
        }
        val unavailableMode = when (targetType) {
            GameTargetType.DATA -> "real_path_unavailable"
            GameTargetType.GAME_ROOT -> "root_real_path_unavailable"
        }
        val simulatedMode = when (targetType) {
            GameTargetType.DATA -> "simulated"
            GameTargetType.GAME_ROOT -> "root_simulated"
        }

        val identity = when {
            !realDeployEnabled -> DeploymentTargetIdentity(
                gameId = gameId,
                mode = simulatedMode,
                target = simulatedDirectory.absolutePath
            )

            validCanonicalPath != null -> DeploymentTargetIdentity(
                gameId = gameId,
                mode = realMode,
                target = validCanonicalPath
            )

            else -> DeploymentTargetIdentity(
                gameId = gameId,
                mode = unavailableMode,
                target = validation?.canonicalPath
                    ?: configuredPath.trim().ifBlank { "<unselected>" }
            )
        }

        val deployDirectory = when {
            !realDeployEnabled -> simulatedDirectory
            validCanonicalPath != null -> File(validCanonicalPath)
            else -> null
        }
        val unavailableReason = when {
            deployDirectory != null -> null
            reselectionRequired -> "The target path must be reselected."
            validation != null -> validation.findings
                .firstOrNull { it.severity == GameTargetValidationSeverity.ERROR }
                ?.let { "${it.title} ${it.details}".trim() }
                ?: "The target failed validation."
            else -> "No usable target path is selected."
        }

        val stateDir = deploymentManifestFile.parentFile ?: File(appFilesDir, "state")
        val manifestFile = File(
            stateDir,
            buildTargetScopedFileNameForIdentity(manifestPrefix, identity)
        )
        val baselineFile = baselinePrefix?.let {
            File(stateDir, buildTargetScopedFileNameForIdentity(it, identity))
        }
        val hash = hashManifestKey(identity.stableKey())
        val backupDirectory = File(
            stateDir,
            "deployment_backups/${identity.gameId}_${identity.mode}_$hash/$backupScope"
        )

        return ResolvedDeploymentTarget(
            targetType = targetType,
            identity = identity,
            deployDirectory = deployDirectory,
            manifestFile = manifestFile,
            backupDirectory = backupDirectory,
            baselineFile = baselineFile,
            validation = validation,
            unavailableReason = unavailableReason
        )
    }


    private fun canonicalOrAbsolute(file: File): File {
        return runCatching { file.canonicalFile }
            .getOrElse { file.absoluteFile }
    }


    private fun requireTargetSnapshotStillCurrent(targets: ResolvedDeploymentTargets) {
        val current = resolveDeploymentTargets(targets.gameId)
        check(
            current.configSnapshot == targets.configSnapshot &&
                    current.data.identity == targets.data.identity &&
                    current.root.identity == targets.root.identity
        ) {
            "Deployment target identity changed while the operation was being prepared. No files were written."
        }
    }


    private fun hashManifestKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }


    private fun buildTargetScopedFileNameForIdentity(
        prefix: String,
        identity: DeploymentTargetIdentity,
        extension: String = "json"
    ): String {
        val hash = hashManifestKey(identity.stableKey())
        return "${prefix}_${identity.gameId}_${identity.mode}_$hash.$extension"
    }


    fun getDeploymentTargetDebugSummary(gameId: String): String {
        val targets = resolveDeploymentTargets(gameId)

        return buildString {
            appendLine("Deployment Target Snapshot")
            appendLine(targets.data.toDebugSummary())
            appendLine(targets.root.toDebugSummary())

            targets.data.validation?.let {
                appendLine()
                append(it.toDebugSummary())
            }
            targets.root.validation?.let {
                appendLine()
                append(it.toDebugSummary())
            }

            val dataValidation = targets.data.validation
            val rootValidation = targets.root.validation
            if (dataValidation != null && rootValidation != null) {
                val relationshipFindings = gameTargetValidator.validateRelationship(
                    dataResult = dataValidation,
                    rootResult = rootValidation
                )
                if (relationshipFindings.isNotEmpty()) {
                    appendLine()
                    appendLine("Target Relationship Validation")
                    relationshipFindings.forEach { finding ->
                        appendLine("  ${finding.severity}: ${finding.title}")
                        if (finding.details.isNotBlank()) {
                            appendLine("    ${finding.details}")
                        }
                    }
                }
            }
        }
    }


    private fun checkPreflight(targets: ResolvedDeploymentTargets, plan: ScopedDeploymentPlan): DeploymentPreflightResult {
        val base = deploymentPreflightChecker.check(targets.configSnapshot, plan)
        if (!base.canDeploy) return base
        val capacityIssues = DeploymentStorageCapacity.assess(targets, plan, storageCapacityProbe).map { fact ->
            val (severity, title) = when (fact.status) {
                StorageCapacityStatus.INSUFFICIENT -> DeploymentPreflightSeverity.ERROR to
                    "Not enough free storage for the deployment copy budget."
                StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES -> DeploymentPreflightSeverity.INFO to
                    "Measured deployment copy budget fits available storage."
                StorageCapacityStatus.UNKNOWN -> DeploymentPreflightSeverity.WARNING to
                    "Deployment storage capacity is unknown or incomplete."
            }
            DeploymentPreflightIssue(severity, title, fact.toDebugSummary())
        }
        if (capacityIssues.isEmpty()) return base
        return DeploymentPreflightResult(base.issues.filterNot { it.title == "No preflight issues found." } + capacityIssues)
    }

    fun buildDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val targets = resolveDeploymentTargets(gameId)
        return checkPreflight(targets, buildDeploymentPlanForTargets(targets))
    }

    fun requireDeploymentPreflightForGame(gameId: String): DeploymentPreflightResult {
        val targets = resolveDeploymentTargets(gameId)
        val result = checkPreflight(targets, buildDeploymentPlanForTargets(targets))

        if (!result.canDeploy) {
            throw DeploymentPreflightException(result)
        }

        requireTargetSnapshotStillCurrent(targets)
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
        preflight: DeploymentPreflightResult,
        targets: ResolvedDeploymentTargets
    ): DeploymentJournalRecord {
        val startedAt = System.currentTimeMillis()
        return DeploymentJournalRecord(
            operationId = "${startedAt}_$gameId",
            gameId = gameId,
            profileId = getCurrentProfileIdForJournal(),
            status = DeploymentJournalStatus.STARTED,
            startedAtEpochMillis = startedAt,
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
            failureMessage = null,
            dataTarget = targets.data.toJournalTargetState(),
            rootTarget = targets.root.toJournalTargetState()
        )
    }


    private fun ResolvedDeploymentTarget.toJournalTargetState(): DeploymentJournalTargetState {
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


    private data class RecoveryEvidence(
        val fact: DeploymentRecoveryFact,
        val details: String,
        val capabilities: DeploymentRecoveryCapabilities = DeploymentRecoveryCapabilities()
    )

    private fun requireJournalScope(record: DeploymentJournalRecord, gameId: String) {
        check(record.profileId == getCurrentProfileIdForJournal() && record.gameId == gameId) {
            "Journal belongs to another profile/game; no recovery action is allowed."
        }
    }

    private fun recordedTargetsMatch(record: DeploymentJournalRecord, targets: ResolvedDeploymentTargets): Boolean? {
        if (record.dataTarget == null || record.rootTarget == null) return null
        return record.dataTarget == targets.data.toJournalTargetState() &&
            record.rootTarget == targets.root.toJournalTargetState()
    }

    private fun collectRecoveryEvidence(gameId: String): RecoveryEvidence = try {
        val file = getDeploymentJournalFile(gameId)
        val text = try { ManagedContentIntegrity.readEvidence(file) }
            catch (_: java.nio.file.NoSuchFileException) { null }
        val record = text?.let { DeploymentJournalRepository(file).decode(it) }
        if (record == null) RecoveryEvidence(DeploymentRecoveryFact.NONE, "No deployment journal.") else {
            requireJournalScope(record, gameId)
            val matches = runCatching { recordedTargetsMatch(record, resolveInspectionTargets(gameId)) }.getOrNull()
            val fact = when (record.status) {
                DeploymentJournalStatus.STARTED -> DeploymentRecoveryFact.UNFINISHED
                DeploymentJournalStatus.FAILED -> DeploymentRecoveryFact.FAILED
                DeploymentJournalStatus.REVIEWED -> DeploymentRecoveryFact.REVIEWED
                DeploymentJournalStatus.COMPLETED -> DeploymentRecoveryFact.NONE
            }
            RecoveryEvidence(fact, record.toDebugSummary(), DeploymentRecoveryCapabilities(
                reviewAllowed = record.status == DeploymentJournalStatus.STARTED && matches == true,
                recordedTargetsMatch = matches
            ))
        }
    } catch (failure: Exception) {
        RecoveryEvidence(DeploymentRecoveryFact.UNKNOWN,
            "Journal evidence is unreadable, invalid or belongs to another profile/game. ${failure.message.orEmpty()}")
    }

    fun getDeploymentJournalStartupWarning(gameId: String): String? {
        val recovery = collectRecoveryEvidence(gameId)
        if (recovery.fact == DeploymentRecoveryFact.NONE) return null
        return recovery.details + "\n" + recovery.capabilities.toDebugSummary()
    }


    fun markDeploymentJournalReviewed(gameId: String): Boolean {
        val repository = DeploymentJournalRepository(
            getDeploymentJournalFile(gameId)
        )

        val record = repository.load() ?: return false

        requireJournalScope(record, gameId)
        if (record.status != DeploymentJournalStatus.STARTED) return false
        val targets = resolveInspectionTargets(gameId)
        check(recordedTargetsMatch(record, targets) == true) {
            "Recorded recovery target evidence is missing or differs from the current target; review refused."
        }
        requireTargetSnapshotStillCurrent(targets)
        repository.markReviewed(record)
        return true
    }


    fun buildFullRedeployPlanForGame(gameId: String): ScopedDeploymentPlan {
        return buildFullRedeployPlanForTargets(resolveDeploymentTargets(gameId))
    }


    private fun buildFullRedeployPlanForTargets(
        targets: ResolvedDeploymentTargets
    ): ScopedDeploymentPlan {
        val oldDataManifest = loadManifestForPlan(targets.data)
        val dataWinningRecords = getCurrentDataWinningRecords()
        val oldRootManifest = loadManifestForPlan(targets.root)
        val rootWinningRecords = getCurrentRootWinningRecords()
        val builder = DeploymentPlanBuilder()

        return ScopedDeploymentPlan(
            dataPlan = builder.buildFullRedeploy(
                scope = DeploymentPlanScope.DATA,
                oldManifest = oldDataManifest,
                newWinningRecords = dataWinningRecords
            ),
            rootPlan = builder.buildFullRedeploy(
                scope = DeploymentPlanScope.GAME_ROOT,
                oldManifest = oldRootManifest,
                newWinningRecords = rootWinningRecords
            )
        )
    }


    fun buildFullRedeployPlanDebugSummary(gameId: String): String {
        val targets = resolveDeploymentTargets(gameId)
        val plan = buildFullRedeployPlanForTargets(targets)
        val preflight = checkPreflight(targets, plan)

        return buildString {
            appendLine("Full Redeploy Plan")
            appendLine("This is a recovery planning check only.")
            appendLine("No files were changed.")
            appendLine()
            appendLine(buildDeploymentPlanContextSummary(gameId, targets.configSnapshot, plan))
            appendLine()
            appendLine(targets.data.toDebugSummary())
            appendLine(targets.root.toDebugSummary())
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

    internal fun resolvedDataTarget(gameId: String): ResolvedDeploymentTarget =
        resolveDeploymentTargets(gameId).data

    internal fun effectiveDataManifestFile(gameId: String): File =
        resolvedDataTarget(gameId).manifestFile

    internal fun dataTargetIdentity(gameId: String): DeploymentTargetIdentity =
        resolvedDataTarget(gameId).identity

    internal fun rootTargetIdentity(gameId: String): DeploymentTargetIdentity =
        resolveDeploymentTargets(gameId).root.identity

    internal fun targetScopedFileName(prefix: String, gameId: String): String =
        buildTargetScopedFileNameForIdentity(prefix, dataTargetIdentity(gameId))
}
