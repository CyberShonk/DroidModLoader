# Droid Mod Loader Current Priorities

This is the short active task list.

Do not use this file as the full roadmap.

Keep this file limited to the next 3 to 7 focused tasks.

## Current Rule

Only work on 1 focused coding task at a time.

Before coding:

1. Pick one task.
2. Confirm the requirement IDs.
3. Define test steps.
4. Make the change.
5. Test it.
6. Commit it.
7. Push it.
8. Check GitHub.

## Active Priorities

### 1. Hide Developer Tools behind dev mode

~~Requirement IDs:~~

- ~~REQ-UI-002~~

~~Reason:

~~Developer tools should not be visible to normal users.~~

~~Expected result:~~

~~Developer Tools only appear after developer mode is unlocked.~~

### 2. Keep Recovery Tools visible

~~Requirement IDs:~~

- ~~REQ-RECOVERY-003~~
- ~~REQ-UI-002~~

~~Reason:~~
~~Recovery tools are user-facing safety tools, not developer-only tools.~~

~~Expected result:~~

~~Recovery Tools remain reachable even when Developer Tools are hidden.~~

### 3. Show real version in diagnostics

~~Requirement IDs:~~

- ~~REQ-DIAG-001~~
- ~~REQ-RELEASE-001~~

~~Reason:~~

~~Diagnostics should report the actual app version.~~

~~Expected result:~~

~~Diagnostics use `versionName` from the build config/package info instead of hardcoded text.~~

### 4. Add unfinished deploy warning

Requirement IDs:

- REQ-RECOVERY-001
- REQ-RECOVERY-002

Reason:

Interrupted deployment needs visible recovery behavior.

Expected result:

App detects unfinished deployment journal state and warns the user.