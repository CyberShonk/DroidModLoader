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

### 1. Migrate all shared-storage workflows to direct filesystem paths

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

- Request and verify all-files access on Android 11 and newer.
- Use a DML-owned direct folder browser for Data, Game Root, and Archive Library.
- Store canonical paths rather than tree URIs.
- Preserve existing profiles through explicit safe reselection.
- Convert deployment, scanning, archive import, repair, and timestamp ordering to direct paths.
- Remove production SAF code only after every call site is migrated.
- Add migration, validation, isolation, and performance coverage.

Task definition: `docs/tasks/direct-storage-migration.md`.

### 2. Validate game-aware plugin output and legacy timestamp ordering

Requirement IDs:

- REQ-PLUGIN-002
- REQ-PLUGIN-003
- REQ-PLUGIN-005
- REQ-PROFILE-002

Expected result:

- Preserve the implemented per-game activation and ordering rules.
- Complete runtime checks after the direct-storage migration supplies writable real paths.
- Verify Skyrim LE text ordering and timestamp ordering for Oblivion, Fallout 3, and Fallout: New Vegas.
- Preserve profile-specific enabled state and order.

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
