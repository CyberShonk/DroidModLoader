# Droid Mod Loader Troubleshooting

This document lists common Droid Mod Loader problems and likely fixes.

## App Cannot See My Game Folder

Possible causes:

- Android storage permission issue
- wrong folder selected
- folder is inside app-private storage
- GameNative folder is not shared correctly
- selected folder is not readable

Try:

1. Re-select the target folder.
2. Confirm the folder is in shared storage.
3. Confirm the folder contains the expected game files.
4. Restart the app.
5. Check diagnostics.

## Deployment Target Looks Wrong

Possible causes:

- selected the wrong folder
- selected game root when Data folder was expected
- selected Data folder when game root was expected
- selected a parent folder that is too broad

Try:

1. Stop before deploying.
2. Check whether the app expects Data-folder or game-root deployment.
3. Re-select the target folder.
4. Check diagnostics.

## Mods Import But Do Not Affect the Game

Possible causes:

- mod is disabled
- plugin is disabled
- files were imported but not deployed
- deployment target is wrong
- archive layout was not detected correctly
- plugin output files were not exported or copied where needed

Try:

1. Confirm the mod is enabled.
2. Confirm the plugin is enabled if the mod has one.
3. Rebuild or rerun deployment.
4. Check plugin output.
5. Check diagnostics.

## Plugin Does Not Show Up

Possible causes:

- plugin file is not inside the archive layout the app expects
- plugin file is disabled
- plugin belongs to a disabled mod
- plugin extension is unsupported for the selected game
- plugin scan needs to be refreshed

Try:

1. Confirm the mod contains `.esm`, `.esp`, or `.esl`.
2. Confirm the mod is enabled.
3. Rescan plugins if the option exists.
4. Re-import the mod if archive layout was wrong.
5. Check diagnostics.

## Game Crashes After Deployment

Possible causes:

- missing master
- wrong plugin order
- incompatible mod
- files deployed to the wrong folder
- manual files conflict with managed files
- bad archive layout
- unsupported game setup

Try:

1. Disable the newest mod.
2. Check plugin warnings.
3. Check for missing masters.
4. Use a safe profile.
5. Redeploy.
6. Restore from backup if needed.

## App Warns About Unfinished Deployment

This means a previous deployment may not have completed cleanly.

Possible causes:

- app was closed during deployment
- device killed the app
- storage write failed
- target folder disconnected or changed

Try:

1. Do not ignore the warning.
2. Open recovery tools.
3. Review the warning.
4. Retry deployment or force full redeploy if appropriate.
5. Clear the warning only after review.

## Files Look Duplicated or Wrong

Possible causes:

- multiple mods contain the same file
- manual files already exist in the target
- previous deployment state is stale
- profile was changed
- deployment manifest is outdated

Try:

1. Check active profile.
2. Check enabled mods.
3. Review conflict information if available.
4. Run diagnostics.
5. Use full redeploy if the app recommends it.


## Reporting a Problem

A useful report should include:

- DML version
- device model
- Android version
- game
- GameNative or other setup
- selected target type
- active profile
- what you tried
- what happened
- screenshot or diagnostics text