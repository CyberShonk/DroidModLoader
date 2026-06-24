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

### 1. Execute the bounded ModEngine service extraction

Reason:

`ModEngine.kt` still owns too many engine responsibilities. It should be split
through a separate patch-driven structural task rather than mixed with feature
or integration work.

Expected result:

- Keep `ModEngine` as the stable facade used by current workflow adapters.
- Extract mod-library, plugin, deployment, inspection, and downloaded-archive
  responsibilities into focused internal services.
- Preserve behavior and profile isolation through small validated commits.
- Follow `docs/tasks/mod-engine-service-extraction.md`.

### 2. Improve archive extraction robustness

Requirement IDs:

- REQ-MOD-001

Expected result:

Improve ZIP, 7Z, and RAR compatibility and provide clearer failures for archive
variants that remain unsupported.

### 3. Define the stable 1.0 acceptance boundary

Reason:

The roadmap currently mixes core safety requirements with larger desktop-style
features, which makes the stable release boundary unclear.

Expected result:

- Identify the capabilities that must block stable 1.0.
- Classify larger Nexus, LOOT, xEdit, guide, collection, storage, and preset work
  as core, staged pre-1.0, or post-1.0.
- Record the accepted scope in the roadmap and decision log before expanding the
  active implementation list.
