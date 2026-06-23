# Droid Mod Loader Architecture

Droid Mod Loader is an Android/Kotlin app using Jetpack Compose for the UI and a custom engine layer for mod management.

## Related Architecture Docs

- [Source Map](architecture/source-map.md)

## Source Root

app/src/main/java/com/shonkware/droidmodloader/

## Main Areas

| Area            | Purpose                                                                       |
|-----------------|-------------------------------------------------------------------------------|
| MainActivity.kt | Android lifecycle, platform UI, Compose attachment, and dependency wiring       |
| ui/             | Compose screens, dashboard components, panels, and theme                      |
| engine/         | Core logic for mods, plugins, deployment, profiles, diagnostics, and recovery |

## UI Layer

The UI layer should show state, guide the user, and trigger actions.

It should not directly own dangerous file logic.

Important UI files:

| File                              | Purpose                                                        |
|-----------------------------------|----------------------------------------------------------------|
| MainScreen.kt                     | Main screen state/action models and dashboard composition      |
| DashboardStatusCards.kt           | Header, status, quick-start, and primary dashboard action cards |
| ModsComponents.kt                 | Dashboard mod card, compact rows, and mod content summaries    |
| PluginComponents.kt               | Dashboard plugin card and plugin rows                          |
| DeploymentSettingsComponents.kt   | Deployment settings card                                       |
| ReportComponents.kt               | Report card                                                    |
| DeveloperToolsComponents.kt       | Developer-only dashboard tools                                 |
| Recovery.kt                       | Deployment-recovery warning and recovery-tool cards            |
| SetupProfileComponents.kt         | First-run setup and profile-management UI                      |
| FullscreenPanelComponents.kt      | Fullscreen Mods and Plugins dialogs                            |
| ArchiveLibraryComponents.kt       | Archive-folder setup and fullscreen Archive Library UI         |
| FullscreenPanel.kt                | Shared fullscreen panel layout                                 |
| SecondScreenController.kt         | Second-screen handling                                         |
| SecondScreenPluginPresentation.kt | Second-screen plugin UI                                        |
| theme/DmlTheme.kt                 | Theme styling                                                  |

### Activity Composition and Workflow Ownership

`MainActivity.kt` is the Android composition root. It registers Activity Result
launchers, attaches Compose content, forwards lifecycle events, creates the
second-screen controller, launches Android settings/share/dialog/toast UI, and
wires focused dependencies together.

Reusable activity behavior is separated as follows:

- `MainActivityUiState.kt` owns activity-scoped Compose state and projects it to
  `DashboardUiState`.
- `DashboardActionBindings.kt` binds dashboard callbacks to focused workflow
  controllers.
- `AppStartupCoordinator.kt` preserves startup sequencing.
- `ProfileSessionCoordinator.kt` applies profile/startup/game configuration to
  UI state.
- `SelectedFolderConfigurationCoordinator.kt` persists selected Data and Game
  Root paths and triggers the required follow-up work.
- `DashboardRefreshCoordinator.kt` applies dashboard refresh results.
- `OperationReporter.kt` and `SessionLogWriter.kt` own operation status and
  file-backed session logging.
- focused workflow coordinators own diagnostics, recovery, direct-folder
  selection, content inspection, plugin synchronization, second-screen state,
  and thread execution.
- `ProfileScopedEngineFactory.kt` and `ProfileRepositoryFactory.kt` own
  profile-scoped construction details.

The activity must not regain domain file operations or reusable workflow logic.
Top-level dependency wiring is intentionally retained there.

## Engine Layer

The engine layer owns the mod manager behavior.

It should handle:

- mod import
- archive analysis
- file indexing
- plugin discovery
- profile state
- resolved file state
- deployment planning
- physical deployment
- deployment journal
- recovery state
- diagnostics

## Important Engine Areas

| Package                             | Purpose                                                |
|-------------------------------------|--------------------------------------------------------|
| engine/install/                     | Archive reading, install layout analysis, FOMOD basics |
| engine/index/                       | Mod file indexing and file summaries                   |
| engine/plugins/                     | Plugin discovery, game rules, output building, and timestamp ordering |
| engine/profile/                     | Profile persistence                                    |
| engine/resolve/                     | Resolved data graph foundation                         |
| engine/deploy/                      | Deployment target and deployment execution             |
| engine/deploy/plan/                 | Deployment planning and preflight checks               |
| engine/deploy/journal/              | Deployment journal and recovery state                  |
| engine/diagnostics or related logic | Human-readable app diagnostics                         |
| engine/util/                        | Path and logging helpers                               |

