# Droid Mod Loader User Guide

This guide explains how to use Droid Mod Loader.

Droid Mod Loader is an Android Bethesda mod manager for shared-storage and GameNative setups.

The app is not just a file copier. It is meant to manage installed mods, plugin state, deployment, diagnostics, and recovery.

## Basic Use Path

The normal user path is:

1. Select a game target.
2. Import mods.
3. Enable or disable mods.
4. Manage plugin state.
5. Review warnings.
6. Deploy.
7. Use diagnostics if something looks wrong.

## 1. Select a Game Target

The game target is the folder Droid Mod Loader deploys files into.

Depending on setup, this may be:

- a `Data` folder
- a game root folder
- a shared GameNative folder

The app should warn if the selected target looks wrong.

Do not select random storage folders as deploy targets.

## 2. Install Mods

Tap **Install Mod** to open the Archive Library.

The first time, DML asks you to choose the folder where you keep downloaded mod
archives. DML remembers this folder for the active profile and scans files directly
inside it for:

- ZIP
- 7Z
- RAR where supported

Each profile can remember a different archive folder. Switching profiles loads
that profile's folder without changing the folders saved for other profiles. DML
only reads the selected folder. Choosing a different folder does not delete or move
the original archives.

The Archive Library provides:

- search by archive or mod name
- manual Refresh
- a Folder button for choosing a different archive folder
- Installed and Previously installed history for the active profile
- installable archives first, with currently installed archives at the bottom

When you install an archive, DML copies it into managed app storage and sends it
through the normal archive analysis and installer flow. The original downloaded
file remains in the selected folder.

If Android removes access to the folder, use **Folder** to select it again.

## 3. Enable or Disable Mods

Enabled mods participate in deployment.

Disabled mods stay installed in Droid Mod Loader, but should not affect the resolved game view or future deployment plan.

## 4. Manage Plugins

Droid Mod Loader can discover Bethesda plugin files such as:

- `.esm`
- `.esp`
- `.esl` where relevant

Plugin activation and order are applied according to the active profile's game:

- `plugins.txt` contains enabled plugins in the selected order.
- Skyrim Legendary Edition also receives `loadorder.txt`, which keeps the
  complete selected order. Disabled plugins may remain there so their saved
  positions are not lost.
- Oblivion, Fallout 3, and Fallout: New Vegas use plugin-file modification
  timestamps for load order. DML applies the complete selected order to those
  timestamps and does not present `loadorder.txt` as authoritative.

Timestamp ordering requires a writable local Data folder path. Android Storage
Access Framework tree URIs do not provide a safe standard operation for setting
arbitrary file modification times, so DML refuses that operation before changing
plugin output.

## 5. Review Warnings

Warnings are important.

Warnings may indicate:

- wrong target folder
- missing plugin files
- missing masters
- unsafe deployment paths
- interrupted deployment
- unmanaged files
- profile mismatch
- unsupported archive layout

Do not ignore deployment or recovery warnings.

## 6. Deploy

Deployment means Droid Mod Loader physically writes files to the selected game target.

Before deployment, the app should build a plan.

The plan may include:

- files to add
- files to update
- files to skip
- files to remove if previously managed
- files needing backup
- dangerous operations to block or warn about

## 7. Diagnostics

Use diagnostics when something looks wrong.

Diagnostics should help explain:

- app version
- active profile
- selected target
- mod count
- plugin count
- deployment state
- recovery warnings
- likely problems

## 8. Recovery

Recovery tools are for normal users, not just developers.

Use recovery tools when:

- deployment was interrupted
- files did not deploy correctly
- the app warns about unfinished deployment
- the target state looks suspicious
- a full redeploy is needed

Dangerous recovery actions should explain what they do before running.

## Safe Testing Advice

When testing new Droid Mod Loader builds:

1. Back up important game folders.
2. Test deployment against a safe test folder first.
3. Read warnings before deploying.
4. Keep one clean profile for testing.
5. Do not use important saves for first-time experiments.

## Current Known Limitations

This guide will change as the app changes.

Known areas still being improved:

- deployment recovery polish
- conflict detail views
- advanced installer handling
- plugin intelligence
- diagnostics export
- beginner wording
- general UI polish