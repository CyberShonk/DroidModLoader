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

### 1. Complete the archive-folder browser

Requirement IDs:

- REQ-MOD-001
- REQ-MOD-005
- REQ-UI-001

Expected result:

- DML remembers a user-selected archive folder.
- The fullscreen Archive Library scans, searches, refreshes, and installs files
  through the existing archive import pipeline.
- Installed and previously installed status is profile-aware.
- Required docs and manual tests are current.

### 2. Preserve fullscreen list state consistently

Requirement IDs:

- REQ-UI-001

Expected result:

Dashboard, Mods, Plugins, and Archive Library scroll positions remain stable
while opening dialogs, pickers, and fullscreen panels during the current session.

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
