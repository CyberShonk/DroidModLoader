# Current Status

* **Mode:** Active development after the `v0.6.0-beta` release.
* **Public version:** `v0.6.0-beta`
* **Minimum Android version:** Android 11 / API 30
* **Implementation baseline:** `3480a14` (`fix: apply plugin configuration by game`)
* **Storage direction:** one all-files direct-filesystem backend for production shared-storage work.

## Current objective

Complete host and Android validation of the direct-storage migration, then finish
runtime verification of game-aware plugin activation and legacy timestamp order.
The accepted 1.0 UI redesign remains outside this work.

The bounded storage task is `docs/tasks/direct-storage-migration.md`. It excludes
new game definitions, `DML_output`, LOOT, xEdit, INI recipes, and broad
`ModEngine` extraction.

## Completed most recently

The current source migration:

* requests and checks Android all-files special access;
* supplies a DML-owned direct filesystem folder browser;
* stores canonical profile-specific paths for Data, Game Root, and Archive Library;
* treats legacy URI-only selections as reselection state without guessing paths;
* uses direct files for archive scanning/import, deployment, plugin scanning,
  overwrite scanning, baseline work, repair, and plugin timestamp ordering;
* removes the production tree-URI deployment manager and `DocumentFile` dependency;
* adds permission, path-validation, migration, profile-isolation, archive, and
  deployment-focused JVM tests; and
* adds deterministic benchmark fixtures and a same-device comparison protocol.

Game-aware plugin activation and ordering is also implemented in source:

* Skyrim Legendary Edition uses `plugins.txt` plus complete-order `loadorder.txt`;
* Oblivion, Fallout 3, and Fallout: New Vegas use enabled-only `plugins.txt` plus
  modification-time ordering of the complete selected plugin list; and
* timestamp application includes preflight, transactional output replacement,
  and rollback where practical.

## Current validation record

Verified while preparing the migration:

```text
git diff --check — passed
./tools/check-docs.sh — passed
focused Kotlin compilation and direct-storage smoke checks — passed
benchmark fixture and summarizer smoke checks — passed
```

`./tools/check-project.sh` reached the Gradle test step but could not download
Gradle 9.5 because `services.gradle.org` could not be resolved in the validation
environment. The JVM suite did not start, so this is not recorded as a test pass
or failure.

## Next safe action

Run the authoritative host checks against the reviewed migration:

```bash
git diff --check
./tools/check-docs.sh
./tools/check-project.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Then perform disposable-folder Android checks for permission flow, direct folder
selection, legacy-profile reselection, archive import, deployment, rollback,
profile isolation, and plugin ordering. Finally, collect the same-device benchmark
results described in `docs/benchmarks/direct-storage.md`.

## Current constraints

* Generate changes only against the latest local source.
* Keep each commit focused on one coherent responsibility.
* Explain code-changing commits before acceptance.
* Provide a reviewable diff for every code change.
* Record actual validation results; do not infer them.
* Keep current released behavior separate from future plans.
* Update documentation and changelogs when documented behavior changes.
* Update the Nexus Mods page whenever the public app version changes.
* Do not automatically commit, push, merge, tag, publish, or release.

## Known open work

* Finish host and manual validation of the direct-storage migration.
* Capture SAF-baseline versus direct-build deployment benchmark results.
* Finish manual game checks for game-aware plugin activation and timestamp order.
* Continue the remaining `MainActivity.kt` responsibility extractions in bounded commits.
* Treat broad `ModEngine.kt` service extraction as a separate later project.
* Improve 7Z and RAR extraction compatibility and failure reporting.
* Continue improving beginner-facing Game Root and Data Folder wording.
* Keep TTW setup, game-folder validation, `DML_output`, configuration recipes,
  and INI presets staged until each has its own bounded task.

## Blockers

Acceptance is blocked on the host Gradle suite, debug APK assembly, disposable
Android runtime checks, and device benchmark capture. No public version change is
part of this migration.

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
