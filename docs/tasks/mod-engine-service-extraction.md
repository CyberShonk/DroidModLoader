# ModEngine Service Extraction

## Status

Implemented structural task for the merged `f451672` baseline. `ModEngine`
remains the public engine facade while cohesive responsibilities live in focused
internal services. Acceptance requires the isolated validator and Android smoke
checks before merge.

## Goal

Reduce `ModEngine.kt` from a multi-domain implementation class into a stable
facade that delegates to focused services for:

1. mod library and state management;
2. plugin discovery, persistence, ordering, and output;
3. deployment planning, execution, preflight, target identity, and journal work;
4. resolved-view inspection, file previews, overwrite scans, baselines, and
   inspection indexes; and
5. downloaded-archive history.

A **facade** is a stable entry point that preserves the current method surface
while forwarding work to smaller classes. Existing UI workflow adapters should
continue calling `ModEngine` during this task.

## Requirement coverage

The extraction must preserve behavior covered by:

- `REQ-MOD-001` through `REQ-MOD-004`;
- `REQ-PLUGIN-001` through `REQ-PLUGIN-005`;
- `REQ-DEPLOY-001` through `REQ-DEPLOY-003`;
- `REQ-RECOVERY-001` through `REQ-RECOVERY-003`;
- `REQ-PROFILE-002`; and
- `REQ-STORAGE-001`.

No requirement is expanded by this task.

## Verified baseline responsibilities

Before extraction, `ModEngine.kt` owned all of the following:

- installed-mod discovery, state persistence, uninstall, and priority
  normalization;
- mod scanning, conflict resolution, deploy-scope classification, and winning
  record calculation;
- archive installation, prepared installer sessions, installed-record metadata,
  and downloaded-archive history;
- game deployment configuration, target validation, deployment planning,
  preflight, execution, full redeploy, target-scoped manifests/backups, and
  deployment journals;
- plugin discovery, saved state, game-specific output, timestamp ordering, Data
  folder discovery, and plugin priorities;
- resolved-data graph construction, content indexes, file previews, overwrite
  scans, Data baselines, target identity summaries, and file-index operations.

The class is constructed only through `ProfileScopedEngineFactory`, and current
production callers use the `ModEngine` method surface through focused UI workflow
adapters. No JVM test currently instantiates `ModEngine` directly.

## Implemented ownership

- `ModLibraryService` owns installed-mod state, scanning/resolution, installation,
  priorities, uninstall/reset, and content/index entry points.
- `PluginManagementService` owns discovery, saved plugin state, ordering, Data
  scanning, and game-aware output application.
- `DeploymentService` owns deployment configuration, direct-path validation,
  planning, preflight, execution, full redeploy, target-scoped manifests and
  backups, and deployment journals.
- `ModInspectionService` owns resolved graph construction, file previews,
  overwrite scans, Data baselines, and inspection indexes.
- `DownloadedArchiveService` owns archive-history registration, lookup,
  installation marking, and summaries.
- `ModEngine` owns facade construction and delegation only.

## Dependency direction

The target dependency direction is:

```text
UI workflow adapters
        |
        v
   ModEngine facade
        |
        +--> ModLibraryService
        +--> PluginManagementService
        +--> DeploymentService
        +--> ModInspectionService
        +--> DownloadedArchiveService
```

Services may depend on existing repositories, scanners, resolvers, planners,
and managers. They must not depend on `MainActivity`, Compose state, or UI
workflow classes.

Cross-service information should be supplied through narrow constructor
callbacks or immutable values. Services must not hold a reference back to the
`ModEngine` facade.

## Commit boundaries

### Commit 1 — task definition

Document this boundary, tests, exclusions, and done criteria before moving code.

### Commit 2 — mod library service

Extract installed-mod discovery, state, scanning/resolution, install metadata,
prepared installation, priorities, uninstall, reset, and index/content entry
points. Move `UninstallResult` to its own file while preserving its package and
fully qualified class name.

### Commit 3 — plugin management service

Extract plugin discovery, persistence, output application, Data-folder scan,
and priority ordering.

### Commit 4 — deployment service

Extract deployment configuration, target identity and debug summaries,
planning, preflight, execution, full redeploy, target-scoped files/backups, and
journal handling.

### Commit 5 — inspection service

Extract resolved graph/debug output, file previews, overwrite scans, Data
baseline handling, and inspection indexing.

### Commit 6 — downloaded archive service

Extract downloaded-archive registration, lookup, installation marking, and
summary generation.

### Commit 7 — documentation reconciliation

Record final ownership in the architecture/source map and mark the task complete
only after full validation. Production-code cleanup belongs to the service
extraction commit that makes it possible; this final commit is documentation-only.

## Automated validation

Every code-changing commit must run:

```bash
git diff --check
./tools/check-docs.sh
./tools/check-project.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Add focused JVM tests for each extracted service. Tests must use the repository's
JUnit 4 API and must construct current model types with all required fields.

The final series validator must:

- use `set -euo pipefail`;
- apply patches in an isolated Git worktree;
- run `git apply --check` before every patch;
- compile and test after every code patch;
- stop immediately on any failed command; and
- never print success after a masked failure.

## Manual Android checks

After the complete series:

1. launch with an existing active profile;
2. switch profiles and confirm isolated mod/plugin/path state;
3. install and remove a disposable archive;
4. toggle and reorder mods and plugins;
5. apply game-specific plugin configuration;
6. run normal deployment and Force Full Redeploy;
7. open deploy-plan, target, journal, overwrite, baseline, content, and archive
   diagnostics; and
8. restart DML and confirm persisted state is unchanged.

## Behavior that must remain unchanged

- Public `ModEngine` method names and return types used by production callers.
- Profile-scoped paths and persistence.
- Conflict winner selection and priority semantics.
- Direct-path validation and simulated-target fallback.
- Deployment planning, backup/restoration, journaling, and recovery warnings.
- Game-specific plugin activation and ordering.
- Archive installation and saved installer behavior.
- Overwrite/baseline classifications and diagnostic wording unless a test proves
  an intentional correction.

## Explicit exclusions

- 1.0 UI redesign;
- new features, integrations, or game definitions;
- TTW setup;
- `DML_output`;
- LOOT or xEdit integration;
- INI presets or configuration recipes;
- archive-format compatibility changes;
- coroutine/threading modernization;
- repository-format migrations;
- renaming the public `ModEngine` facade; and
- removal of existing workflow adapters.

## Done criteria

The task is complete when:

- `ModEngine` remains the stable public facade but no longer implements the five
  extracted domains directly;
- each service has a coherent constructor and focused JVM coverage;
- all existing and new JVM tests pass;
- the debug APK assembles;
- documentation describes actual ownership;
- the final patch series replays from `f451672` in an isolated worktree; and
- Android smoke checks find no behavior regression.
