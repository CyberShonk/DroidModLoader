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

### Plugin Handling

- .esm files are discovered.
- .esp files are discovered.
- Plugin enabled state is saved.
- Disabled plugins are excluded from exported output.
- Plugin order survives app restart.

### Profiles

- User can create a second profile.
- User can switch profiles.
- Mod state is profile-aware.
- Plugin state is profile-aware.
- Profile state survives app restart.

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
- Dashboard cards do not clip important text.
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

These tests run without an Android device.

| Test File                      | Purpose                                                                |
|--------------------------------|------------------------------------------------------------------------|
| `PathUtilsTest`                | Verifies path normalization and ignored metadata paths                 |
| `DeployFileClassifierTest`     | Verifies deploy scope classification and unsafe path blocking          |
| `ModDisplayNameNormalizerTest` | Verifies archive/mod name cleanup                                      |
| `PluginDiscoveryTest`          | Verifies plugin discovery, plugin type detection, and 1-based priority |

Run with:
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