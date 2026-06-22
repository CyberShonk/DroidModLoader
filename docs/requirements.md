# Droid Mod Loader Requirements

This document defines the core requirements for Droid Mod Loader.

The goal is to keep feature work grounded. New features should either satisfy an existing requirement, refine an existing requirement, or require a deliberate update to this document.

## Requirement Status Labels

Use these labels:

- Planned
- In Progress
- Working
- Needs Cleanup
- Blocked
- Deferred

## 1. Game Target Requirements

### REQ-GAME-001: Select Game Target

Status: In Progress

Droid Mod Loader must allow the user to select a game target folder.

The target may be:

- a Data folder
- a game root folder
- a shared-storage folder intended for GameNative

Done when:

- the user can select a target
- the app remembers the target
- the app can display the selected target clearly
- the app can warn if the target looks wrong

### REQ-GAME-002: Identify Target Type

Status: Planned

Droid Mod Loader must distinguish between Data-folder deployment and game-root deployment.

Done when:

- Data-folder targets are labeled clearly
- game-root targets are labeled clearly
- dangerous root-level deployment is treated as advanced
- diagnostics include the selected target type

### REQ-GAME-003: Validate Target

Status: Planned

Droid Mod Loader must validate the selected target before deployment.

Validation should check:

- folder exists
- folder is readable
- folder appears to match the selected game
- folder is writable or likely writable
- deployment path is not obviously unsafe

Done when:

- invalid targets block deployment
- suspicious targets show a warning
- diagnostics explain the problem in normal language

## 2. Mod Installation Requirements

### REQ-MOD-001: Import Mod Archive

Status: Working

Droid Mod Loader must import supported mod archives.

Supported formats:

- ZIP
- 7z
- RAR where supported

Done when:

- the archive can be selected
- the app extracts it into a managed mod folder
- the installed mod appears in the mod list
- failure gives a useful message

### REQ-MOD-002: Keep Mods Isolated

Status: Working

Each installed mod must have its own managed folder.

Done when:

- one mod does not overwrite another mod during import
- mod files can be indexed separately
- disabling one mod does not delete another mod's files

### REQ-MOD-003: Enable and Disable Mods

Status: Working

The user must be able to enable or disable installed mods.

Done when:

- disabled mods are excluded from the resolved game view
- disabled mods are excluded from new deployment plans
- disabled mods are still preserved in the app

### REQ-MOD-004: Mod Priority

Status: Working

The app must support mod priority order.

Done when:

- mod priority is visible
- priority can be changed
- priority affects conflict winners
- priority is normalized consistently

### REQ-MOD-005: Browse a Remembered Archive Folder

Status: In Progress

Droid Mod Loader must let the user select one direct-access Android folder that
contains downloaded mod archives, remember that path, and present supported
archives in a searchable install list.

Done when:

- first use explains why the folder is needed
- each profile remembers its own selected folder across app restarts
- ZIP, 7Z, and RAR files directly inside the folder are listed
- the list can be refreshed without continuously monitoring storage
- installed and previously installed archives are identified for the active profile
- selecting Install routes through the existing archive import pipeline
- switching profiles loads that profile's archive folder without changing another profile's selection
- changing folders does not delete or modify the original archive files
- lost folder access produces a clear recovery message
- the selected folder is stored as a canonical direct filesystem path

## 3. File Index and Resolver Requirements

### REQ-RESOLVE-001: Index Mod Files

Status: In Progress

Droid Mod Loader must index the files contained by each installed mod.

Done when:

- the app can list files owned by each mod
- file paths are normalized
- indexes can be rebuilt
- invalid paths are handled safely

### REQ-RESOLVE-002: Build Resolved Game View

Status: Planned

The app must build a resolved game view from installed mods, enabled state, and priority order.

Done when:

- every deployable path has a selected winner
- overwritten files are recorded
- identical duplicate files can be identified
- disabled mods are excluded

### REQ-RESOLVE-003: Explain File Conflicts

Status: Planned

The app must explain file conflicts in a way normal users can understand.

Done when:

- the user can see the winning mod
- the user can see overwritten mods
- the user can see why a file won
- conflict details are available before deployment

