# Current Status

* **Mode:** Active development after the `v0.6.0-beta` release.
* **Public version:** `v0.6.0-beta`
* **Reconciliation baseline:** `e8c27ee` (`docs: refresh current project status`)
* **Repository state:** Local `main` matched `origin/main` with a clean working tree when this reconciliation began.

## Current objective

Finish the documentation-only priority reconciliation, then begin one scoped
plugin-correctness task without mixing in later setup, output, or preset work.

Before beginning another code change:

1. Review and commit the proposed priority, backlog, roadmap, requirement, and
   decision updates.
2. Create a scoped task or GitHub Issue for game-specific plugin ordering.
3. Do not begin implementation until requirements, tests, and done criteria are recorded.

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

Scope the game-aware plugin output and legacy timestamp-ordering task.

The task must first confirm the currently selectable game definitions. It must
fix timestamp ordering for affected current profiles, keep Skyrim LE behavior
separate, and leave a reusable strategy for later TTW, Fallout 3, and Oblivion
definitions. It must define automated tests, manual
runtime verification, failure handling, and profile isolation before code is
changed.

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

* Correct game-aware plugin activation output and legacy timestamp ordering.
* Continue the remaining `MainActivity.kt` responsibility extractions in bounded commits.
* Treat `ModEngine.kt` service extraction as a separate later project.
* Improve 7Z and RAR extraction compatibility and failure reporting.
* Continue improving beginner-facing Game Root and Data Folder wording.
* Keep TTW setup, game-folder validation, `DML_output`, configuration recipes,
  and INI presets staged in the backlog until each is converted into a bounded
  task.
* Keep guide documentation accurate for the currently released DML version.

## Blockers

No repository blocker is currently recorded.

The selected next implementation task is plugin-order correctness. It is not yet scoped for coding.

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

2026-06-17
