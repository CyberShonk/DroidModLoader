# Droid Mod Loader Decision Log

This file records major technical, product, UI, storage, and release decisions for Droid Mod Loader.

The purpose is to prevent repeated arguments, forgotten context, and scattered project direction.

## When to Add a Decision

Add a decision when the project chooses one path over another in a way that affects future work.

Examples:

- choosing physical deployment instead of virtual filesystem mounting
- changing profile behavior
- changing deployment safety rules
- changing target game priority
- changing Android storage strategy
- changing UI navigation model
- adding or removing a major pre-1.0 feature
- changing release policy
- deciding that a feature is intentionally deferred

Do not add tiny implementation details unless they affect future design.

## Decision Format

Use this format:

```text
## YYYY-MM-DD - Decision Title

Status:
Accepted, Revised, Superseded, or Deferred.

Decision:
What was decided.

Reason:
Why this decision was made.

Result:
What this changes for the project.

Related:
Requirement IDs, roadmap section, issue link, or file paths.



```

## 2026-06-06 - Use Indexed Physical Deployment Instead of VFS

Status:
Accepted.

Decision:
Droid Mod Loader will use indexed physical deployment instead of trying to recreate Mod Organizer 2's virtual filesystem.

Reason:
Android storage restrictions, shared-folder behavior, and GameNative use make physical deployment the correct core model for this project.

Result:
The app must track installed mods, file indexes, resolved winners, deployment manifests, target identity, verification, diagnostics, and recovery.

Related:
REQ-RESOLVE-001, REQ-RESOLVE-002, REQ-DEPLOY-001, REQ-DEPLOY-002, REQ-RECOVERY-001.

## 2026-06-06 - GameNative Is the Main Early Target

Status:
Accepted.

Decision:
GameNative is the main early test environment for Droid Mod Loader.

Reason:
The app is being built around Android Bethesda modding through Windows-container setups and shared folders.

Result:
Droid Mod Loader should work through shared folders, exported plugin files, and user-selected game folders. It should not depend on private GameNative internals.

Related:
REQ-GAME-001, REQ-GAME-002, REQ-GAME-003.

## 2026-06-06 - File Safety Comes First

Status:
Accepted.

Decision:
Deployment safety is more important than fast feature completion.

Reason:
The app can modify user game folders. Bad file operations can break installs, overwrite user work, or create hard-to-debug states.

Result:
Deployment needs preflight checks, deployment plans, journals, backups where needed, verification where practical, and recovery tools.

Related:
REQ-DEPLOY-001, REQ-DEPLOY-002, REQ-DEPLOY-003, REQ-RECOVERY-001, REQ-RECOVERY-002, REQ-RECOVERY-003.

## 2026-06-06 - Profiles Are a Core Concept

Status:
Accepted.

Decision:
Profiles are first-class app state, not just a visual grouping.

Reason:
Users need separate setups for clean testing, personal mod lists, game-specific setups, and future guide or collection states.

Result:
Profiles should eventually isolate mod state, plugin state, target identity, deployment manifests, diagnostics, and recovery state.

Related:
REQ-PROFILE-001, REQ-PROFILE-002.

## 2026-06-06 - Developer Tools Must Be Hidden

Status:
Accepted.

Decision:
Developer tools should not be visible in the normal user path.

Reason:
Visible developer tools confuse normal users and make the app feel unfinished.

Result:
Developer tools should be hidden behind developer mode. Recovery tools should remain available outside developer mode because recovery is a user safety feature.

Related:
REQ-UI-002, REQ-RECOVERY-003.

## 2026-06-06 - Recovery Tools Are User-Facing Safety Tools

Status:
Accepted.

Decision:
Recovery tools should not be treated as developer-only tools.

Reason:
A normal user may need recovery after interrupted deployment, bad target state, or stale journal warnings.

Result:
Recovery tools should be reachable from normal UI, but dangerous recovery actions need clear labels and explanations.

Related:
REQ-RECOVERY-002, REQ-RECOVERY-003, REQ-UI-002.

## 2026-06-06 - Handmade Branding Assets Are Required

Status:
Accepted.

Decision:
Official DML branding assets should be handmade, and editable source files should be preserved.

Reason:
The project needs a consistent visual identity and maintainable source assets.