## 4. Plugin Requirements

### REQ-PLUGIN-001: Discover Plugins

Status: Working

Droid Mod Loader must discover Bethesda plugin files.

Plugin extensions:

- .esm
- .esp
- .esl where relevant

Done when:

- plugins appear in the plugin list
- plugin source mod is known when possible
- missing plugin files are handled safely

### REQ-PLUGIN-002: Enable and Disable Plugins

Status: Working

The user must be able to enable and disable plugins.

Done when:

- enabled state is saved
- disabled plugins are excluded from the active plugin set but may remain in a
  complete-order file when the selected game or tool expects one
- plugin state survives app restart

### REQ-PLUGIN-003: Export Plugin Files

Status: Working

The app must export plugin order files used by supported games and containers.

Output files may include:

- plugins.txt
- loadorder.txt

Done when:

- files are generated in the selected game's expected format
- the active plugin set is represented correctly
- complete-order files include or exclude inactive plugins according to the
  selected game's rules
- output location is clear to the user

### REQ-PLUGIN-004: Detect Basic Plugin Problems

Status: Planned

The app must detect basic plugin problems.

Examples:

- missing masters
- disabled source mod
- missing plugin file
- duplicate plugin name
- official plugin ordering issue
- plugin and BSA mismatch

Done when:

- warnings appear in diagnostics
- warnings are understandable
- warnings include likely fix steps

### REQ-PLUGIN-005: Apply Game-Specific Plugin Order

Status: In Progress

Droid Mod Loader must apply plugin order using the mechanism required by the
selected game instead of assuming that every supported game reads the same text
files.

Done when:

- Skyrim LE output preserves the correct text-file order
- every supported game definition that declares timestamp-based ordering applies
  the selected order to plugin modification times
- the timestamp-ordering component can be reused by future game definitions
  without duplicating game-independent ordering logic
- enabled and disabled state remains correct after ordering
- ordering is isolated by profile
- failures leave the previous valid state intact where practical
- diagnostics explain which ordering mechanism was applied

## 5. Deployment Requirements

### REQ-DEPLOY-001: Build Deploy Plan

Status: In Progress

The app must build a deployment plan before changing files.

The plan should include:

- files to add
- files to update
- files to remove
- files to skip
- files requiring backup
- dangerous operations
- estimated bytes where practical

Done when:

- deployment actions are planned before execution
- the plan can be inspected by diagnostics
- unsafe operations can be blocked

### REQ-DEPLOY-002: Physical Deployment

Status: In Progress

The app must physically deploy resolved files to the selected game target.

Done when:

- files are copied to the correct target
- existing managed files can be updated
- removed managed files can be cleaned up
- unmanaged files are not blindly deleted

### REQ-DEPLOY-003: Deployment Verification

Status: Planned

The app must verify deployment results where practical.

Done when:

- copied files can be checked
- missing deployed files are detected
- failed writes are reported
- diagnostics explain verification failures

## 6. Journal and Recovery Requirements

### REQ-RECOVERY-001: Deployment Journal

Status: In Progress

The app must keep a journal of deployment operations.

Done when:

- deployment start is recorded
- each important file operation is recorded
- deployment completion is recorded
- interrupted deployments can be detected later

### REQ-RECOVERY-002: Unfinished Deploy Warning

Status: Planned

The app must warn the user if the previous deployment was interrupted.

Done when:

- stale or unfinished journal state is detected
- the warning is visible
- the warning can be reviewed
- recovery options are offered

### REQ-RECOVERY-003: Recovery Tools

Status: Planned

The app must provide recovery tools for failed or suspicious deployments.

Recovery tools may include:

- retry deploy
- force full redeploy
- restore backups
- clear stale warning after review
- export support report

Done when:

- recovery tools are reachable without developer mode
- dangerous recovery actions explain what they do
- user files are not casually deleted

## 7. Profile Requirements

### REQ-PROFILE-001: Create and Switch Profiles

Status: Working

The app must support multiple profiles.

Done when:

- profiles can be created
- profiles can be switched
- current profile is visible
- profile state survives app restart

### REQ-PROFILE-002: Isolate Profile State

