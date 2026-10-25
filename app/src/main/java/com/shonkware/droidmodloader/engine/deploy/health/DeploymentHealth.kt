package com.shonkware.droidmodloader.engine.deploy.health

import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.integrity.ManagedIntegrityReport
import com.shonkware.droidmodloader.engine.install.InstallRecoveryFact

/** Completion of an operation is deliberately not a health state. */
enum class DeploymentHealthPhase(val summary: String) {
    UNKNOWN("Deployment evidence unavailable"),
    STALE_CONTEXT("Deployment context changed; refresh required"),
    RECOVERY_ATTENTION("Deployment recovery needs review"),
    TARGET_UNAVAILABLE("Deployment readiness needs attention"),
    PENDING_CHANGES("Deployment changes pending"),
    INTEGRITY_DRIFT("Recorded content differs at last check"),
    INTEGRITY_STALE("Integrity evidence is stale; rerun required"),
    INTEGRITY_PARTIAL("Integrity check incomplete or unavailable"),
    UNVERIFIED("No current integrity check"),
    MATCHED_AT_CHECK("Recorded content matched at check time")
}

enum class TargetReadiness { UNKNOWN, UNAVAILABLE, SIMULATED, READY }
enum class DeploymentRecoveryFact { UNKNOWN, NONE, UNFINISHED, FAILED, REVIEWED }
enum class DeploymentIntegrityFact { UNVERIFIED, MATCHED_AT_CHECK, DRIFT_AT_CHECK, PARTIAL, STALE }

enum class DeploymentHealthReason {
    PROFILE_REQUIRED, CONTEXT_CHANGED, INSTALL_RECOVERY_REQUIRED, UNFINISHED_JOURNAL, FAILED_JOURNAL,
    JOURNAL_UNKNOWN, TARGET_UNAVAILABLE, TARGET_UNKNOWN, RESELECTION_REQUIRED,
    PREFLIGHT_BLOCKED, PREFLIGHT_UNKNOWN, PLAN_UNKNOWN, PENDING_DEPLOYMENT,
    INTEGRITY_DRIFT, INTEGRITY_STALE, INTEGRITY_PARTIAL, INTEGRITY_UNVERIFIED,
    REVIEWED_NOT_REPAIRED, SIMULATED_TARGET, MATCHED_AT_CHECK
}

data class DeploymentTargetHealth(
    val role: GameTargetType,
    val identity: DeploymentTargetIdentity?,
    val readiness: TargetReadiness,
    val pendingChanges: Int?,
    val reselectionRequired: Boolean = false,
    val details: String = ""
)

/** Read-only capabilities; execution must revalidate journal and target evidence. */
data class DeploymentRecoveryCapabilities(
    val reviewAllowed: Boolean = false,
    val recordedTargetsMatch: Boolean? = null
) {
    fun toDebugSummary(): String = "Recorded recovery targets match current targets: ${recordedTargetsMatch ?: "unknown"}\n" +
        "Mark reviewed: ${if (reviewAllowed) "available (annotation only)" else "unavailable"}\n" +
        "Retry / rollback unavailable: the journal has no per-file recovery transaction proving a safe action. " +
        "Backup presence alone is not recovery authority. Review or dismissal does not repair or unblock deployment."
}

data class DeploymentIntegrityEvidence(
    val fact: DeploymentIntegrityFact = DeploymentIntegrityFact.UNVERIFIED,
    val observedAtEpochMillis: Long? = null,
    val inputDigest: String? = null,
    val checked: Int = 0,
    val missing: Int = 0,
    val changed: Int = 0,
    val unreadable: Int = 0,
    val unavailable: Int = 0
)

