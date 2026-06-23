# Direct Filesystem Storage Migration

## Type

Feature, refactor, safety, test, and documentation.

## Implementation Status

The direct-storage code migration, host Gradle validation, debug APK assembly,
focused tests, disposable-folder Android checks, startup-profile regression
check, and exploratory same-device benchmark are complete. A publication-grade
benchmark remains optional future work rather than an acceptance blocker.

## Requirement IDs

- REQ-STORAGE-001
- REQ-STORAGE-002
- REQ-GAME-001
- REQ-GAME-003
- REQ-MOD-001
- REQ-MOD-005
- REQ-DEPLOY-002
- REQ-PLUGIN-005
- REQ-PROFILE-002

## Problem

Before this migration, DML mixed Android Storage Access Framework tree URIs with
ordinary filesystem paths. That split blocked reliable plugin timestamp ordering
and required parallel implementations for folder selection, scanning, deployment,
archive browsing, and recovery.

The app's core responsibility is direct management of game and mod files in
shared storage. The accepted storage direction is therefore one direct-path
backend using Android's all-files access permission.

## Desired Behavior

- DML requests and verifies all-files access before shared-storage workflows.
- Folder selection uses a DML-owned direct filesystem browser.
- Game Data, Game Root, and Archive Library selections are stored as canonical
  absolute paths.
- Deployment, archive scanning/import, plugin discovery, overwrite scanning,
  baseline scanning, repair, and timestamp ordering use ordinary filesystem
  APIs.
- Existing URI-only profile selections are preserved as migration state and
  require explicit safe reselection instead of guessed URI-to-path conversion.
- Profiles continue to isolate target and Archive Library selections.
- Production SAF code and the DocumentFile dependency are removed only after
  every production call site is converted.

## Scope

1. Record the direct-storage architectural decision.
2. Add and request `MANAGE_EXTERNAL_STORAGE`.
3. Add a reusable direct filesystem folder browser.
4. Replace Data and Game Root tree-URI selection with direct paths.
5. Replace Archive Library tree-URI selection with a profile-specific path.
6. Detect legacy URI-only selections and require explicit reselection.
7. Convert deployment and archive workflows to direct paths.
8. Remove production SAF code and the DocumentFile dependency.
9. Add permission-state, path-validation, migration, profile-isolation, archive,
   and deployment tests.
10. Add a repeatable benchmark protocol and timing instrumentation for comparing
    direct deployment with the released SAF implementation on the same device.

## Out of Scope

- The accepted 1.0 UI redesign.
- New game definitions, including TTW.
- Fallout 3 or Oblivion setup redesign beyond direct folder selection.
- `DML_output`.
- LOOT or xEdit integration.
- INI presets or configuration recipes.
- Broad `ModEngine` service extraction.
- Automatic conversion of `content://` identifiers into filesystem paths.
- A public version change or release.

## Storage Model

### Permission

DML requires Android 11 (API 30) or newer. Every supported Android version
requires all-files access for direct shared-storage workflows. DML must check
the current grant and direct the user to the app-specific system settings screen
when access is absent.

### Stored paths

The canonical persisted values are:

- `GameProfile.targetDataPath`
- `GameProfile.targetRootPath`
- matching `GameDeploymentConfig` path fields
- one profile-keyed Archive Library path

New saves must not persist tree-URI fields.

### Legacy migration

When a profile or game configuration contains a saved tree URI but no usable
corresponding direct path:

- preserve the profile and all unrelated state;
- clear the unusable runtime target;
- mark the affected Data or Game Root selection as requiring reselection;
- block real deployment until required Data reselection is completed;
- do not infer a path by parsing the URI;
- clear each migration flag only after the user selects and saves a valid direct
  folder.

A legacy Archive Library URI similarly becomes a profile-specific reselection
notice and no archive folder is assumed.

## Path Rules

A selectable folder must:

- be represented by an absolute path;
- exist;
- be a directory;
- be readable;
- be writable when selected as a deployment target;
- resolve canonically before persistence;
- remain inside the selected directory when child paths are resolved.

Archive Library selection requires read access. Deployment targets require read
and write access.

## Failure Behavior

- Missing all-files access blocks opening the direct folder browser and offers
  the permission settings action.
- Invalid or inaccessible selections are rejected without replacing the prior
  valid path.
- Legacy selections remain marked for reselection until a valid replacement is
  saved.
- Real deployment with a missing or invalid Data path fails preflight rather
  than silently using simulated output.
- Archive scan/import failures leave the original archive untouched.
- Existing deployment journal, backup, and rollback behavior remains active.

## Automated Tests

