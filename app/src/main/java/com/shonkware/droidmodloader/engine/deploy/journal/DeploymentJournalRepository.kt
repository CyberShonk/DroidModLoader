package com.shonkware.droidmodloader.engine.deploy.journal

import com.shonkware.droidmodloader.engine.data.CriticalMetadataFile
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableObject
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.nullableString
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredBoolean
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredInt
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredLong
import com.shonkware.droidmodloader.engine.data.CriticalMetadataJson.requiredString
import org.json.JSONObject
import java.io.File

class DeploymentJournalRepository internal constructor(
    private val metadataFile: CriticalMetadataFile
) {
    constructor(journalFile: File) : this(CriticalMetadataFile(journalFile))

    fun saveStarted(record: DeploymentJournalRecord) {
        check(record.status == DeploymentJournalStatus.STARTED) { "Expected STARTED journal" }
        val existing = load()
        check(existing?.status != DeploymentJournalStatus.STARTED) {
            buildString {
                append("Refusing to overwrite unfinished deployment journal")
                existing?.let {
                    append(" ${it.operationId}")
                    it.dataTarget?.let { target ->
                        append(" for ${target.mode}:${target.target}")
                    }
                }
                append(". Review the previous deployment warning before starting another deploy.")
            }
        }
        save(record)
    }

    fun markCompleted(
        record: DeploymentJournalRecord,
        resultSummary: DeploymentJournalResultSummary
    ) {
        requireCurrentStarted(record)
        save(
            record.copy(
                status = DeploymentJournalStatus.COMPLETED,
                completedAtEpochMillis = System.currentTimeMillis(),
                resultSummary = resultSummary,
                failureMessage = null
            )
        )
    }

    fun markFailed(
        record: DeploymentJournalRecord,
        message: String
    ) {
        requireCurrentStarted(record)
        save(
            record.copy(
                status = DeploymentJournalStatus.FAILED,
                completedAtEpochMillis = System.currentTimeMillis(),
                failureMessage = message
            )
        )
    }

    /** Only an absent file is absent evidence; blank, invalid or unreadable files throw. */
    fun load(): DeploymentJournalRecord? = metadataFile.read()?.let(::decode)

    internal fun decode(text: String): DeploymentJournalRecord =
        CriticalMetadataJson.decode("deployment journal", text) { value ->
            val json = value as JSONObject
            val version = if (json.has("schemaVersion")) json.requiredInt("schemaVersion") else 1
            require(version in 1..2) { "Unsupported deployment journal schema: $version" }
            val planJson = json.getJSONObject("planSummary")
            val resultJson = json.nullableObject("resultSummary")
            DeploymentJournalRecord(
                schemaVersion = version,
                operationId = json.requiredString("operationId"),
                gameId = json.requiredString("gameId"),
                profileId = json.requiredString("profileId"),
                status = DeploymentJournalStatus.valueOf(json.requiredString("status")),
                startedAtEpochMillis = json.requiredLong("startedAtEpochMillis"),
                completedAtEpochMillis = json.nullableLong("completedAtEpochMillis"),
                planSummary = DeploymentJournalPlanSummary(
                    dataOperationCount = planJson.requiredInt("dataOperationCount"),
                    rootOperationCount = planJson.requiredInt("rootOperationCount"),
                    totalOperationCount = planJson.requiredInt("totalOperationCount"),
                    dataEstimatedCopyBytes = planJson.nullableLong("dataEstimatedCopyBytes"),
                    rootEstimatedCopyBytes = planJson.nullableLong("rootEstimatedCopyBytes"),
                    preflightCanDeploy = planJson.requiredBoolean("preflightCanDeploy"),
                    preflightErrorCount = planJson.requiredInt("preflightErrorCount"),
                    preflightWarningCount = planJson.requiredInt("preflightWarningCount")
                ),
                resultSummary = resultJson?.let {
                    DeploymentJournalResultSummary(
                        addCount = it.requiredInt("addCount"),
                        updateCount = it.requiredInt("updateCount"),
                        removeCount = it.requiredInt("removeCount"),
                        backupCount = it.requiredInt("backupCount"),
                        restoreCount = it.requiredInt("restoreCount"),
                        protectedConflictCount = it.requiredInt("protectedConflictCount"),
                        finalRecordCount = it.requiredInt("finalRecordCount")
                    )
                },
                failureMessage = json.nullableString("failureMessage"),
                dataTarget = json.nullableObject("dataTarget")?.toTargetState(),
                rootTarget = json.nullableObject("rootTarget")?.toTargetState()
            ).also { record ->
                val validState = when (record.status) {
                    DeploymentJournalStatus.STARTED ->
                        record.completedAtEpochMillis == null && record.resultSummary == null
                    DeploymentJournalStatus.COMPLETED ->
                        record.completedAtEpochMillis != null && record.resultSummary != null
                    DeploymentJournalStatus.FAILED, DeploymentJournalStatus.REVIEWED ->
                        record.completedAtEpochMillis != null
                }
                require(validState) { "Inconsistent deployment journal status/completion evidence" }
            }
        }

    private fun requireCurrentStarted(record: DeploymentJournalRecord) {
        check(record.status == DeploymentJournalStatus.STARTED && load() == record) {
            "Refusing to replace missing or changed deployment journal evidence."
        }
    }

    private fun save(record: DeploymentJournalRecord) {
        val text = toJson(record).toString(2)
        decode(text)
        metadataFile.replace(text)
    }

    private fun toJson(record: DeploymentJournalRecord): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", record.schemaVersion)
            put("operationId", record.operationId)
            put("gameId", record.gameId)
            put("profileId", record.profileId)
            put("status", record.status.name)
            put("startedAtEpochMillis", record.startedAtEpochMillis)
            put("completedAtEpochMillis", record.completedAtEpochMillis ?: JSONObject.NULL)
            put("dataTarget", record.dataTarget?.toJson() ?: JSONObject.NULL)
            put("rootTarget", record.rootTarget?.toJson() ?: JSONObject.NULL)

            put(
                "planSummary",
                JSONObject().apply {
                    put("dataOperationCount", record.planSummary.dataOperationCount)
                    put("rootOperationCount", record.planSummary.rootOperationCount)
                    put("totalOperationCount", record.planSummary.totalOperationCount)
                    put("dataEstimatedCopyBytes", record.planSummary.dataEstimatedCopyBytes ?: JSONObject.NULL)
                    put("rootEstimatedCopyBytes", record.planSummary.rootEstimatedCopyBytes ?: JSONObject.NULL)
                    put("preflightCanDeploy", record.planSummary.preflightCanDeploy)
                    put("preflightErrorCount", record.planSummary.preflightErrorCount)
                    put("preflightWarningCount", record.planSummary.preflightWarningCount)
                }
            )

            val result = record.resultSummary
            put(
                "resultSummary",
                if (result == null) {
                    JSONObject.NULL
                } else {
                    JSONObject().apply {
                        put("addCount", result.addCount)
                        put("updateCount", result.updateCount)
                        put("removeCount", result.removeCount)
                        put("backupCount", result.backupCount)
                        put("restoreCount", result.restoreCount)
                        put("protectedConflictCount", result.protectedConflictCount)
                        put("finalRecordCount", result.finalRecordCount)
                    }
                }
            )

            put("failureMessage", record.failureMessage ?: JSONObject.NULL)
        }
    }

    private fun DeploymentJournalTargetState.toJson(): JSONObject {
        return JSONObject().apply {
            put("targetType", targetType)
            put("gameId", gameId)
            put("mode", mode)
            put("target", target)
            put("identityKey", identityKey)
            put("manifestFilePath", manifestFilePath)
            put("baselineFilePath", baselineFilePath ?: JSONObject.NULL)
            put("backupDirectoryPath", backupDirectoryPath)
        }
    }

    private fun JSONObject.toTargetState(): DeploymentJournalTargetState {
        return DeploymentJournalTargetState(
            targetType = requiredString("targetType"),
            gameId = requiredString("gameId"),
            mode = requiredString("mode"),
            target = requiredString("target"),
            identityKey = requiredString("identityKey"),
            manifestFilePath = requiredString("manifestFilePath"),
            baselineFilePath = nullableString("baselineFilePath"),
            backupDirectoryPath = requiredString("backupDirectoryPath")
        )
    }

    fun markReviewed(record: DeploymentJournalRecord) {
        requireCurrentStarted(record)
        save(
            record.copy(
                status = DeploymentJournalStatus.REVIEWED,
                completedAtEpochMillis = System.currentTimeMillis(),
                failureMessage = "User reviewed unfinished deploy warning. No recovery action was performed."
            )
        )
    }
}
