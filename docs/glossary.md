# Droid Mod Loader Glossary

This glossary explains project terms in normal language.

## Archive

A compressed mod file, such as ZIP, 7z, or RAR.

## Data Folder

The folder where many Bethesda game assets and plugins are installed.

Common examples include:

- `Data`
- `data`

## Deployment

The act of physically writing managed mod files into the selected game target.

## Deployment Journal

A record of deployment operations.

The journal helps the app detect interrupted deployment and recover safely.

## Deployment Manifest

A record of what Droid Mod Loader believes it deployed.

This helps the app update, remove, verify, or repair managed files later.

## Deployment Plan

The list of file operations Droid Mod Loader intends to perform before it changes files.

A deploy plan may include files to add, update, skip, remove, or back up.

## Diagnostics

A readable report that explains the current app state and possible problems.

## Disabled Mod

A mod that remains installed in Droid Mod Loader but should not affect deployment.

## Enabled Mod

A mod that participates in the resolved game view and deployment plan.

## Game Root

The main game folder.

This may contain the game executable, launcher files, DLL files, and the Data folder.

## GameNative

A Windows-container setup used to run PC games on Android.

Droid Mod Loader targets shared-folder workflows that can work with GameNative.

## Managed File

A file Droid Mod Loader installed or deployed and can track.

## Manual File

A file that exists in the game target but was not placed there by Droid Mod Loader.

## Mod Priority

The order used to decide which mod wins when multiple mods provide the same file.

## Plugin

A Bethesda game data file.

Common plugin extensions:

- `.esm`
- `.esp`
- `.esl`

## Profile

A separate mod setup inside Droid Mod Loader.

Profiles should eventually isolate mod state, plugin state, target state, deployment state, diagnostics, and recovery state.

## Resolved Game View

The final view of what the game should see after enabled mods and priorities are processed.

## All-Files Access

Android special access that allows a file-management app to work with ordinary
filesystem paths in shared storage. DML uses this for production game and mod
storage workflows.

## Direct Filesystem Path

An absolute path such as `/storage/emulated/0/Games/Fallout New Vegas/Data` that
DML can validate and use with ordinary filesystem APIs.

## Storage Access Framework

Android's document-provider system for user-selected files and folders. Earlier
DML builds used tree URIs from this system; current production storage uses direct
filesystem paths instead.

## Target Folder

The folder selected as the deployment destination.

## Unmanaged File

A file in the target folder that Droid Mod Loader did not install or deploy.

Unmanaged files should not be deleted casually.

## Verification

A check that confirms deployed files are present and correct where practical.