- all-files permission policy and settings-intent behavior for supported Android versions;
- settings-intent action selection;
- canonical path validation for readable and writable directories;
- rejection of blank, relative, missing, and non-directory paths;
- child-path containment checks;
- profile repository migration of legacy Data and Game Root URIs;
- game-config repository migration of legacy URIs;
- migration flags clear only after valid direct paths are saved;
- Archive Library path preference isolation by profile;
- legacy Archive Library URI requires reselection;
- direct archive scan identity and filtering;
- archive workflow installs by direct file path;
- deployment preflight blocks missing/unwritable real targets;
- plugin and overwrite scans use the selected direct Data path;
- existing profile isolation tests continue to pass.

## Manual Runtime Verification

1. Install or upgrade on Android 11 or newer with all-files access disabled.
2. Confirm DML explains the requirement and opens the app-specific settings page.
3. Grant access, return to DML, and confirm the browser becomes available.
4. Select Data, Game Root, and Archive Library folders and restart DML.
5. Confirm canonical direct paths persist for the active profile.
6. Create a second profile with different paths and verify isolation.
7. Upgrade a profile containing only tree URIs and verify explicit reselection
   without loss of mods, plugins, or profile identity.
8. Scan and install ZIP, 7Z, and RAR archives from the direct Archive Library.
9. Deploy a safe test mod containing many small files and verify journal,
   backup, rollback, manifest, and overwrite behavior.
10. Apply plugin changes for all selectable games using disposable Data folders.
11. Run the benchmark protocol against the released SAF build and the migrated
    direct-path build on the same device and fixture.

## Recorded Android Results — 2026-06-22

Passed on disposable shared-storage folders:

- all-files permission onboarding and return from the Android Settings screen;
- direct Game Root, Data, and profile-specific Archive Library selection;
- path and profile state persistence across restart;
- two-profile isolation;
- legacy URI reselection behavior without unrelated profile-state loss;
- ZIP, 7Z, and RAR archive handling;
- direct deployment, full redeploy, backup, and restoration of a pre-existing
  unmanaged file; and
- direct plugin scanning and timestamp mutation.

A startup presentation defect was found and fixed: the persisted active profile
ID was restored, but the status card retained `No profile` until the same profile
was selected again. The focused startup hotfix now restores the profile name from
the resolved active profile, and the Android restart regression check passed.

Regular deployment does not rewrite an externally changed target when the saved
manifest and winning source hash are unchanged. Force Full Redeploy does rewrite
the complete winning file set. No user-facing external-change scan is currently
available.

## Recorded Exploratory Benchmark — 2026-06-22

An AYN Thor running Android 13 / API 33 compared SAF commit `3480a14` with
direct-storage commit `80dc515` using the deterministic fixtures documented in
`docs/benchmarks/direct-storage.md`.

- Stable later small-file samples: approximately 108.8 seconds SAF versus
  14.0 seconds direct.
- Stable large-file samples after excluding the direct cold-start outlier:
  approximately 491 ms SAF median versus 319 ms direct median.

The result supports the direct-filesystem architecture. Because the capture used
fewer than five measured samples, did not separate every warm-up consistently,
and contained one major cold-start outlier, it is recorded as exploratory rather
than as a definitive public multiplier.

## Likely Affected Structures

- `AndroidManifest.xml`
- `MainActivity.kt`
- `ui/MainScreen.kt`
- direct-folder browser Compose components and controller/model
- `engine/model/GameProfile.kt`
- `engine/model/GameDeploymentConfig.kt`
- profile and game-config repositories
- Archive Library preferences, scanner, browser workflow, and import workflow
- deployment preflight and deployment target resolution
- plugin, overwrite, baseline, and repair scanners
- storage-related tests and documentation

## Done When

- [x] Direct paths are the only production shared-storage backend.
- [x] All-files permission is requested and checked.
- [x] Data, Game Root, and Archive Library selections use the direct browser.
- [x] Existing URI-only profiles require safe reselection.
- [x] Deployment and archive workflows no longer use SAF.
- [x] Production `DocumentFile`/tree-URI code and dependency are removed.
- [x] Required automated tests pass.
- [x] Debug APK assembles.
- [x] Manual disposable-folder Android checks pass.
- [x] Benchmark procedure and deterministic fixtures are recorded.
- [x] Exploratory same-device SAF-baseline and direct-build results are captured.

## Suggested Commit Sequence

1. `docs: adopt direct filesystem storage architecture`
2. `feat: add all-files access permission flow`
3. `feat: add direct filesystem folder browser`
4. `refactor: migrate profiles and targets to direct paths`
5. `refactor: migrate archive library to direct paths`
6. `refactor: remove SAF deployment backend`
7. `test: add direct deployment benchmark tooling`
8. `docs: reconcile direct storage behavior and validation`
