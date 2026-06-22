# Current Status

* **Mode:** Active development after the `v0.6.0-beta` release.
* **Public version:** `v0.6.0-beta`
* **Reconciliation baseline:** `508bca2` (merged post-`v0.6.0` documentation reconciliation)
* **Repository state:** Local `main` matched `origin/main` with a clean working tree when this reconciliation began.

## Current objective

Migrate production shared-storage workflows from mixed SAF/tree-URI and local
path handling to one all-files direct-path backend. This migration must preserve
profiles and existing managed state, require explicit reselection for legacy
URI-only folders, and retain deployment safety behavior.

The bounded task is `docs/tasks/direct-storage-migration.md`. It intentionally
excludes the accepted 1.0 UI redesign, new game definitions, `DML_output`, LOOT,
xEdit, INI recipes, and broad `ModEngine` extraction.

## Completed most recently

Published `v0.6.0-beta` and pushed commit `191397a`, which scopes archive-folder selection, persisted folder access, archive history, and related settings to individual profiles.

The release includes the archive-folder browser workflow for:

* selecting and retaining an archive folder;
* discovering top-level ZIP, 7Z, and RAR archives;
* searching and refreshing the archive list;
* changing archive-folder locations;
* installing archives through the existing import and installer pipeline; and
* keeping archive settings and history isolated by profile.
* Verified current-session fullscreen list-state retention for Dashboard, Mods, Plugins, and Archive Library. Dedicated regression coverage remains future test work.

## Last recorded validation

The earlier archive-folder browser implementation recorded successful results for:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
git diff --check
```

The exact final release validation result was not preserved in this file. Do not infer unrecorded test results.

The repository itself was confirmed clean and synchronized with `origin/main` on 2026-06-17.

## Next safe action

Implement the direct-storage task in small reviewable commits, beginning with
the permission boundary and direct folder browser. Run automated checks after
each coherent change. After migration, complete disposable-folder runtime
verification for deployment, archive import, profile isolation, and plugin order.

## Current constraints

* Generate changes only against the latest local source.
* Keep each commit focused on one coherent responsibility.
* Explain code-changing commits before acceptance.
* Provide a reviewable diff for every code change.
* Run appropriate tests and builds before committing code.
* Keep current released behavior separate from future plans.
* Update documentation and changelogs when documented behavior changes.
* Update the Nexus Mods page whenever the public app version changes.
* Do not automatically commit, push, merge, tag, publish, release, or make destructive changes.

## Known open work

* Finish host and manual validation of game-aware plugin activation and legacy timestamp ordering.
* Continue the remaining `MainActivity.kt` responsibility extractions in bounded commits.
* Treat `ModEngine.kt` service extraction as a separate later project.
* Improve 7Z and RAR extraction compatibility and failure reporting.
* Continue improving beginner-facing Game Root and Data Folder wording.
* Keep TTW setup, game-folder validation, `DML_output`, configuration recipes,
  and INI presets staged in the backlog until each is converted into a bounded
  task.
* Keep guide documentation accurate for the currently released DML version.

## Blockers

The scoped plugin-order implementation still requires full host Gradle validation
and manual safe-folder/runtime verification before acceptance.

## Private and public boundary

Unreleased `v0.7.0` and `v0.8.0` functionality must be identified as planned rather than current behavior.

Private experiments, unpublished research, credentials, signing material, and private project context must not be added to the public repository.

## Parking lot

* `ModEngine.kt` service extraction
* broader archive-format hardening
* additional game-specific validation
* expanded deterministic workflow tooling
* cross-project `workctl`, only after repository-local commands are proven

## Last updated

2026-06-22
