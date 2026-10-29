# Droid Mod Loader Troubleshooting

These steps describe the development version. Some features below are not in the
current public release. For `v0.7.0-beta`, use the
[released troubleshooting guide](https://github.com/CyberShonk/DroidModLoader/blob/v0.7.0-beta/docs/troubleshooting.md).
See the [changelog](../releases/changelog.md) for version changes.

## DML cannot use my game folder

Check these first:

1. Confirm DML has Android **Manage all files** access.
2. Use a shared storage path that DML can read and write directly.
3. Use **Choose Game Folder** during setup, or **Change Game Folder** in settings, to select the game's main folder. DML finds its Data folder. In settings, save with **Save Settings**.
4. Do not select another application's protected private `Android/data` directory.
5. Select the folder again if the profile came from an older DML build that used URI storage.

Android may protect another application's private storage even when DML has
all files access.

## The deployment target is rejected

Confirm that the selected path exists, is a folder, and can be read and written
by DML. Check both Data and Game Root where the setup uses them.

DML checks known game filenames and folder access, not every game distribution
or runtime. A writable folder alone does not prove it is the right installation.
Read the reported reason and select the intended game's main folder rather than
bypassing a warning.

## A mod imports but does not appear in the game

Check:

1. The mod installation completed successfully.
2. The mod is enabled in the active profile.
3. The active profile points to the intended game folders.
4. The mod's files are deployable game content rather than documentation or other files used only by the manager.
5. A normal deployment completed after the mod state changed.
6. Any plugin supplied by the mod is enabled when required.

If another program changed the game folders, review **Deployment Summary** and
run **Check Recorded Files**. A saved file list does not prove the files still
match. Full redeploy is not repair and is blocked while earlier work still needs
recovery or its records cannot be read.

## A plugin is missing or has the wrong order

Confirm the enabled mod actually contains the plugin and that the plugin is
supported by the selected game.

For Skyrim Legendary Edition, inspect `plugins.txt` and `loadorder.txt` behavior.
For Oblivion, Fallout 3, Fallout: New Vegas, and TTW, ordering is applied through
plugin modification timestamps and requires a writable Data folder.

Review DML's plugin warnings before changing files manually.

## DML reports an unfinished deployment

An unfinished journal means the previous deployment may not have completed
cleanly.

1. Open **Recovery Details** and inspect the recorded profile, game and targets.
2. Confirm they match the installation you intended to change.
3. Preserve journals, manifests, backups and pending install transactions.
4. Do not manually delete deployment state just to remove the warning.

Review or dismissal is not repair. Unfinished, failed and reviewed journals block
another deployment, including full redeploy. Corrupt or unreadable journals also
block replacement. Deployment retry and rollback are not currently supported;
backup presence alone does not prove that either action is safe.

## An archive is rejected

DML identifies archive format from its signature, not just the filename.
If installation fails, read the message and log for the reason. An unrecognized
folder layout is different from an unsupported or damaged archive. Installation
needs temporary space and can stop at storage or resource limits. Recognizing
ZIP, 7z or RAR in the library does not prove the archive can be installed.

Common unsupported cases include:

- RAR5 methods outside `m0` through `m5`, newer compression versions, links and service records other than a bounded stored quick open index;
- RAR5 archives that exceed the dictionary, metadata, entry or path depth limits;
- archives protected by a password or encrypted archives;
- multipart RAR archives; and
- uncommon unsupported 7z variants.

A large archive is not automatically invalid. If an install crashes or exits,
report the actual format and any diagnostics rather than assuming file size was
the cause.

## The game crashes after deployment

A successful file copy does not guarantee that a mod list is valid for the game.
Check for missing masters, incompatible mods, plugin order problems, wrong target
selection, and unmanaged/manual files that conflict with the managed setup.

Test changes with backups and a disposable profile or a profile known to work
where practical.

## Reporting a problem

Include:

- DML version;
- device model and Android version;
- game and compatibility environment;
- active profile;
- selected target type and path class without exposing private information;
- archive format if the problem involves installation;
- exact reproduction steps;
- what you expected and what happened; and
- relevant screenshots, DML diagnostics, or logs with sensitive data removed.