## Plugin Activation and Ordering Flow

Plugin state remains profile-scoped. `ProfileScopedEngineFactory.kt` constructs
each engine with the active profile's `plugins.json`, `plugins.txt`, and
`loadorder.txt` paths.

Responsibilities are separated as follows:

- `GamePluginLoadOrderRules.kt` declares whether a selectable game uses text
  files or plugin modification timestamps for order.
- `PluginOutputBuilder.kt` formats enabled-only activation output and any
  complete-order text output required by the game.
- `PluginTimestampOrderer.kt` preflights and applies strictly increasing
  timestamps, with rollback after partial failure.
- `PluginOutputRepository.kt` transactionally replaces profile-scoped output
  files and removes stale `loadorder.txt` output for timestamp-based games.
- `PluginConfigurationApplier.kt` coordinates timestamp application and output
  replacement, restoring timestamps if output replacement fails.
- `ModEngine.kt` resolves the active game's writable direct or simulated Data
  target before output changes.
- `PluginManagementWorkflow.kt` preserves the existing one-time refresh fallback
  and reports the selected game and applied ordering mechanism.

Skyrim Legendary Edition uses enabled-only `plugins.txt` plus complete-order
`loadorder.txt`. Oblivion, Fallout 3, and Fallout: New Vegas use enabled-only
`plugins.txt` plus full selected-order plugin timestamps.

## Intended Data Flow

1. User selects archive.
2. Archive is analyzed.
3. Mod is installed into managed storage.
4. Mod files are indexed.
5. Enabled mods and priorities create a resolved game view.
6. Resolved game view creates a deploy plan.
7. Preflight checks target safety.
8. Deployment journal starts.
9. Files physically deploy.
10. Deployment is verified where practical.
11. Diagnostics report the result.
12. Journal marks completion.

## Deployment Model

Droid Mod Loader does not use a virtual file system.

It uses indexed physical deployment.

That means the app must track:

- installed mods
- file indexes
- conflict winners
- overwritten files
- deployment target
- previous deployed files
- backups
- verification results
- recovery state

## Architecture Rules

1. UI should not perform dangerous file operations directly.
2. Deployment must be planned before files are changed.
3. Recovery state must be written before risky operations.
4. Paths must be normalized before deployment decisions.
5. Profiles must not accidentally leak state into each other.
6. Developer tools should stay hidden from normal users.
7. Recovery tools should remain reachable by normal users.
8. Major decisions should be recorded in docs/decisions.md.

## Architecture Maintenance Rule

When code structure changes, update architecture docs in the same task.

Update architecture docs when:

- a new major engine package is added
- responsibilities move between files
- deployment flow changes
- profile state ownership changes
- plugin handling changes
- recovery/journal behavior changes
- UI navigation model changes
- a large file is split into smaller services

Do not let architecture docs become fictional. If the code changes, update the docs.

## Known Cleanup Targets

- ModEngine.kt is large and should eventually be split into smaller services.
- Deployment planning, preflight, journal, and recovery should remain separate concepts.
- Release APKs should not be committed.

## Direct Storage and Archive Library

DML uses one direct-filesystem backend for production shared-storage work. On
Android 11 and newer, `AllFilesAccessManager.kt` checks the special all-files
access state before DML opens its own folder browser.

Responsibilities are separated as follows:

- `engine/storage/DirectPathValidator.kt` validates existing readable/writable
  directories and canonicalizes selected paths.
- `engine/storage/DirectFolderBrowser.kt` provides filesystem navigation without
  Android document-provider identifiers.
- `engine/download/ArchiveFolderPreferences.kt` persists the selected canonical
  Archive Library path by profile ID and marks legacy URI-only selections for
  explicit reselection.
- `engine/download/ArchiveFolderScanner.kt` performs a read-only, top-level direct
  scan for ZIP, 7Z, and RAR files.
- `ui/workflow/ArchiveBrowserWorkflow.kt` combines scanned files with profile
  archive history, sorting, status, refresh, and install routing.
- `ArchiveImportExecutionWorkflow` copies the selected direct source file into
  DML-managed profile storage before analysis and installation.

Game Root, Data, and Archive Library selections use the same direct folder
browser and persist canonical absolute paths. Deployment, plugin discovery,
overwrite scanning, baseline scanning, archive import, and legacy plugin
timestamp ordering use ordinary filesystem APIs. Legacy tree-URI values are read
only as migration signals; DML does not guess a filesystem path from them.

The selected folder, archive history, and installed-status calculation remain
profile-specific. Switching profiles loads that profile's folder selection
without changing another profile's selection.