Result:
Editable source assets live under `assets/source/`. Generated Android resources live under `app/src/main/res/`.

Related:
REQ-ASSET-001, REQ-ASSET-002.

## 2026-06-06 - Main UI Should Use Progressive Disclosure

Status:
Accepted.

Decision:
The main UI should not show every tool, warning, setting, diagnostic, and action at once.

Reason:
The current direction needs to be beginner-readable. Advanced tools should exist, but the main path should stay clear.

Result:
The UI should prioritize the basic path: select game, import mods, manage plugins, review warnings, deploy, diagnose problems.

Related:
REQ-UI-001, REQ-UI-002.

## 2026-06-06 - Non-Trivial Work Uses Scoped Tasks

Status:
Accepted.

Decision:
New non-trivial work should start from a scoped task, GitHub Issue, or current-priorities entry.

Reason:
Unscoped work is harder to review, test, and maintain.

Result:
A scoped task should define the problem, desired behavior, requirement IDs, likely affected files, test steps, and done criteria before code changes begin.

Related:
docs/tasks/task-template.md, docs/tasks/current-priorities.md, docs/process/development-loop.md.

## 2026-06-16 - Use a Remembered Read-Only Folder for Manual Archive Installs

Status: Superseded by the 2026-06-22 direct-filesystem decision.

Historical decision: The primary manual mod-install flow used a remembered
folder selected through Android's Storage Access Framework. DML scanned only
files directly inside that folder and retained its own managed copy when an
archive was installed.

Reason: A remembered folder provides a fast, familiar mod-manager-style list
without cluttering the dashboard or requiring users to choose one file for every
install. Read-only access protects the user's original downloads.

Historical result: The UI opened a searchable fullscreen Archive Library,
provided Refresh and Change Folder actions, and sent selected document URIs
through the archive import pipeline. The later direct-filesystem decision keeps
the same user flow but replaces URI persistence and provider I/O. The design keeps structured source and Nexus metadata available for future
enrichment. The original app-wide scope was revised by the
profile-specific decision below.

Related: REQ-MOD-001, REQ-MOD-005, REQ-UI-001,
`engine/download/ArchiveFolderScanner.kt`,
`ui/workflow/ArchiveBrowserWorkflow.kt`.

## 2026-06-16 - Archive Folder Selection Is Profile-Specific

Status: Accepted.

Decision: Each DML profile remembers its own read-only archive folder selection.

Reason: Profiles can represent different games or mod setups. A single app-wide archive folder can mix unrelated archives and makes profile isolation less predictable.

Result: Archive folder preferences are keyed by profile ID. The previous app-wide selection is migrated once to the first active profile that opens the Archive Library. Archive installation history and installed status continue to use the active profile's managed state.

Related: REQ-MOD-005, REQ-PROFILE-002,
`engine/download/ArchiveFolderPreferences.kt`,
`ui/workflow/ArchiveBrowserWorkflow.kt`.

## 2026-06-17 - Plugin Activation and Ordering Are Game-Specific

Status: Accepted.

Decision: Droid Mod Loader will not apply one universal `plugins.txt`,
`loadorder.txt`, or plugin-ordering rule to every supported Bethesda game.
Plugin activation files and the mechanism that establishes load order must be
selected by the active game definition.

Reason: Skyrim LE uses text-file ordering behavior that differs from Fallout:
New Vegas, Tale of Two Wastelands, Fallout 3, and Oblivion, where plugin file
timestamps are required to apply the intended order. A correct-looking exported
text file is not enough if the game loads a different order.

Result: The next plugin-correctness task must define game-specific output rules,
preserve profile-specific state, and apply timestamp ordering for the legacy
games before DML claims that their load order has been written successfully.

Related: REQ-PLUGIN-002, REQ-PLUGIN-003, REQ-PLUGIN-005,
ROADMAP.md, docs/tasks/current-priorities.md.

## 2026-06-17 - DML Output Mirrors the Active Profile

Status: Accepted.

Decision: DML's internal storage remains the authoritative state for every
profile. A future external `DML_output` folder will expose only the currently
active profile's generated GameNative handoff rather than storing a growing copy
of every profile.

Reason: Users need a clear bundle that matches the profile they are currently
managing. Mixing several profile exports inside one external folder makes stale
or incorrect files easier to copy into GameNative.

