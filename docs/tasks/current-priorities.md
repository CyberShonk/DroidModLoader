# Droid Mod Loader Current Priorities

This is the short active task list. Do not use this file as the full roadmap.
Keep this file limited to the next 3 to 7 focused tasks.

## Current Rule

Only work on one focused coding task at a time. Before coding:

1. Confirm the requirement IDs.
2. Define test steps.
3. Make and review the change.
4. Run unit tests and assemble the debug APK.
5. Commit, push, and verify GitHub.

## Active Priorities

### 1. Finish direct-filesystem storage acceptance

Requirement IDs:

- REQ-STORAGE-001
- REQ-STORAGE-002
- REQ-GAME-001
- REQ-GAME-003
- REQ-MOD-001
- REQ-MOD-005
- REQ-DEPLOY-002
- REQ-PLUGIN-005
- REQ-PROFILE-002

Expected result:

- Confirm the active-profile name restores in the status area after restart.
- Preserve the completed host, permission, folder, profile, archive, deployment,
  rollback, and disposable-folder plugin checks.
- Capture same-device SAF-baseline and direct-build benchmark results.

Task definition: `docs/tasks/direct-storage-migration.md`.
Benchmark protocol: `docs/benchmarks/direct-storage.md`.

### 2. Validate game-aware plugin output and legacy timestamp ordering

Requirement IDs:

- REQ-PLUGIN-002
- REQ-PLUGIN-003
- REQ-PLUGIN-005
- REQ-PROFILE-002

Expected result:

- Preserve the completed disposable-folder checks for Skyrim LE text ordering
  and legacy-game timestamp ordering.
- Launch each supported game in its target container and confirm the generated
  activation output and effective order are consumed as intended.
- Preserve profile-specific enabled state and order.

Task definition: `docs/tasks/game-aware-plugin-ordering.md`.

### 3. Continue MainActivity responsibility extraction

Reason:

`MainActivity.kt` remains a large coordinator even after the completed workflow
extractions.

Expected result:

Continue cohesive, behavior-preserving extractions without mixing in the later
`ModEngine` service-extraction phase.

### 4. Improve archive extraction robustness

Requirement IDs:

- REQ-MOD-001

Expected result:

Improve ZIP, 7Z, and RAR compatibility and provide clearer failures for archive
variants that remain unsupported.

### 5. Define the stable 1.0 acceptance boundary

Reason:

The roadmap currently mixes core safety requirements with larger desktop-style
features, which makes the stable release boundary unclear.

Expected result:

- Identify the capabilities that must block stable 1.0.
- Classify larger Nexus, LOOT, xEdit, guide, collection, storage, and preset work
  as core, staged pre-1.0, or post-1.0.
- Record the accepted scope in the roadmap and decision log before expanding the
  active implementation list.
