# Current Status

* **Mode:** Active development after the `v0.6.0-beta` release.
* **Public version:** `v0.6.0-beta`
* **Minimum Android version:** Android 11 / API 30
* **Direct-storage migration baseline:** `23f5fa3` (`build: require Android 11 for direct storage`)
* **Startup hotfix:** `90d788a` (`fix: restore active profile state on startup`)
* **Runtime verification record:** `80dc515` (`docs: record direct storage runtime verification`)
* **Storage direction:** one all-files direct-filesystem backend for production shared-storage work.

## Current objective

The bounded `ModEngine.kt` service extraction is implemented. `ModEngine`
remains the stable facade used by current workflow adapters while focused
internal services own mod-library, plugin, deployment, inspection, and
archive-history behavior.

The next normal coding task is archive extraction robustness. Integrations, bug
fixes, and feature additions should return to user-written implementation with
guided review; complete generated patches remain appropriate only for tedious,
pre-scoped structural work. The accepted 1.0 UI redesign is still out of scope.

## Completed most recently

The `ModEngine` service extraction:

* preserved the public `ModEngine` method surface as a facade;
* moved mod state, scanning, resolution, installation, priority, and uninstall
  behavior into `ModLibraryService`;
* moved plugin discovery, persistence, ordering, and output application into
  `PluginManagementService`;
* moved deployment configuration, planning, preflight, execution, target-scoped
  manifests/backups, and journals into `DeploymentService`;
* moved resolved-view diagnostics, previews, overwrite scans, baselines, and
  file-index inspection into `ModInspectionService`; and
* moved downloaded-archive history into `DownloadedArchiveService`.

The final `MainActivity` extraction:

* removed the obsolete v0.5.0 artifact-repair feature and engine class;
* moved mutable Compose state and `DashboardUiState` projection into
  `MainActivityUiState.kt`;
* moved dashboard callback binding into `DashboardActionBindings.kt`;
* extracted operation reporting, session logging, startup sequencing,
  profile/session configuration, dashboard refresh, plugin synchronization,
  selected-folder persistence, diagnostics, support reports, content inspection,
  second-screen decisions, and thread coordination into focused classes;
* moved profile-scoped engine and repository construction into factories; and
* left `MainActivity` with platform UI actions and top-level workflow wiring.

The only intentional user-visible change was removal of the obsolete developer-only
v0.5.0 repair action. All other behavior and the public version remain unchanged.

The current source migration:

* requests and checks Android all-files special access;
* supplies a DML-owned direct filesystem folder browser;
* stores canonical profile-specific paths for Data, Game Root, and Archive Library;
* treats legacy URI-only selections as reselection state without guessing paths;
* uses direct files for archive scanning/import, deployment, plugin scanning,
  overwrite scanning, baseline work, backup/restoration, and plugin timestamp ordering;
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

An exploratory same-device benchmark on an AYN Thor running Android 13 compared
SAF commit `3480a14` with direct-storage commit `80dc515` using deterministic
fixtures. The stable later small-file samples were approximately 108.8 seconds
for SAF and 14.0 seconds for direct paths. After excluding the direct build's
first large-file setup outlier, the stable large-file medians were approximately
491 ms for SAF and 319 ms for direct paths. These results support the direct
filesystem architecture, but the limited sample count and imperfect warm-up
separation do not support a definitive public multiplier.

Regular incremental deployment intentionally follows the saved deployment
manifest and does not repair an externally edited deployed file when the plan is
otherwise unchanged. Force Full Redeploy rewrites the winning file set. No
user-facing external-change scan is currently exposed.

## Next safe action

Validate the complete service-extraction series in an isolated worktree,
perform the Android smoke checklist, merge it, and then begin a separately
scoped user-written archive-robustness change.

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

* Keep real-container plugin verification as deferred regression work unless a
  user report or release-specific check makes it necessary.
* Improve 7Z and RAR extraction compatibility and failure reporting.
* Continue improving beginner-facing Game Root and Data Folder wording.
* Keep TTW setup, game-folder validation, `DML_output`, configuration recipes,
  and INI presets staged until each has its own bounded task.

## Blockers

No active storage, plugin-verification, `MainActivity`, or `ModEngine`
structural blocker remains in source. Acceptance still requires the full local
validator and Android smoke checks before merge. No public version change is
part of this structural work.

## Private and public boundary

Unreleased `v0.7.0` and `v0.8.0` functionality must be identified as planned rather than current behavior.

Private experiments, unpublished research, credentials, signing material, and private project context must not be added to the public repository.

## Parking lot

* broader archive-format hardening
* additional game-specific validation
* expanded deterministic workflow tooling
* cross-project `workctl`, only after repository-local commands are proven

## Last updated

2026-06-23
