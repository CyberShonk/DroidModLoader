# Droid Mod Loader Troubleshooting

These steps apply to `v0.7.0-beta`. See the [changelog](../releases/changelog.md)
for changes in other versions.

## DML cannot use my game folder

Check these first:

1. Confirm DML has Android **Manage all files** access.
2. Use a shared storage path that DML can read and write directly.
3. Confirm you selected the correct game `Data` folder and Game Root for the active profile.
4. Do not select another application's protected private `Android/data` directory.
5. Select the folder again if the profile came from an older DML build that used URI storage.

Android may protect another application's private storage even when DML has
all files access.

## The deployment target is rejected

Confirm that the selected path exists, is a folder, and can be read and written
by DML. Check both Data and Game Root where the setup uses them.

Folder validation is limited in this release. Do not rely on a writable folder
alone to prove that you selected the intended game installation. Stop and select
the intended folders rather than bypassing a warning.

## A mod imports but does not appear in the game

Check:

1. The mod installation completed successfully.
2. The mod is enabled in the active profile.
3. The active profile points to the intended game folders.
4. The mod's files are deployable game content rather than documentation or other files used only by the manager.
5. A normal deployment completed after the mod state changed.
6. Any plugin supplied by the mod is enabled when required.

If the physical target was changed outside DML and its state is uncertain, inspect
Deployment Health and run **Check Recorded Managed Content**. A saved manifest
does not prove every physical file is still correct. Full redeploy is not repair
and is blocked while recovery evidence is unresolved or unavailable.

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

Common unsupported cases include:

- RAR5 installation;
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
