package com.shonkware.droidmodloader.engine.deploy.health

import com.shonkware.droidmodloader.engine.deploy.DeploymentTargetIdentity
import com.shonkware.droidmodloader.engine.deploy.GameTargetType
import com.shonkware.droidmodloader.engine.integrity.IntegrityCounts
import com.shonkware.droidmodloader.engine.integrity.ManagedIntegrityReport
import org.junit.Assert.*
import org.junit.Test

class DeploymentHealthProjectorTest {
    private val projector = DeploymentHealthProjector

    private fun facts() = DeploymentHealthFacts(
        "profile-a", "fallout_nv", GameTargetType.entries.map { role ->
            DeploymentTargetHealth(role,
                DeploymentTargetIdentity("fallout_nv", if (role == GameTargetType.DATA) "real_path" else "root_real_path", "/fixture/${role.name}"),
                TargetReadiness.READY, 0)
        }, DeploymentRecoveryFact.NONE, DeploymentIntegrityEvidence(), true, 100
    )

    private fun report(counts: IntegrityCounts = IntegrityCounts("Data", "target", 1, checked = 1)) =
        ManagedIntegrityReport(50, "digest", listOf(counts), false, false)

    @Test fun `no active profile is unknown rather than healthy`() {
        val health = projector.project(facts().copy(profileId = ""))
        assertEquals(DeploymentHealthPhase.UNKNOWN, health.phase)
        assertTrue(DeploymentHealthReason.PROFILE_REQUIRED in health.reasons)
    }

    @Test fun `zero pending changes with no disk evidence is unverified`() {
        val health = projector.project(facts())
        assertEquals(DeploymentHealthPhase.UNVERIFIED, health.phase)
        assertEquals(listOf(DeploymentHealthReason.INTEGRITY_UNVERIFIED), health.reasons)
    }

    @Test fun `simulated targets remain explicit without claiming a physical deployment`() {
        val health = projector.project(facts().let { facts ->
            facts.copy(targets = facts.targets.map { it.copy(readiness = TargetReadiness.SIMULATED,
                identity = it.identity!!.copy(mode = if (it.role == GameTargetType.DATA) "simulated" else "root_simulated")) })
        })
        assertEquals(DeploymentHealthPhase.UNVERIFIED, health.phase)
        assertTrue(DeploymentHealthReason.SIMULATED_TARGET in health.reasons)
        assertTrue(health.toDebugSummary().contains("SIMULATED"))
    }

    @Test fun `invalid and missing targets preserve separate Data and Root facts`() {
        val base = facts()
        val health = projector.project(base.copy(targets = listOf(base.targets[0], base.targets[1].copy(
            readiness = TargetReadiness.UNAVAILABLE, pendingChanges = null,
            reselectionRequired = true, details = "No root selected"
        )), preflightCanDeploy = false))
        assertEquals(DeploymentHealthPhase.TARGET_UNAVAILABLE, health.phase)
        assertEquals(TargetReadiness.READY, health.targets[0].readiness)
        assertEquals(TargetReadiness.UNAVAILABLE, health.targets[1].readiness)
        assertTrue(health.reasons.containsAll(listOf(DeploymentHealthReason.RESELECTION_REQUIRED,
            DeploymentHealthReason.PLAN_UNKNOWN, DeploymentHealthReason.PREFLIGHT_BLOCKED)))
        assertTrue(health.toDebugSummary().contains("No root selected"))
    }

    @Test fun `desired enabled and priority changes produce pending state`() {
        val base = facts()
        val health = projector.project(base.copy(targets = base.targets.map { it.copy(pendingChanges = 3) }))
        assertEquals(DeploymentHealthPhase.PENDING_CHANGES, health.phase)
        assertTrue(DeploymentHealthReason.INTEGRITY_UNVERIFIED in health.reasons)
        assertEquals(6, health.targets.sumOf { it.pendingChanges!! })
    }

    @Test fun `matched report is only evidence at its captured check time`() {
        val evidence = projector.integrityEvidence(report(), "digest", true)
        val health = projector.project(facts().copy(integrity = evidence))
        assertEquals(DeploymentHealthPhase.MATCHED_AT_CHECK, health.phase)
        assertEquals(50L, health.integrity.observedAtEpochMillis)
        assertEquals("digest", health.integrity.inputDigest)
        assertTrue(health.toDebugSummary().contains("not continuous disk"))
    }

    @Test fun `changed and missing recorded files produce drift`() {
        for (counts in listOf(IntegrityCounts("Data", "target", 1, checked = 1, changed = 1),
            IntegrityCounts("Game Root", "target", 1, missing = 1))) {
            val evidence = projector.integrityEvidence(report(counts), "digest", true)
            val health = projector.project(facts().copy(integrity = evidence))
            assertEquals(DeploymentHealthPhase.INTEGRITY_DRIFT, health.phase)
            assertTrue(DeploymentHealthReason.INTEGRITY_DRIFT in health.reasons)
        }
    }

    @Test fun `stale digest and wrong profile or target scope cannot verify current bytes`() {
        for ((digest, matches) in listOf("other-digest" to true, null to true, "digest" to false)) {
            val evidence = projector.integrityEvidence(report(), digest, matches)
            assertEquals(DeploymentIntegrityFact.STALE, evidence.fact)
            assertEquals(DeploymentHealthPhase.INTEGRITY_STALE, projector.project(facts().copy(integrity = evidence)).phase)
        }
        assertEquals(DeploymentIntegrityFact.STALE,
            projector.integrityEvidence(report().copy(stale = true), "digest", true).fact)
    }

