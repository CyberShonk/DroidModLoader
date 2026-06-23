# Current Status

* **Mode:** Active development after the `v0.6.0-beta` release.
* **Public version:** `v0.6.0-beta`
* **Minimum Android version:** Android 11 / API 30
* **Reviewed migration baseline:** `23f5fa3` (`build: require Android 11 for direct storage`)
* **Storage direction:** one all-files direct-filesystem backend for production shared-storage work.

## Current objective

Complete the active-profile startup hotfix, capture the same-device storage
benchmark, and finish real-container verification of game-aware plugin output.
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

Verified on the live development machine:

```text
git diff --check — passed
./tools/check-docs.sh — passed
./tools/check-project.sh — passed
./gradlew testDebugUnitTest — passed
./gradlew assembleDebug — passed
```

Recorded Android 11+ disposable-folder checks passed for:

* all-files permission onboarding and return from Settings;
* direct Game Root, Data, and Archive Library selection and persistence;
* profile-isolated paths and state;
* ZIP, 7Z, and RAR archive handling;
* direct deployment, full redeploy, backup, and restoration;
* Skyrim text-file plugin ordering; and
* Oblivion, Fallout 3, and Fallout: New Vegas timestamp ordering.

Regular incremental deployment intentionally follows the saved deployment
manifest and does not repair an externally edited deployed file when the plan is
otherwise unchanged. Force Full Redeploy rewrites the winning file set. No
user-facing external-change scan is currently exposed.

## Next safe action

Validate the active-profile startup hotfix on Android:

1. select a profile;
2. force-stop DML;
3. relaunch it; and
4. confirm the status area immediately shows the persisted profile name.

Then collect the same-device SAF-baseline versus direct-build benchmark results
from `docs/benchmarks/direct-storage.md` and run real game/container checks for
the generated activation files and effective plugin order.

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

* Confirm the active-profile status hotfix on Android after restart.
* Capture SAF-baseline versus direct-build deployment benchmark results.
* Finish real-container game checks for activation files and effective plugin order.
* Continue the remaining `MainActivity.kt` responsibility extractions in bounded commits.
* Treat broad `ModEngine.kt` service extraction as a separate later project.
* Improve 7Z and RAR extraction compatibility and failure reporting.
* Continue improving beginner-facing Game Root and Data Folder wording.
* Keep TTW setup, game-folder validation, `DML_output`, configuration recipes,
  and INI presets staged until each has its own bounded task.

## Blockers

Acceptance is blocked on the startup hotfix retest, device benchmark capture, and
real-container plugin verification. No public version change is part of this
migration.

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