Result: The next plugin-correctness task must define game-specific output rules,
preserve profile-specific state, and implement timestamp ordering for currently
supported game definitions that require it. The ordering component must remain
reusable by later TTW, Fallout 3, and Oblivion definitions.

Related: REQ-PROFILE-002, REQ-GAME-001, REQ-PLUGIN-003,
ROADMAP.md, docs/tasks/backlog.md.


## 2026-06-22 - Use One Direct Filesystem Storage Backend

Status: Accepted.

Decision: Droid Mod Loader will use ordinary absolute filesystem paths for all
production shared-storage workflows. DML requires Android 11 (API 30) or newer
and requires the user-granted `MANAGE_EXTERNAL_STORAGE` special access before browsing or
modifying shared game and mod folders. The app will not retain SAF as a parallel
production backend.

Reason: DML is a file-management application whose core work includes scanning
large file trees, deploying many loose files, performing transactional backup
and replacement, and setting legacy Bethesda plugin modification timestamps. A
mixed SAF/direct-path architecture duplicates risky file logic and cannot
reliably provide all required metadata operations.

Result: Data, Game Root, and Archive Library selections are canonical direct
paths selected through a DML-owned folder browser. Deployment, plugin discovery,
overwrite scanning, archive scanning/import, repair, and timestamp ordering use
ordinary filesystem APIs. Existing URI-only selections are never guessed into
paths; affected profiles remain intact and require explicit reselection.
Production tree-URI code and the DocumentFile dependency are removed after all
call sites are migrated.

Related: REQ-STORAGE-001, REQ-STORAGE-002, REQ-GAME-001, REQ-GAME-003,
REQ-MOD-001, REQ-MOD-005, REQ-DEPLOY-002, REQ-PLUGIN-005, REQ-PROFILE-002,
`docs/tasks/direct-storage-migration.md`.

## 2026-06-29 - Detect Archive Content and Commit Installs Transactionally

Status: Accepted.

Decision: Droid Mod Loader identifies supported archive families from file
signatures instead of trusting filename extensions. Archive installation stages
and validates content before replacing an installed mod, records replacement
progress on disk, and supports cooperative cancellation.

Reason: Extensions can be missing or incorrect, and archive processing can fail
or be interrupted after copying or extraction has started. Treating a partial
folder as installed or deleting the prior mod before promotion would make
failure destructive.

Result: ZIP, 7Z, RAR4, and RAR5 signatures are recognized consistently across
installation and the Archive Library. Supported readers receive bounded safe
extraction. Existing installations remain in place until completed staging is
promoted, retained transactions are recovered at startup and profile
activation, and cancelled work removes partial output where possible. RAR5 is
recognized but remains explicitly unsupported for installation.

Related: REQ-MOD-001, REQ-MOD-002, REQ-MOD-005,
`engine/install/ArchiveFormatProbe.kt`,
`engine/install/ArchiveEntryWriter.kt`,
`engine/install/InstallReplacementTransaction.kt`,
`engine/install/InstallReplacementRecovery.kt`,
`ui/workflow/ArchiveImportExecutionWorkflow.kt`.

## 2026-06-30 - Adopt Quiet Workbench for v0.9.0

Status: Accepted.

Decision: Quiet Workbench is the locked UI and interaction system for the planned `v0.9.0-beta` overhaul. Use Home, Mods, Plugins, and Deploy as the primary workspaces; responsive bottom navigation or navigation rail; the H-to-E persistent game/profile/deployment context; and progressive disclosure for detailed or advanced actions.

Adaptive Neutral is the first-launch theme. The planned bundled themes are Capital Wasteland, Deep Ink, Warm Workshop, Adaptive Neutral, and Red Carbon. Red Carbon is labeled Developer's Choice and remains functionally identical to other themes.

Reason: A completed design review resolved the remaining navigation, responsive-layout, state, conflict, recovery, accessibility, and theming questions. One accepted system prevents conflicting implementation choices.

Result: Future `v0.9.0-beta` UI work must follow `docs/ui-ux.md`. Implementation may calibrate spacing and color tokens for accessibility and device constraints, but must preserve the locked hierarchy, workflows, state meanings, theme identities, and safety behavior.

Related: `docs/ui-ux.md`, `ROADMAP.md`, REQ-UI-003 through REQ-UI-008.