    @Test fun `partial cancelled unreadable unavailable and empty checks are not matched`() {
        val partial = listOf(report().copy(cancelled = true), report().copy(groups = emptyList()),
            report(IntegrityCounts("Data", "target", 1, unreadable = 1)),
            report(IntegrityCounts("Data", "target", 1, unavailable = 1)))
        for (report in partial) {
            val evidence = projector.integrityEvidence(report, "digest", true)
            assertEquals(DeploymentIntegrityFact.PARTIAL, evidence.fact)
            assertEquals(DeploymentHealthPhase.INTEGRITY_PARTIAL, projector.project(facts().copy(integrity = evidence)).phase)
        }
    }

    @Test fun `partial checks retain known drift but stale old drift cannot become current`() {
        val evidence = projector.integrityEvidence(report(IntegrityCounts("Data", "target", 2,
            checked = 1, changed = 1, unreadable = 1)), "digest", true)
        val health = projector.project(facts().copy(integrity = evidence))
        assertEquals(DeploymentHealthPhase.INTEGRITY_DRIFT, health.phase)
        assertTrue(health.reasons.containsAll(listOf(DeploymentHealthReason.INTEGRITY_DRIFT,
            DeploymentHealthReason.INTEGRITY_PARTIAL)))
        val stale = projector.integrityEvidence(report(IntegrityCounts("Data", "target", 1,
            checked = 1, changed = 1)), "new-digest", true)
        val staleHealth = projector.project(facts().copy(integrity = stale))
        assertEquals(DeploymentHealthPhase.INTEGRITY_STALE, staleHealth.phase)
        assertFalse(DeploymentHealthReason.INTEGRITY_DRIFT in staleHealth.reasons)
    }

    @Test fun `unfinished journal takes precedence without erasing pending and integrity reasons`() {
        val base = facts()
        val health = projector.project(base.copy(
            recovery = DeploymentRecoveryFact.UNFINISHED,
            targets = base.targets.map { it.copy(pendingChanges = 1) },
            integrity = DeploymentIntegrityEvidence(DeploymentIntegrityFact.STALE)
        ))
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION, health.phase)
        assertTrue(health.reasons.containsAll(listOf(DeploymentHealthReason.UNFINISHED_JOURNAL,
            DeploymentHealthReason.PENDING_DEPLOYMENT, DeploymentHealthReason.INTEGRITY_STALE)))
    }

    @Test fun `failed or reviewed journal never creates verified integrity`() {
        assertEquals(DeploymentHealthPhase.RECOVERY_ATTENTION,
            projector.project(facts().copy(recovery = DeploymentRecoveryFact.FAILED)).phase)
        val reviewed = projector.project(facts().copy(recovery = DeploymentRecoveryFact.REVIEWED))
        assertEquals(DeploymentHealthPhase.UNVERIFIED, reviewed.phase)
        assertTrue(DeploymentHealthReason.REVIEWED_NOT_REPAIRED in reviewed.reasons)
    }

    @Test fun `unreadable journal or plan is unknown rather than clean`() {
        assertEquals(DeploymentHealthPhase.UNKNOWN,
            projector.project(facts().copy(recovery = DeploymentRecoveryFact.UNKNOWN)).phase)
        val base = facts()
        assertEquals(DeploymentHealthPhase.UNKNOWN,
            projector.project(base.copy(targets = base.targets.map { it.copy(pendingChanges = null) })).phase)
        assertEquals(DeploymentHealthPhase.UNKNOWN,
            projector.project(base.copy(preflightCanDeploy = null)).phase)
    }

    @Test fun `missing duplicate or wrong-game target roles are unknown`() {
        val base = facts()
        val wrong = base.targets[0].copy(identity = base.targets[0].identity!!.copy(gameId = "other"))
        for (targets in listOf(emptyList(), listOf(base.targets[0], base.targets[0]), listOf(wrong, base.targets[1]))) {
            assertEquals(DeploymentHealthPhase.UNKNOWN, projector.project(base.copy(targets = targets)).phase)
        }
    }

    @Test fun `late context change supersedes otherwise matched facts`() {
        val health = projector.project(facts().copy(contextStillCurrent = false,
            integrity = DeploymentIntegrityEvidence(DeploymentIntegrityFact.MATCHED_AT_CHECK)))
        assertEquals(DeploymentHealthPhase.STALE_CONTEXT, health.phase)
        assertFalse(health.isFor("profile-b", "fallout_nv"))
        assertFalse(health.isFor("profile-a", "oblivion"))
        assertTrue(health.isFor("profile-a", "fallout_nv"))
    }

    @Test fun `projection and reason ordering are deterministic without mutating facts`() {
        val base = facts().copy(recovery = DeploymentRecoveryFact.REVIEWED)
        val first = projector.project(base)
        assertEquals(first, projector.project(base))
        assertEquals(first, projector.project(base.copy(targets = base.targets.reversed())))
        assertEquals(listOf(GameTargetType.DATA, GameTargetType.GAME_ROOT), base.targets.map { it.role })
    }
}
