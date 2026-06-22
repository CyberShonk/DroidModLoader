# Apply Game-Aware Plugin Activation and Ordering

## Type

Bug, Safety, Test.

## Implementation Status

The task is scoped and ready for implementation. No production-code changes
are included in this task-definition commit.

## Requirement IDs

- REQ-PLUGIN-002
- REQ-PLUGIN-003
- REQ-PLUGIN-005
- REQ-PROFILE-002

## Problem

DML currently writes one universal pair of `plugins.txt` and `loadorder.txt`
files. It does not select output behavior by game and does not apply the
file-timestamp ordering required by the currently selectable legacy games.
A successful text export can therefore claim an order that Fallout: New Vegas,
Fallout 3, or Oblivion will not actually load.

## Verified Current Game Matrix

`MainActivity.getSupportedGameIds()` currently returns these game IDs:

| Game ID | Display name | Activation output | Load-order mechanism |
|---|---|---|---|
| `skyrim_le` | Skyrim Legendary Edition | `plugins.txt` contains enabled plugins; `loadorder.txt` records the complete selected order | Text-file order |
| `oblivion` | Oblivion | `plugins.txt` contains enabled plugins | Plugin-file modification timestamps |
| `fallout_3` | Fallout 3 | `plugins.txt` contains enabled plugins | Plugin-file modification timestamps |
| `fallout_nv` | Fallout: New Vegas | `plugins.txt` contains enabled plugins | Plugin-file modification timestamps |

`GamePluginRules` also contains Fallout 4 official-plugin metadata, but Fallout
4 is not selectable. TTW has no dedicated game ID. Neither is part of this task.

For the timestamp-based games, DML may retain an internal diagnostic summary of
the selected order, but it must not describe `loadorder.txt` as the mechanism
the game uses.

## Desired Behavior

- Select plugin activation output and ordering behavior from the active game ID.
- Preserve plugin enabled state independently from order.
- Keep Skyrim LE text-file behavior separate from legacy timestamp behavior.
- Apply the full selected plugin order to plugin-file modification times for
  Oblivion, Fallout 3, and Fallout: New Vegas.
- Report which mechanism was applied and how many plugin files were ordered.
- Keep all saved plugin state and generated output isolated to the active
  profile.

## Profile Isolation

The existing `ProfileStoragePaths` and `MainActivity.createModEngineForWorkflows()`
construction must remain authoritative:

- `plugins.json`, `plugins.txt`, and any `loadorder.txt` output stay under the
  active profile state directory;
- switching profiles must not read or rewrite another profile's plugin state;
- timestamp application targets only the active profile's configured Data
  target or its profile-specific simulated deployment directory.

## Failure Behavior

- Unknown or unsupported game IDs fail before output files or timestamps change.
- If no saved plugin list exists, the workflow may perform its existing one-time
  plugin refresh. If the list remains empty, it fails without output changes.
- Timestamp ordering preflights the complete ordered plugin set before changing
  any file.
- Missing files, duplicate case-insensitive plugin names, an invalid Data target,
  or a read-only target fail before mutation where practical.
- Original timestamps are captured before mutation. A partial timestamp failure
  attempts to restore every changed timestamp and reports any rollback failure.
- A Storage Access Framework tree URI does not provide a safe standard API for
  assigning arbitrary modification times. Timestamp-based games using a tree
  URI must fail with a clear message before changing activation output.
- Existing valid plugin output files are preserved when replacement fails where
  practical.

## Automated Unit Tests

Add JVM coverage for:

1. The four selectable game IDs and their declared ordering mechanisms.
2. Skyrim LE output: enabled-only `plugins.txt`, complete-order `loadorder.txt`,
   and no timestamp request.
3. Oblivion, Fallout 3, and Fallout: New Vegas output: enabled-only
   `plugins.txt` and timestamp ordering.
4. Timestamp order uses strictly increasing modification times in selected
   priority order.
5. Disabled plugins remain disabled while still retaining selected priority.
6. Missing target plugin preflight changes no timestamps.
7. Duplicate case-insensitive plugin names are rejected before mutation.
8. A mid-application failure restores already changed timestamps.
9. Workflow logs the selected game and applied mechanism.
10. Empty-list fallback and operation-in-progress behavior remain unchanged.
11. Profile storage path tests continue to prove separate profile directories.

## Manual Runtime Verification

For each selectable game profile:

1. Create or select a dedicated test profile and safe test Data directory.
2. Add at least one enabled and one disabled non-official plugin.
3. Reorder the plugins and restart DML; confirm state and priority persist.
4. Apply plugin output/order.
5. Confirm `plugins.txt` contains only enabled plugins in selected order.
6. For Skyrim LE, confirm `loadorder.txt` contains the complete selected order
   and plugin timestamps were not rewritten.
7. For Oblivion, Fallout 3, and Fallout: New Vegas, confirm plugin timestamps are
   strictly increasing in selected order and no output is presented as the
   authoritative load-order mechanism.
8. Force a missing-plugin failure and confirm existing output files and prior
   timestamps remain unchanged where practical.
9. Test a tree-URI target for a timestamp-based game and confirm DML refuses the
   operation with a clear explanation and no partial changes.
10. Switch profiles and confirm neither saved state nor outputs changed in the
    inactive profile.
11. Launch each game in the target container and confirm the enabled set and
    effective load order match DML.

## Files Likely Affected

Verified current source areas:

- `app/src/main/java/com/shonkware/droidmodloader/engine/plugins/GamePluginRules.kt`
- a focused game-order rule type under `engine/plugins/`
- a reusable timestamp-ordering component under `engine/plugins/`
- `app/src/main/java/com/shonkware/droidmodloader/engine/data/PluginOutputRepository.kt`
- `app/src/main/java/com/shonkware/droidmodloader/engine/ModEngine.kt`
- `app/src/main/java/com/shonkware/droidmodloader/ui/workflow/PluginManagementWorkflow.kt`
- `app/src/main/java/com/shonkware/droidmodloader/MainActivity.kt`
- JVM tests under `app/src/test/java/.../engine/plugins/` and
  `app/src/test/java/.../ui/workflow/`
- plugin-related public documentation if user-visible behavior changes

## Explicit Exclusions

- The accepted 1.0 UI redesign.
- A new TTW profile or game definition.
- Fallout 3 or Oblivion setup redesign.
- `DML_output` or external handoff-folder work.
- LOOT or xEdit integration.
- INI presets or configuration recipes.
- Broad `ModEngine` service extraction.
- Plugin dependency analysis, missing-master detection, or automatic sorting.
- General game-folder validation redesign.

## Done When

- [ ] Each selectable game has an explicit tested activation/output rule.
- [ ] Skyrim LE remains text-file ordered.
- [ ] Oblivion, Fallout 3, and Fallout: New Vegas apply selected timestamp order.
- [ ] Enabled state and profile isolation remain intact.
- [ ] Failures avoid partial state and restore prior timestamps where practical.
- [ ] JVM tests cover the rule matrix, formatting, ordering, and rollback paths.
- [ ] Manual safe-folder and real-container checks are recorded.
- [ ] Documentation accurately describes current behavior.
