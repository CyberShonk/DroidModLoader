# Droid Mod Loader Backlog

This file holds future work that is not ready to code yet.

Use GitHub Issues for active tasks. Use this backlog for rough ideas, deferred work, and things that need more thought.

## Rules

- Do not code directly from vague backlog items.
- Convert backlog items into a scoped task before coding.
- Every task should link to one or more requirement IDs when possible.
- Dangerous file behavior needs test steps before implementation.
- UI work must include portrait and landscape checks.

## Game Setup and Profile Output

- Add a dedicated Tale of Two Wastelands game/profile definition that reuses the
  Fallout: New Vegas engine family without pretending TTW is a generic FNV
  profile.
- Make Game Folder selection the normal setup path and keep a separate Data
  Folder picker as an advanced override.
- Validate Game Root and Data using the expected executable plus a small,
  distribution-aware set of official masters and archives.
- Reuse normalized directory snapshots where practical without making the Data
  baseline responsible for installation validation.
- Create a visible, profile-aware `DML_output` status and recovery flow.
- Keep DML's internal profile state authoritative and expose only the active
  profile's current handoff through `DML_output`.
- Improve GameNative handoff instructions without depending on private
  GameNative internals.

## Game Configuration Presets

- Define versioned, reviewed Bethesda INI presets from independently verified
  community research.
- Add Pocket Mojave and Pocket Wastelands presets after the output workflow is
  implemented.
- Add a cached `ConfigRecipeDetector` service that consumes indexed plugin and
  file-path data instead of rescanning archives or arbitrary readmes.
- Keep game INI generation separate from mod-owned script-extender plugin INIs.
- Detect custom-INI prerequisites such as JIP LN NVSE or Command Extender before
  generating files that depend on them.
- Record preset sources, changed keys, prerequisites, and deterministic override
  order.

## Archive Invalidation

- Research and implement game-specific archive invalidation separately from INI
  preset generation.
- Cover Fallout 3, Fallout: New Vegas, TTW, Oblivion, Skyrim LE, and SkyBSA-aware
  Oblivion behavior.
- Verify the result with an actual loose-file override test before claiming that
  invalidation is active.

## Game-Specific Validation and Diagnostics

- Add Oblivion-specific Game Root, Data, plugin-order, xOBSE, and SkyBSA checks.
- Expand support reports with active profile, target identity, plugin ordering,
  output status, archive details, and unfinished deployment state.
- Consider a shared actionable-finding model before building a full notification
  center.

## Immediate Cleanup Candidates

These are known candidates from the current roadmap and docs.

### UI

- Hide Developer Tools behind developer mode.
- Keep Recovery Tools visible outside developer mode.
- Improve beginner wording on main actions.
- Keep advanced actions visually quieter.
- Confirm portrait and landscape layouts.
- Add regression coverage for fullscreen list-state retention across dialogs, folder pickers, and fullscreen panel transitions where practical.

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