# MainActivity Responsibility Extraction

## Status

Active.

## Goal

Finish the long-running responsibility extraction from `MainActivity.kt`
without changing user-visible behavior. `MainActivity` should remain the Android
lifecycle and composition root while focused classes own reusable state mapping,
startup/session coordination, logging/status reporting, diagnostics, direct
folder selection, and dashboard refresh behavior.

This is structural refactoring. It is not a feature task.

## Requirement coverage

The extraction must preserve behavior covered by:

- `REQ-GAME-001`, `REQ-GAME-002`, and `REQ-GAME-003`;
- `REQ-MOD-001` through `REQ-MOD-005`;
- `REQ-PLUGIN-001`, `REQ-PLUGIN-002`, `REQ-PLUGIN-003`, and `REQ-PLUGIN-005`;
- `REQ-DEPLOY-001` through `REQ-DEPLOY-003`;
- `REQ-RECOVERY-001` through `REQ-RECOVERY-003`;
- `REQ-PROFILE-001` and `REQ-PROFILE-002`;
- `REQ-DIAG-001` and `REQ-DIAG-002`;
- `REQ-UI-001` and `REQ-UI-002`; and
- `REQ-STORAGE-001` and `REQ-STORAGE-002`.

No requirement is intentionally changed by this task.

## Current problem

`MainActivity.kt` is still more than 2,200 lines and owns several unrelated
responsibilities in addition to Android lifecycle wiring:

- Compose state projection and action construction;
- operation status and file-backed session logging;
- `ModEngine` and profile repository construction;
- startup/profile/game-configuration hydration;
- dashboard refresh and plugin synchronization;
- developer diagnostics and support-report assembly;
- direct folder browser and all-files permission coordination;
- second-screen state decisions; and
- obsolete v0.5.0 artifact repair tooling.

The existing workflow classes and controllers already provide useful seams. The
remaining extraction should extend those patterns rather than introduce a new
all-purpose coordinator.

## Planned commit boundaries

1. Remove the obsolete v0.5.0 artifact repair feature and its engine class.
2. Extract operation status and session-log reporting.
3. Extract profile-scoped engine/repository construction.
4. Extract startup and profile/game configuration coordination.
5. Extract dashboard refresh and plugin synchronization.
6. Extract developer diagnostics and support-report assembly.
7. Extract direct folder and all-files permission coordination.
8. Extract dashboard state projection and remove residual activity helpers.
9. Reconcile architecture/status documentation and record the final activity
   responsibility boundary.

Each commit must compile and pass the relevant focused tests before the next
responsibility moves.

## Behavior that must remain unchanged

- Existing profiles, paths, mods, plugins, archive history, and deployment state
  remain profile-isolated.
- Startup restores the active profile and its visible status immediately.
- Existing archive import, installer, mod, plugin, deployment, recovery,
  overwrite, and second-screen actions remain available in the same UI paths.
- Operation-in-progress guards and user-facing status/log messages remain
  equivalent.
- Background work and UI-thread updates retain their current threading model.
- Direct storage remains the only production shared-storage backend.
- Developer tools remain hidden unless developer mode is unlocked.
- Recovery tools remain available to normal users.

## Automated validation

At minimum:

```bash
git diff --check
./tools/check-docs.sh
./tools/check-project.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Add focused JVM tests for newly extracted pure Kotlin mappers, coordinators, or
factories. Do not introduce coroutines or a new state-management framework as an
incidental refactor.

## Manual checks

After the complete series:

1. Launch DML with an existing profile and confirm the status card is hydrated.
2. Switch profiles and confirm paths, mods, plugins, and Archive Library state
   remain isolated.
3. Open the direct folder browser for Data, Game Root, Archive Library, and new
   profile setup.
4. Import one archive and open installer choices when applicable.
5. Toggle/reorder a mod and plugin, then apply their changes.
6. Run normal deploy, full redeploy confirmation, and recovery details.
7. Open developer summaries with developer mode enabled.
8. Share logs and confirm the support text/file still opens through Android.
9. Toggle the second-screen plugin display on supported hardware when available.

## Explicit exclusions

- the accepted 1.0 UI redesign;
- new UI navigation or visual behavior;
- broad `ModEngine` service extraction;
- new game definitions or TTW setup;
- plugin-system redesign or real-container verification;
- archive-format hardening;
- LOOT, xEdit, `DML_output`, INI presets, or configuration recipes;
- coroutine/threading modernization; and
- new features or opportunistic cleanup outside `MainActivity` ownership.

## Done criteria

The task is complete when:

- obsolete v0.5.0 repair code is removed;
- `MainActivity` primarily owns Android lifecycle, Activity Result launchers,
  `setContent`, top-level dependency wiring, and platform UI launches;
- extracted classes each have one coherent responsibility;
- no replacement god coordinator is introduced;
- required checks pass; and
- architecture/status documents describe the resulting ownership accurately.