/** An immutable projection, not a serialized store or permission to deploy/repair. */
data class DeploymentHealth(
    val profileId: String,
    val gameId: String,
    val phase: DeploymentHealthPhase,
    val reasons: List<DeploymentHealthReason>,
    val targets: List<DeploymentTargetHealth>,
    val recovery: DeploymentRecoveryFact,
    val integrity: DeploymentIntegrityEvidence,
    val preflightCanDeploy: Boolean?,
    val collectedAtEpochMillis: Long,
    val recoveryDetails: String = "",
    val installRecoveryRequired: Boolean = false,
    val preflightDetails: String = "",
    val recoveryCapabilities: DeploymentRecoveryCapabilities = DeploymentRecoveryCapabilities(),
    val installRecoveryFacts: List<InstallRecoveryFact> = emptyList()
) {
    fun isFor(profileId: String?, gameId: String): Boolean =
        this.profileId == profileId.orEmpty() && this.gameId == gameId

    fun toDebugSummary(): String = buildString {
        appendLine("Deployment Health: ${phase.summary}")
        appendLine("Profile: $profileId; game: $gameId; collected: $collectedAtEpochMillis (epoch ms)")
        appendLine("Reasons: ${reasons.joinToString { it.name }}")
        targets.forEach { target ->
            appendLine("${target.role.displayName}: ${target.readiness}; pending changes: ${target.pendingChanges ?: "unknown"}")
            appendLine("  ${target.identity?.displaySummary() ?: "Target identity unknown"}")
            if (target.details.isNotBlank()) appendLine("  ${target.details}")
        }
        appendLine("Preflight can deploy: ${preflightCanDeploy ?: "unknown"}")
        if (preflightDetails.isNotBlank()) appendLine(preflightDetails)
        appendLine("Recovery: $recovery; install recovery attention: $installRecoveryRequired")
        if (recoveryDetails.isNotBlank()) appendLine(recoveryDetails)
        appendLine(recoveryCapabilities.toDebugSummary())
        installRecoveryFacts.take(8).forEach { appendLine("Install recovery: ${it.toDebugSummary()}") }
        if (installRecoveryFacts.size > 8) appendLine("${installRecoveryFacts.size - 8} additional install recovery records.")
        if (installRecoveryRequired) appendLine("Manual install retry / rollback unavailable. Existing startup recovery rules remain authoritative; unresolved transactions are preserved.")
        appendLine("Integrity: ${integrity.fact}; observed: ${integrity.observedAtEpochMillis ?: "not checked"}")
        appendLine("  checked=${integrity.checked}, missing=${integrity.missing}, changed=${integrity.changed}, unreadable=${integrity.unreadable}, unavailable=${integrity.unavailable}")
        integrity.inputDigest?.let { appendLine("  Check input digest: $it") }
        appendLine("Integrity is evidence at check time, not continuous disk or game compatibility verification.")
    }
}

internal data class ScopedIntegrityObservation(
    val profileId: String,
    val gameId: String,
    val report: ManagedIntegrityReport,
    val journalRevision: String
)

internal data class DeploymentHealthFacts(
    val profileId: String,
    val gameId: String,
    val targets: List<DeploymentTargetHealth>,
    val recovery: DeploymentRecoveryFact,
    val integrity: DeploymentIntegrityEvidence,
    val preflightCanDeploy: Boolean?,
    val collectedAtEpochMillis: Long,
    val recoveryDetails: String = "",
    val contextStillCurrent: Boolean = true,
    val installRecoveryRequired: Boolean = false,
    val preflightDetails: String = "",
    val recoveryCapabilities: DeploymentRecoveryCapabilities = DeploymentRecoveryCapabilities(),
    val installRecoveryFacts: List<InstallRecoveryFact> = emptyList()
)

