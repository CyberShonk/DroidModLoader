# Droid Mod Loader Vision

Droid Mod Loader is an Android Bethesda mod manager for shared-storage and Windows-container setups such as GameNative.

The goal is not to be a simple archive extractor or file copier. Droid Mod Loader should understand the user's modded game state, explain it clearly, deploy it safely, and help recover when something goes wrong.

## Core Direction

Droid Mod Loader should become an MO2-style resolver, profile manager, and transactional physical deployer for Android Bethesda modding.

Mod Organizer 2 uses a virtual file system.

Droid Mod Loader uses indexed physical deployment.

The core model is:

1. Installed mods
2. File indexes
3. Resolved game view
4. Deploy plan
5. Physical deploy
6. Verification
7. Diagnostics
8. Recovery

## Primary User Problem

Android Bethesda modding currently involves too much manual file copying, guessing, and cleanup.

Users need a tool that can:

- keep mods organized
- show what each mod contains
- explain file conflicts
- manage profiles
- prepare plugin order files
- deploy to the correct game folder
- detect dangerous or confusing states
- recover from interrupted or broken deployments

## Main Test Environment

GameNative is the main target environment.

Droid Mod Loader should not modify GameNative directly or depend on private GameNative internals. The app should work through shared folders, exported files, and user-selected game targets.

## Target Games

Early focus:

- Skyrim Legendary Edition
- Fallout: New Vegas
- Fallout 3
- Oblivion

Later targets:

- Skyrim Special Edition
- Fallout 4

## Product Rules

### 1. Safety comes first

Droid Mod Loader must not casually overwrite, delete, or move user files without a plan, warning, or recovery path.

### 2. The app must understand the game state

The app should know the difference between:

- base game files
- DLC files
- manually installed files
- managed mod files
- generated output files
- overwrite files
- deployed files

### 3. Deployment must be explainable

Before deployment, the app should be able to explain:

- what will be added
- what will be updated
- what will be skipped
- what will overwrite something else
- what may be dangerous
- what can be recovered

### 4. Profiles must be real

Profiles should not be cosmetic. They should isolate mod state, plugin state, target identity, deployment manifests, diagnostics, and future guide/collection data.

### 5. Diagnostics must be useful to normal users and testers

The app should produce reports that explain problems in plain language while still including enough technical detail for debugging.

### 6. Advanced tools should not clutter the main UI

Normal users should see the basic path first:

1. Pick game
2. Import mods
3. Sort/enable plugins
4. Review warnings
5. Deploy
6. Diagnose problems if needed

Advanced tools should be available, but not forced onto new users.

## Interaction Direction

Droid Mod Loader's planned interface system is Quiet Workbench: calm, moderately dense, conflict-transparent, responsive, and designed for touch use without copying a desktop mod manager layout.

The normal workflow stays centered on Home, Mods, Plugins, and Deploy. Game, profile, and deployment state remain visible through a responsive persistent-context pattern. Routine actions use progressive disclosure, while warnings, recovery, diagnostics, and destructive actions remain explicit.

Adaptive Neutral is the first-launch theme. The full planned theme set is Capital Wasteland, Deep Ink, Warm Workshop, Adaptive Neutral, and Red Carbon. Presentation never changes safety behavior or state meaning.

## What Droid Mod Loader Is Not

Droid Mod Loader is not:

- a Nexus-only downloader
- a GameNative replacement
- a Bethesda asset distributor
- a Windows mod manager port
- a simple unzipper
- a tool that blindly copies files into Data

## Long-Term Goal

The long-term goal is to make Android Bethesda modding safer, clearer, more repeatable, and less dependent on manual file management.

Droid Mod Loader should eventually support:

- strong mod installation
- advanced FOMOD/BAIN handling
- plugin diagnostics
- LOOT/xEdit bridge features
- resolved file conflict views
- deployment verification
- support bundles
- guide-based modding
- collections
- GameNative-focused setup helpers
