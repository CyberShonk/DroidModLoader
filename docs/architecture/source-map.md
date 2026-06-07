# Droid Mod Loader Source Map

This document maps important source files and folders to their current responsibilities.

Use this when planning changes so work does not get shoved into the wrong file.

## Source Root

`app/src/main/java/com/shonkware/droidmodloader/`

## Main Entry Point

| File              | Responsibility                                                                                                              |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `MainActivity.kt` | Android entry point and high-level coordinator. This file is allowed to wire systems together, but should shrink over time. |

## UI

| File/Folder                            | Responsibility                                                     |
|----------------------------------------|--------------------------------------------------------------------|
| `ui/MainScreen.kt`                     | Main app screen and primary user path                              |
| `ui/DashboardComponents.kt`            | Dashboard cards, buttons, status panels, and reusable dashboard UI |
| `ui/FullscreenPanel.kt`                | Fullscreen panel container                                         |
| `ui/SecondScreenController.kt`         | Second-screen support                                              |
| `ui/SecondScreenPluginPresentation.kt` | Second-screen plugin display                                       |
| `ui/theme/DmlTheme.kt`                 | DML visual theme                                                   |

UI rules:

- UI should display state and trigger actions.
- UI should not directly own deployment, deletion, backup, or recovery logic.
- UI should remain readable across normal phone and handheld layouts.
- Developer tools should stay hidden unless developer mode is unlocked.
- Recovery tools should stay reachable for normal users.

## Engine

| Folder                   | Responsibility                                                  |
|--------------------------|-----------------------------------------------------------------|
| `engine/install/`        | Archive analysis, installer layout detection, FOMOD/BAIN basics |
| `engine/index/`          | Mod file indexing, file previews, file tree summaries           |
| `engine/plugins/`        | Plugin discovery, plugin state, game plugin rules               |
| `engine/profile/`        | Profile persistence                                             |
| `engine/resolve/`        | Resolved data graph and conflict winner state                   |
| `engine/deploy/`         | Deployment target identity and deployment execution             |
| `engine/deploy/plan/`    | Deployment plan building and preflight checks                   |
| `engine/deploy/journal/` | Deployment journal and interrupted deploy state                 |
| `engine/baseline/`       | Baseline target file tracking                                   |
| `engine/overwrite/`      | Existing/manual/overwrite file scanning                         |
| `engine/download/`       | Archive/download metadata and source tracking                   |
| `engine/rules/`          | File classification and deployment rules                        |
| `engine/util/`           | Path helpers, logging helpers, shared utilities                 |
| `engine/model/`          | Shared data models                                              |

## High-Risk Files

Changes to these areas require careful test steps:

| Area                     | Risk                                          |
|--------------------------|-----------------------------------------------|
| Deployment plan building | Can copy, skip, update, or remove wrong files |
| Deployment execution     | Can write to the wrong target                 |
| Journal/recovery         | Can fail to recover interrupted operations    |
| Path utilities           | Can allow unsafe paths                        |
| Profile persistence      | Can leak state across profiles                |
| Plugin export            | Can produce broken load order files           |
| Resolver/conflict logic  | Can choose the wrong winning file             |

## Refactor Targets

These files/areas should be reduced over time:

| Target               | Reason                                                                     |
|----------------------|----------------------------------------------------------------------------|
| `MainActivity.kt`    | Should not keep accumulating app coordination logic                        |
| `ModEngine.kt`       | Should be split into focused services over time                            |
| Large UI composables | Should be broken into reusable components when they become hard to read    |
| Deployment code      | Planning, preflight, journal, execution, and recovery must remain separate |

## Change Placement Rules

Use these rules when deciding where code belongs:

| Change                    | Preferred Location                                     |
|---------------------------|--------------------------------------------------------|
| Dashboard layout          | `ui/DashboardComponents.kt` or `ui/MainScreen.kt`      |
| Theme color/shape changes | `ui/theme/DmlTheme.kt`                                 |
| Mod import behavior       | `engine/install/`                                      |
| File indexing             | `engine/index/`                                        |
| Plugin discovery/output   | `engine/plugins/`                                      |
| Profile state             | `engine/profile/`                                      |
| Conflict winner logic     | `engine/resolve/`                                      |
| Deployment plan           | `engine/deploy/plan/`                                  |
| Deployment execution      | `engine/deploy/`                                       |
| Recovery/journal          | `engine/deploy/journal/`                               |
| Path safety               | `engine/util/` or rules-specific helpers               |
| Release process           | `docs/release-checklist.md`                            |
| Project direction         | `docs/vision.md`, `ROADMAP.md`, or `docs/decisions.md` |

## Rule

Before modifying a large file, ask:

1. Is this the right file for the change?
2. Is this making the file harder to understand?
3. Should this be a new helper/service instead?
4. What test proves this did not break risky behavior?