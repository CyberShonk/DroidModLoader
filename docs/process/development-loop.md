# Droid Mod Loader Development Loop

This document defines how code changes move from idea to committed work.

The goal is to keep Droid Mod Loader organized, testable, and safe.

## Core Rule

Do not start coding from a vague idea.

Every non-trivial change should start as a scoped task.

A task should define:

- requirement IDs
- problem
- desired behavior
- files likely affected
- test steps
- done criteria

## Standard Change Flow

Use this flow for most changes:

1. Pick one task.
2. Confirm the related requirement IDs.
3. Create or update a GitHub Issue or local task note.
4. Make a focused code change.
5. Build the app.
6. Run manual test steps.
7. Add or update automated tests if practical.
8. Update docs if behavior changed.
9. Update changelog if user-facing.
10. Commit with a clear message.
11. Push.
12. Check GitHub.

## Branch Rule

For small solo changes, working on `main` is acceptable if the change is focused.

For larger or risky changes, use a branch.

Use branches for:

- deployment logic
- recovery logic
- profile persistence
- resolver changes
- plugin output changes
- large UI redesigns
- collaborator work

Suggested branch names:

```text
fix/dashboard-text-overflow
task/hide-developer-tools
task/deploy-journal-warning
docs/update-release-checklist
refactor/split-mod-engine