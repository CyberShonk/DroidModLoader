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
The project needs consistent visual identity and should not depend on AI-generated official branding.

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

## 2026-06-06 - Project Work Must Use Tasks

Status:
Accepted.

Decision:
New non-trivial work should start from a scoped task, GitHub Issue, or current-priorities entry.

Reason:
Scattered notes and vague coding prompts make the app harder to maintain.

Result:
Future work should define the problem, desired behavior, requirement IDs, files likely affected, test steps, and done criteria before code changes begin.

Related:
docs/tasks/task-template.md, docs/tasks/current-priorities.md, docs/process/development-loop.md.