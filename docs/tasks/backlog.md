# Droid Mod Loader Backlog

This file holds future work that is not ready to code yet.

Use GitHub Issues for active tasks. Use this backlog for rough ideas, deferred work, and things that need more thought.

## Rules

- Do not code directly from vague backlog items.
- Convert backlog items into a scoped task before coding.
- Every task should link to one or more requirement IDs when possible.
- Dangerous file behavior needs test steps before implementation.
- UI work must include portrait and landscape checks.

## Immediate Cleanup Candidates

These are known candidates from the current roadmap and docs.

### UI

- Hide Developer Tools behind developer mode.
- Keep Recovery Tools visible outside developer mode.
- Improve beginner wording on main actions.
- Keep advanced actions visually quieter.
- Confirm portrait and landscape layouts.

### Diagnostics

- Show real `versionName` in diagnostics.
- Include active profile in diagnostics.
- Include target identity in diagnostics.
- Include unfinished deploy state in diagnostics.
- Add exportable support report later.

### Deployment Safety

- Add unfinished deployment warning.
- Add reviewed state for stale recovery warnings.
- Add force full redeploy option.
- Improve deployment preflight checks.
- Add tiny write test where practical.
- Confirm unmanaged files are not blindly deleted.

### Resolved Data Graph

- Expand `ResolvedDataGraph`.
- Track file winners.
- Track overwritten providers.
- Track identical duplicates.
- Add conflict summary.
- Add reasons for conflict winners.

### Plugin Intelligence

- Detect missing masters.
- Detect duplicate plugin names.
- Detect disabled source mod with enabled plugin.
- Detect plugin/BSA mismatch.
- Improve plugin output diagnostics.

### Release

- Confirm release APKs are ignored by Git.
- Update changelog per release.
- Use release checklist before uploading.
- Keep Nexus/GitHub release notes aligned.