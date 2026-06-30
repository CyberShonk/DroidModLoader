# Droid Mod Loader Current Priorities

This page tracks the focused work immediately after `v0.7.0-beta`. It is
shorter than the full roadmap and should not be used as a substitute for scoped
implementation tasks.

## 1. Monitor the `v0.7.0-beta` release

**Status:** Active

Review reproducible reports for:

- archive compatibility and failure classification;
- cancellation and cleanup behavior;
- interrupted replacement recovery;
- direct-path profile persistence;
- plugin ordering and output; and
- safe deployment behavior.

Only release-blocking regressions should interrupt the next planning phase.

## 2. Preserve archive-install safety guarantees

**Status:** Ongoing release rule

Archive changes must continue to preserve these guarantees:

- archive type is determined from content rather than the filename extension;
- unsafe or excessive extraction is rejected before becoming installed state;
- cancellation removes partial output where possible;
- replacing a mod preserves the previous installation until promotion succeeds;
- interrupted replacement state remains recoverable; and
- unsupported variants fail with a specific, understandable message.

## 3. Scope `v0.8.0-beta`

**Status:** Next implementation-planning task

Prepare coherent tasks for:

- stronger Game Root and `Data` validation;
- clearer setup and reselection guidance;
- deployment and recovery polish;
- safe output-location handling; and
- release-grade diagnostics for those workflows.

Do not pull broad UI redesign, LOOT, xEdit, collections, or full Nexus browsing
into the first `v0.8.0-beta` task without a separate scope decision.

## 4. Define the stable 1.0 acceptance boundary

**Status:** Ongoing planning

Classify Nexus integration, target validation, deployment and recovery polish,
conflict presentation, adaptive navigation, separators, details views, themes,
LOOT and xEdit support, guides, collections, storage tools, and presets as
stable blockers, staged pre-1.0 work, optional enhancements, or post-1.0 work.

## 5. Preserve the extracted architecture

**Status:** Ongoing maintenance rule

Keep `MainActivity` as the Android composition root and `ModEngine` as the stable
engine facade. Add behavior to the focused workflow, service, repository, or
engine component that owns it.

## Locked future design reference

Quiet Workbench is the accepted design reference for the planned `v0.9.0-beta` interface. It does not replace the current scoped `v0.8.0-beta` work and must not be described as current released behavior.

When UI implementation is scheduled, scope tasks from `docs/ui-ux.md` and preserve its locked hierarchy, state meanings, themes, accessibility boundaries, and safety behavior.

## Last updated

2026-06-30