Status: In Progress

Profiles must isolate mod state, plugin state, deployment state, and diagnostics where practical.

Done when:

- changing one profile does not unexpectedly change another
- plugin state is profile-aware
- deployment manifest is profile-aware
- diagnostics identify the active profile

## 8. Diagnostics Requirements

### REQ-DIAG-001: Human-Readable Diagnostics

Status: In Progress

The app must provide diagnostics that explain problems clearly.

Done when:

- diagnostics use the real app version
- diagnostics include target info
- diagnostics include profile info
- diagnostics include mod/plugin counts
- diagnostics include warnings and likely causes

### REQ-DIAG-002: Support Report Export

Status: Planned

The app must export a support report that can be shared for debugging.

Done when:

- report includes app version
- report includes device/app state
- report includes selected target
- report includes current profile
- report includes deployment/recovery state
- report avoids leaking unnecessary personal data

## 9. UI Requirements

### REQ-UI-001: Main Path Is Simple

Status: In Progress

The main UI must guide normal users through the basic path.

Basic path:

1. Select game
2. Import mods
3. Enable/disable mods
4. Manage plugins
5. Review warnings
6. Deploy
7. Diagnose problems

Done when:

- the home screen does not show everything at once
- advanced actions are visually quieter
- dangerous actions require clear labels
- beginner wording is understandable

### REQ-UI-002: Developer Tools Hidden

Status: Planned

Developer tools must be hidden unless developer mode is unlocked.

Done when:

- normal users do not see developer tools
- developer mode can reveal them
- recovery tools remain available outside developer mode

## 10. Branding and Asset Requirements

### REQ-ASSET-001: Handmade Asset Sources Are Preserved

Status: In Progress

Source assets for icons, launcher graphics, and promotional art must be preserved in the repo or an organized asset folder.

Done when:

- source files are not scattered
- generated Android assets are separate from editable source assets
- asset notes explain what each asset is for

### REQ-ASSET-002: Android Launcher Icons Are Generated Correctly

Status: In Progress

The app must use correctly generated Android launcher icons.

Done when:

- adaptive icon assets exist
- legacy icon assets exist if needed
- monochrome icon is considered for newer Android launchers
- manifest references the launcher icon correctly
- generated files are committed intentionally

## 11. Release Requirements

### REQ-RELEASE-001: Versioning

Status: In Progress

Every release must have a clear version name.

Done when:

- APK versionName matches the release notes
- diagnostics show the real versionName
- uploaded APK filename includes the version

### REQ-RELEASE-002: Changelog

Status: Planned

Every release must update the changelog.

Done when:

- added items are listed
- changed items are listed
- fixed items are listed
- known issues are listed
- upgrade notes are included when needed

### REQ-RELEASE-003: Release Checklist

Status: Planned

Every release must pass a checklist before upload.

Done when:

- build succeeds
- release APK is signed
- release APK is not committed accidentally
- basic manual tests pass
- docs are updated
- known issues are updated

## 12. Storage Requirements

### REQ-STORAGE-001: Use Direct Shared-Storage Access

Status: In Progress

Droid Mod Loader must use one direct filesystem backend for production game and
mod storage workflows.

Done when:

- Android 11 / API 30 is the minimum supported platform
- every supported Android version requires and verifies all-files access before shared-storage work
- Data, Game Root, and Archive Library selections are canonical absolute paths
- deployment, scanning, archive import, conflict inspection, and timestamp ordering use direct filesystem APIs
- production SAF tree-URI and DocumentFile paths are removed
- diagnostics clearly report permission or path failures

### REQ-STORAGE-002: Migrate Legacy Storage Selections Safely

Status: In Progress

Droid Mod Loader must preserve existing profiles while moving from URI-based
folder selections to direct filesystem paths.

Done when:

- existing profiles, mods, plugins, and unrelated settings are preserved
- URI-only Data and Game Root selections are marked for explicit reselection
- URI-only Archive Library selections are marked for explicit reselection per profile
- DML does not fabricate paths by parsing `content://` identifiers
- real deployment is blocked until a required Data path is valid
- migration state clears only after a valid direct path is saved
