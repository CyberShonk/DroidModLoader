# Droid Mod Loader Testing

This document tracks testing expectations for Droid Mod Loader.

## Testing Priority

The highest priority is anything that can affect user files.

Risk order:

1. Deployment and deletion logic
2. Path normalization
3. Backup and recovery
4. Profile isolation
5. Plugin output files
6. Archive extraction
7. UI state
8. Visual polish

## Manual Test Checklist

### Fresh Install

- App installs.
- App launches.
- App icon appears correctly.
- No crash on first launch.

### Game Target

- User can select a target folder.
- Target is remembered after restart.
- Wrong-looking targets show warnings.
- Diagnostics show target information.

### Mod Import

- ZIP import works.
- 7z import works.
- RAR import works if supported.
- Installed mod appears in the mod list.
- Mod can be enabled and disabled.
- Bad archive fails with a useful message.

### Archive Folder Browser

- First use explains why an archive folder is required.
- Selecting a folder persists after app restart.
- Cancelling folder selection leaves the previous folder unchanged.
- Only top-level ZIP, 7Z, and RAR files appear.
- Unsupported files and subfolders are ignored.
- Refresh detects files added or removed outside DML.
- Lost folder permission shows a clear Change Folder recovery path.
- Search filters without losing the selected folder.
- Never-installed and previously installed archives appear before installed archives.
- Installable archives sort by file last-modified time, newest first.
- Installed archives sort by installation time, newest first.
- Installing a folder archive uses the existing archive import and installer path.
- Original archive files remain unchanged.
- Dashboard, Mods, Plugins, and Archive Library scroll positions survive opening and closing panels.
- Portrait and landscape layouts keep Refresh, Folder, and Close reachable.

### Direct Storage Migration

- Storage-access policy verifies granted and denied all-files access for
  supported Android 11+ versions.
- Direct folder selection returns canonical absolute paths and rejects missing,
  relative, unreadable, or unwritable targets as required.
- Legacy URI-only profile and deployment records preserve unrelated state, set
  explicit reselection flags, and stop writing obsolete URI fields.
- Data, Game Root, and Archive Library selections remain profile-isolated.
- Archive scanning/import, plugin discovery, overwrite scanning, deployment
  preflight, and deployment execution use direct filesystem paths.
- No production `DocumentFile`, tree-URI launcher, or SAF deployment backend
  remains after migration.
- Same-device performance comparison follows
  `benchmarks/direct-storage.md`; the recorded Android result is exploratory and
  must not be presented as a definitive public multiplier.

### Plugin Handling

- `.esm`, `.esp`, and relevant `.esl` files are discovered.
- Plugin enabled state and selected priority survive app restart.
- `plugins.txt` contains enabled plugins in selected order.
- Skyrim LE writes a complete-order `loadorder.txt` without changing plugin
  timestamps.
- Oblivion, Fallout 3, and Fallout: New Vegas apply strictly increasing plugin
  timestamps in selected order and remove stale internal `loadorder.txt` output.
- Timestamp ordering includes disabled plugins without enabling them.
- Missing or duplicate plugin files fail before timestamp mutation.
- A partial timestamp failure restores changed timestamps where practical.
- Timestamp games require a valid writable direct Data-folder path before output
  changes.
- Switching profiles does not read or rewrite another profile's plugin state or
  output files.

### Profiles

- User can create a second profile.
- User can switch profiles.
- Mod state is profile-aware.
- Plugin state is profile-aware.
- Profile state survives app restart.
- Startup restores the persisted active profile name to the main status area.
- Each profile remembers its own archive folder.
- Switching profiles does not overwrite another profile's archive folder selection.

### Deployment Plan

- Deployment plan builds before file changes.
- Plan includes expected adds, updates, skips, and removals.
- Dangerous operations are warned or blocked.
- Unmanaged files are not blindly deleted.

### Deployment

- Test deploy works against a safe test folder.
- Files copy to expected paths.
- Repeated deploy does not duplicate files.
- Disabled mods affect the next deploy plan correctly.

### Journal and Recovery

- Deployment journal starts before risky operations.
- Interrupted deploy state can be detected.
- User sees unfinished deploy warning.
- Recovery tools are reachable without developer mode.

### Diagnostics

- Diagnostics screen opens.
- Real versionName is shown.
- Active profile is shown.
- Target info is shown.
- Mod count is shown.
- Plugin count is shown.
- Warnings are understandable.

### UI

- Portrait layout works.
- Landscape layout works.
- Long mod names do not destroy the layout.
- Main screens remain readable in portrait and landscape.
- Developer Tools are hidden unless developer mode is unlocked.
- Recovery Tools are visible when needed.

## Automated Test Targets

Start tests around dangerous logic first.

| Target                       | Reason                                  |
|------------------------------|-----------------------------------------|
| PathUtils                    | Prevent unsafe paths                    |
| DeployFileClassifier         | Prevent wrong deployment classification |
| DeploymentPlanBuilder        | Prevent bad copy/delete plans           |
| DeploymentPreflightChecker   | Prevent unsafe deployment               |
| DeploymentJournalRepository  | Prevent broken recovery                 |
| InstalledModRecordRepository | Prevent corrupted mod state             |
| PluginDiscovery              | Prevent plugin order bugs               |
| InstallerLayoutAnalyzer      | Prevent bad install layout detection    |
| ModDisplayNameNormalizer     | Prevent ugly or confusing mod names     |
| ResolvedDataGraphBuilder     | Prevent wrong conflict winners          |

## Current JVM Unit Tests

Activity extraction coverage includes state projection, startup ordering, profile/session coordination, selected-folder persistence, dashboard refresh application, operation reporting, diagnostics, and thread execution.

These tests run without an Android device. The exact test-file list is derived
from source rather than duplicated as a hand-maintained table here.

Current coverage includes:

- path normalization and deployment-scope classification;
- profile storage paths and legacy-state migration;
- plugin discovery, game-aware activation output, transactional plugin-output
  replacement, and rollback-safe legacy timestamp ordering;
- archive-folder scanning, archive metadata, downloaded-archive records, and
  Nexus URL parsing;
- installer option selection and display-name normalization; and
- archive, deployment, mod, plugin, pending-installer, and profile workflow
  coordination.

Inspect the exact current test files with:

```bash
find app/src/test -type f -name '*Test.kt' | sort
```

Run the JVM unit-test suite with:

```bash
./gradlew testDebugUnitTest
```
## Release Rule

A release should not be uploaded until:

- the app builds
- basic manual tests pass
- risky deployment behavior is tested
- changelog is updated
- known issues are written down