# MainActivity Responsibility Extraction

## Status

Complete.

## Goal

Finish the long-running responsibility extraction from `MainActivity.kt` while
preserving current behavior except for the explicitly approved removal of the obsolete
v0.5.0 developer repair action. `MainActivity` should remain the Android
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

No supported product requirement is intentionally changed by this task. The removed
v0.5.0 repair command was obsolete developer-only compatibility tooling.

## Completed result

The activity was reduced from more than 2,200 lines to a composition-root file
whose remaining bulk is top-level construction and dependency wiring. Reusable
behavior now lives in focused classes rather than private activity methods.

Key ownership after extraction:

- `MainActivityUiState.kt` owns mutable activity-scoped Compose state and the
  immutable `DashboardUiState` projection.
- `DashboardActionBindings.kt` maps UI callbacks to focused workflow controllers.
- `OperationReporter.kt` and `SessionLogWriter.kt` own operation state and logs.
- `ProfileSessionCoordinator.kt`, `AppStartupCoordinator.kt`, and
  `SelectedFolderConfigurationCoordinator.kt` own startup and profile/config
  orchestration.
- `DashboardRefreshCoordinator.kt` and `PluginSynchronizationWorkflow.kt` own
  dashboard and plugin refresh coordination.
- focused diagnostics, recovery, direct-folder, content-inspection,
  second-screen, and thread coordinators own their named responsibilities.
- profile-scoped engine and repository factories own construction details.

`MainActivity` retains lifecycle callbacks, Activity Result registration,
`setContent`, Android settings/share/dialog/toast launches, second-display
attachment, and top-level dependency wiring.

## Completed commit boundaries

1. Removed the obsolete v0.5.0 artifact repair feature and engine class.
2. Extracted operation status and session-log reporting.
3. Extracted profile-scoped engine/repository construction.
4. Extracted plugin synchronization, dashboard refresh, diagnostics, recovery,
   support reporting, direct-folder selection, profile inspection, second-screen
   decisions, and thread execution.
5. Extracted activity UI state projection and dashboard action binding.
6. Extracted profile/session, selected-folder, and startup coordination.
7. Removed residual operation-report forwarding helpers.
8. Reconciled architecture/status documentation with the final boundary.

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

## Completion criteria

The completed series satisfies the structural criteria:

- obsolete v0.5.0 repair code is removed;
- `MainActivity` primarily owns Android lifecycle, Activity Result launchers,
  `setContent`, top-level dependency wiring, and platform UI launches;
- extracted classes each have one coherent responsibility;
- no replacement god coordinator was introduced; and
- architecture/status documents describe the resulting ownership accurately.

The live repository must still run the required Gradle checks and manual smoke
checks when applying the patch series.
