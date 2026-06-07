# Droid Mod Loader Architecture

Droid Mod Loader is an Android/Kotlin app using Jetpack Compose for the UI and a custom engine layer for mod management.

## Related Architecture Docs

- [Source Map](architecture/source-map.md)

## Source Root

app/src/main/java/com/shonkware/droidmodloader/

## Main Areas

| Area            | Purpose                                                                       |
|-----------------|-------------------------------------------------------------------------------|
| MainActivity.kt | Main Android entry point and high-level coordinator                           |
| ui/             | Compose screens, dashboard components, panels, and theme                      |
| engine/         | Core logic for mods, plugins, deployment, profiles, diagnostics, and recovery |

## UI Layer

The UI layer should show state, guide the user, and trigger actions.

It should not directly own dangerous file logic.

Important UI files:

| File                              | Purpose                                 |
|-----------------------------------|-----------------------------------------|
| MainScreen.kt                     | Main app screen                         |
| DashboardComponents.kt            | Dashboard cards, buttons, and status UI |
| FullscreenPanel.kt                | Fullscreen panel layout                 |
| SecondScreenController.kt         | Second-screen handling                  |
| SecondScreenPluginPresentation.kt | Second-screen plugin UI                 |
| theme/DmlTheme.kt                 | Theme styling                           |

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
| engine/plugins/                     | Plugin discovery and plugin rules                      |
| engine/profile/                     | Profile persistence                                    |
| engine/resolve/                     | Resolved data graph foundation                         |
| engine/deploy/                      | Deployment target and deployment execution             |
| engine/deploy/plan/                 | Deployment planning and preflight checks               |
| engine/deploy/journal/              | Deployment journal and recovery state                  |
| engine/diagnostics or related logic | Human-readable app diagnostics                         |
| engine/util/                        | Path and logging helpers                               |

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

- MainActivity.kt is too large and should be reduced over time.
- ModEngine.kt is large and should eventually be split into smaller services.
- Deployment planning, preflight, journal, and recovery should remain separate concepts.
- Release APKs should not be committed.