/** Pure deterministic precedence. Lower-priority reasons remain visible in the same result. */
internal object DeploymentHealthProjector {
    fun invalidate(health: DeploymentHealth): DeploymentHealth = project(DeploymentHealthFacts(
        health.profileId, health.gameId, health.targets, health.recovery,
        if (health.integrity.observedAtEpochMillis == null) health.integrity
        else health.integrity.copy(fact = DeploymentIntegrityFact.STALE), health.preflightCanDeploy,
        health.collectedAtEpochMillis, health.recoveryDetails, contextStillCurrent = false,
        installRecoveryRequired = health.installRecoveryRequired, preflightDetails = health.preflightDetails,
        recoveryCapabilities = health.recoveryCapabilities.copy(reviewAllowed = false),
        installRecoveryFacts = health.installRecoveryFacts
    ))

    fun project(facts: DeploymentHealthFacts): DeploymentHealth {
        val reasons = linkedSetOf<DeploymentHealthReason>()
        val installRecoveryRequired = facts.installRecoveryRequired || facts.installRecoveryFacts.isNotEmpty()
        if (facts.profileId.isBlank() || facts.gameId.isBlank()) reasons += DeploymentHealthReason.PROFILE_REQUIRED
        if (!facts.contextStillCurrent) reasons += DeploymentHealthReason.CONTEXT_CHANGED
        if (installRecoveryRequired) reasons += DeploymentHealthReason.INSTALL_RECOVERY_REQUIRED
        when (facts.recovery) {
            DeploymentRecoveryFact.UNKNOWN -> reasons += DeploymentHealthReason.JOURNAL_UNKNOWN
            DeploymentRecoveryFact.UNFINISHED -> reasons += DeploymentHealthReason.UNFINISHED_JOURNAL
            DeploymentRecoveryFact.FAILED -> reasons += DeploymentHealthReason.FAILED_JOURNAL
            DeploymentRecoveryFact.REVIEWED -> reasons += DeploymentHealthReason.REVIEWED_NOT_REPAIRED
            DeploymentRecoveryFact.NONE -> Unit
        }
        if (facts.targets.map { it.role }.toSet() != GameTargetType.entries.toSet() || facts.targets.size != 2 ||
            facts.targets.any { it.readiness == TargetReadiness.UNKNOWN || it.identity == null || it.identity.gameId != facts.gameId }) {
            reasons += DeploymentHealthReason.TARGET_UNKNOWN
        }
        if (facts.targets.any { it.readiness == TargetReadiness.UNAVAILABLE }) reasons += DeploymentHealthReason.TARGET_UNAVAILABLE
        if (facts.targets.any { it.reselectionRequired }) reasons += DeploymentHealthReason.RESELECTION_REQUIRED
        if (facts.targets.any { it.readiness == TargetReadiness.SIMULATED }) reasons += DeploymentHealthReason.SIMULATED_TARGET
        if (facts.targets.any { it.pendingChanges == null || it.pendingChanges < 0 }) reasons += DeploymentHealthReason.PLAN_UNKNOWN
        if (facts.targets.any { (it.pendingChanges ?: 0) > 0 }) reasons += DeploymentHealthReason.PENDING_DEPLOYMENT
        when (facts.preflightCanDeploy) {
            null -> reasons += DeploymentHealthReason.PREFLIGHT_UNKNOWN
            false -> reasons += DeploymentHealthReason.PREFLIGHT_BLOCKED
            true -> Unit
        }
        reasons += when (facts.integrity.fact) {
            DeploymentIntegrityFact.UNVERIFIED -> DeploymentHealthReason.INTEGRITY_UNVERIFIED
            DeploymentIntegrityFact.MATCHED_AT_CHECK -> DeploymentHealthReason.MATCHED_AT_CHECK
            DeploymentIntegrityFact.DRIFT_AT_CHECK -> DeploymentHealthReason.INTEGRITY_DRIFT
            DeploymentIntegrityFact.PARTIAL -> DeploymentHealthReason.INTEGRITY_PARTIAL
            DeploymentIntegrityFact.STALE -> DeploymentHealthReason.INTEGRITY_STALE
        }
        if (facts.integrity.fact != DeploymentIntegrityFact.STALE &&
            (facts.integrity.changed > 0 || facts.integrity.missing > 0)) {
            reasons += DeploymentHealthReason.INTEGRITY_DRIFT
        }
        val phase = when {
            DeploymentHealthReason.PROFILE_REQUIRED in reasons -> DeploymentHealthPhase.UNKNOWN
            DeploymentHealthReason.CONTEXT_CHANGED in reasons -> DeploymentHealthPhase.STALE_CONTEXT
            installRecoveryRequired || facts.recovery in setOf(DeploymentRecoveryFact.UNFINISHED, DeploymentRecoveryFact.FAILED, DeploymentRecoveryFact.REVIEWED) -> DeploymentHealthPhase.RECOVERY_ATTENTION
            DeploymentHealthReason.TARGET_UNAVAILABLE in reasons || DeploymentHealthReason.RESELECTION_REQUIRED in reasons ||
                facts.preflightCanDeploy == false -> DeploymentHealthPhase.TARGET_UNAVAILABLE
            DeploymentHealthReason.INTEGRITY_DRIFT in reasons -> DeploymentHealthPhase.INTEGRITY_DRIFT
            reasons.any { it in setOf(DeploymentHealthReason.JOURNAL_UNKNOWN, DeploymentHealthReason.TARGET_UNKNOWN,
                DeploymentHealthReason.PREFLIGHT_UNKNOWN, DeploymentHealthReason.PLAN_UNKNOWN) } -> DeploymentHealthPhase.UNKNOWN
            DeploymentHealthReason.PENDING_DEPLOYMENT in reasons -> DeploymentHealthPhase.PENDING_CHANGES
            facts.integrity.fact == DeploymentIntegrityFact.STALE -> DeploymentHealthPhase.INTEGRITY_STALE
            facts.integrity.fact == DeploymentIntegrityFact.PARTIAL -> DeploymentHealthPhase.INTEGRITY_PARTIAL
            facts.integrity.fact == DeploymentIntegrityFact.MATCHED_AT_CHECK -> DeploymentHealthPhase.MATCHED_AT_CHECK
            else -> DeploymentHealthPhase.UNVERIFIED
        }
        return DeploymentHealth(facts.profileId, facts.gameId, phase, reasons.sortedBy { it.ordinal },
            facts.targets.sortedBy { it.role.ordinal }, facts.recovery, facts.integrity,
            facts.preflightCanDeploy, facts.collectedAtEpochMillis, facts.recoveryDetails,
            installRecoveryRequired, facts.preflightDetails,
            facts.recoveryCapabilities.copy(reviewAllowed = facts.contextStillCurrent &&
                facts.recovery == DeploymentRecoveryFact.UNFINISHED &&
                facts.recoveryCapabilities.recordedTargetsMatch == true && facts.recoveryCapabilities.reviewAllowed),
            facts.installRecoveryFacts)
    }

    fun integrityEvidence(
        report: ManagedIntegrityReport?,
        currentInputDigest: String?,
        contextMatches: Boolean
    ): DeploymentIntegrityEvidence {
        if (report == null) return DeploymentIntegrityEvidence()
        val fact = when {
            !contextMatches || report.stale || currentInputDigest == null || report.inputDigest != currentInputDigest -> DeploymentIntegrityFact.STALE
            report.cancelled || report.groups.isEmpty() || report.groups.any { !it.complete } -> DeploymentIntegrityFact.PARTIAL
            report.groups.any { it.changed > 0 || it.missing > 0 } -> DeploymentIntegrityFact.DRIFT_AT_CHECK
            else -> DeploymentIntegrityFact.MATCHED_AT_CHECK
        }
        return DeploymentIntegrityEvidence(fact, report.observedAtEpochMillis, report.inputDigest,
            report.groups.sumOf { it.checked }, report.groups.sumOf { it.missing }, report.groups.sumOf { it.changed },
            report.groups.sumOf { it.unreadable }, report.groups.sumOf { it.unavailable })
    }
}
