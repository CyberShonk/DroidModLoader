# Droid Mod Loader User Guide

Droid Mod Loader manages mod archives, installed mods, profiles, plugin state,
and physical deployment for supported Bethesda games on Android.

This guide describes the development version. Some features below are not in the
current public release. For `v0.7.0-beta`, use the
[released guide](https://github.com/CyberShonk/DroidModLoader/blob/v0.7.0-beta/docs/user-guide.md).
See the [changelog](../releases/changelog.md) for version changes.

## Before you begin

DML requires Android 11 or newer for its shared storage workflow. Grant **Allow
access to manage all files** when Android prompts for DML's special storage
access.

Back up any game folder you plan to use for testing. DML is beta software and
physical deployment changes files in the selected game target.

## 1. Create or select a profile

Profiles keep mod, plugin, Archive Library, and deployment state separate. Select
the profile for the game setup you intend to manage before importing or deploying
mods.

Supported early game profiles include Skyrim Legendary Edition, Fallout: New
Vegas, Fallout 3, Oblivion, and Tale of Two Wastelands through Fallout: New
Vegas.

## 2. Select game folders

Tap **Choose Game Folder** during setup or when creating a profile. In settings,
use **Change Game Folder**. Select the game's main folder, not its `Data` subfolder
or a general storage root.
DML checks known game files and finds the Data folder. The folder choices belong
to the active profile. In settings, choose **Save Settings** to save the selection.

A validated selection enables physical deployment to those game folders. Check the
paths before deploying. Known filenames and folder access do not prove that every
game distribution or compatibility environment will work.

Tale of Two Wastelands uses a Fallout: New Vegas target.

Do not select unrelated storage roots as deployment targets.

## 3. Choose an Archive Library

Tap **Install Mod** to open the Archive Library. On first use, choose the folder
where you keep downloaded mod archives.

The selected archive folder is remembered per profile. DML scans files directly
from that folder and does not move or delete the original downloads when you
switch folders.

The Archive Library supports search, refresh, folder switching, and installed
archive history for the active profile. New imports use temporary archive copies,
not a second permanent archive library.

Each archive shows its filename, type and file size. Installed and previously
installed archives are marked for the current profile. Known version and source
game information appears when available. If the installed mod has a different
name, the row shows that name too.

Use **Close** to leave the library when idle. During an import, **Cancel Import**
cancels that import instead.

Choose **Install** to unpack an archive into DML's mod storage. Browsing the list
does not unpack archives. After an install attempt, **Archive Details** can show
facts gathered during that attempt without reading the archive again. These
facts last for the current session and clear when the profile or archive folder
changes. They do not prove that the source is unchanged or the mod will work
in the game.

To remove copies retained by older versions, choose **Remove Old DML Copies…**
and confirm **Remove Copies**. DML checks records from all stored profiles
and compares each eligible copy with its original before deleting it. This can
take time for large archives. Originals, installer sessions, and import history
are preserved. Missing or changed originals, selected libraries, and uncertain
files are left alone. Unreadable or unresolved records can block cleanup; see
the log for details.

Archive format is identified from file content rather than filename extension.
ZIP and supported 7z/RAR variants enter the normal installer flow. Detection
does not mean every variant can be installed; see the limitations below.

## 4. Install and manage mods

Installed mods are kept in profile storage managed by DML rather than copied
directly into the game during import.

After choosing **Install** on an archive, edit **Mod name** if needed. This names
the mod in your list and its folder inside DML. It does not change where its files
go when deployed to the game.

Choose **Install** to continue. DML shows installer options or file choices when
needed. **Choose Files** opens the archive's folder tree. Check the files and
folders to include. Open a folder's **⋮** menu and choose **Use as Data Folder**
to install its contents without the enclosing folders. **Reset to Archive Root** returns to the whole archive and clears exclusions.
The tree shows the proposed structure inside the mod's folder. For an unrecognized
layout, review the tree before choosing **Install Anyway**.

If the mod name already exists, choose:

- **Add / Update Files** to keep existing files and overwrite matching files with the new ones.
- **Replace** to remove the existing files and install only the new selection.
- **Cancel** to return to the installer without changing the existing mod.

Enabled mods participate in the resolved game view. Disabled mods remain
installed but do not contribute files to deployment.

Priority determines the winner when multiple enabled mods provide the same
normalized path.

## 5. Manage plugins

DML discovers Bethesda plugin files such as `.esm`, `.esp`, and `.esl` where the
selected game supports them.

Plugin activation and ordering are specific to each game:

- Skyrim Legendary Edition writes enabled plugins to `plugins.txt` and preserves the complete selected order in `loadorder.txt`.
- Oblivion, Fallout 3, Fallout: New Vegas, and TTW write enabled plugins to `plugins.txt` and apply the selected order through plugin modification timestamps.

Timestamp ordering requires a valid writable Data folder. DML preflights the
complete plugin set before changing plugin output or timestamps.

## 6. Deploy

Deployment physically writes the resolved managed mod view to the selected game
target.

DML builds a deployment plan before writing. The plan can contain additions,
updates, removals of previously managed files, backups, and blocked operations.

Deployment manifests are stored separately for each target. A saved manifest
does not verify changes made to physical files outside DML.

DML does not intentionally delete unrelated unmanaged files. Review warnings and
planned removals before deployment.

## 7. Recovery and diagnostics

DML records deployment journal state so interrupted work can be detected.
Recovery tools and diagnostics are safety features intended for users. They are
not tools reserved for developers.

Use diagnostics when the selected target, plugin state, archive support, or
deployment result is unclear.

**Notifications** show problems such as lost folder access or unfinished installs
and deployments. Use **Choose Game Folder** or **View Details** when offered.
**Mark Reviewed** only records that you saw a warning. **Hide for Now**, when
available, hides it for the current session. Neither action repairs files or
allows a blocked deployment. These are notices inside DML, not Android system
notifications.

**Recent Activity** shows the latest five recorded attempts across profiles. Open
**Details** to see the profile, game, saved folder choices, times and result.
DML keeps at most 50 attempts within 256 KiB. Waiting for installer choices is not
success. An unfinished attempt from an earlier session has an unknown outcome;
DML does not retry it automatically. The list tells you if history could not be
loaded or saved. Past results do not check your current files or repair changes.

In **Report & Diagnostics**, **Deployment Summary** combines known folder access,
changes waiting to be deployed, unfinished work and previous file checks. Choose
**Refresh Deployment Summary** to update it, then **Show Deployment Details** for
the recorded folders and results. Refreshing does not read every file or rebuild
saved file checks. Missing or outdated file lists leave pending changes unknown.
Test folders do not mean mods were copied to the game. The summary is not a score
or a promise that the game will work.

**Check Game Setup** checks expected game filenames and folder access,
then records app and Android versions, API level and all files access permission.
It checks captured folder choices, not whether they were saved or used for deployment.
Each folder check examines at most 4,096 entries without scanning subfolders.
Incomplete or inaccessible checks remain unavailable, not missing markers. TTW uses
Fallout: New Vegas markers; its own master is optional before initial deployment.
File names do not prove file contents, game version or runtime compatibility.
Launcher and Wine details are not inspected. Results are historical observations
in the log and shared reports; run it again after changing folders or permissions.

**Show Storage Info** reports DML's storage locations and available space in
historical diagnostic logs, which are also included in shared reports. Operation
requirements stay unknown here; deployment preflight sizes its own copy budget.
The managed archive inventory inspects at most 2,000 entries through eight directory
levels, deduplicates known file identities and never follows symbolic links or
removes files. It reports observed logical bytes, not allocated disk size, archive
validity or deletion eligibility. User source libraries and import history are not
inventory. Partial or unavailable results stay explicit; run it again after changes.

**Share Logs** creates a text report and opens Android's share chooser. It includes
a short summary, technical details and existing logs. Recorded details cover the
selected profile and game, folder choices, deployment and recovery, recent attempts,
and retained storage, game setup and archive observations. Missing or outdated
observations stay explicit. Sharing does not run another check or read archives.

Tap the **ⓘ** beside **Share Logs** for a privacy explanation. Reports include full
folder paths, which may reveal personal names, and logs can include older profiles
and folders. Only share with someone you trust. Nothing is uploaded automatically.
Tap elsewhere, tap the icon again or scroll to dismiss the explanation.

Choose **Check Recorded Files** to compare recorded Data, Game Root and installed
mod files with DML's saved checks. It reports missing, changed or unreadable files
without changing them. It only checks files with saved records, not the whole game.
**Cancel File Check** stops the check and leaves partial results in the log.
Missing or invalid saved checks are reported, not rebuilt.

After checking, choose **Refresh Deployment Summary** to include the results.
Run the check again after changing files, folders or profiles. Finishing an action
or marking a warning reviewed does not prove files still match. DML blocks its own
conflicting work during a check, but other programs can still change files. A match
only describes what was read during that check.

Evidence reads are limited to 8 MiB per metadata file, 50,000 files per target,
and 1,000 recorded installs with 50,000 indexed files in total. Exceeding a limit
leaves the affected evidence unavailable rather than silently truncating a check.
Files not named in recorded evidence are not scanned.

**Recovery Details** identifies recorded deployment targets and pending install
transactions, including missing or unavailable backup evidence. Transaction
inspection stops at 1,000 records; exceeding this limit leaves recovery unknown.
**Mark Reviewed** is available only for an unfinished journal whose recorded profile,
game and targets match the current scope. It annotates the warning, not the files.
Reviewing or dismissing a notice does not resolve recovery or unlock deployment.

DML preserves unfinished, failed and reviewed journals and blocks another deploy
or full redeploy from replacing them. Deployment retry and rollback are not
currently supported because the journal does not prove a safe recovery action.
Do not delete recovery evidence to bypass this guard. Existing automatic install
recovery still runs at startup and profile activation; unresolved install
transactions also block deployment.

## Current limitations

- DML remains beta software.
- RAR5 support is limited to single volume, unencrypted archives using standard methods `m0` through `m5` (Store through Best), including solid archives. Limits are a 32 MiB declared dictionary, 4 MiB of metadata input, 8,200 metadata read calls, 4,096 entries and 64 path segments. The first limit reached applies; long paths can exhaust the metadata byte budget before the entry limit. Output limits and free space checks also apply. A bounded stored quick open index is ignored; canonical archive headers remain authoritative. Links, other service records, unknown sizes and other compression variants are refused.
- RAR archives protected by a password, encrypted RAR archives, and multipart RAR archives are not supported.
- Some uncommon 7z compression or encryption variants may be unsupported.
- Android can block access to another application's protected private storage.
- Game folder validation and guidance are limited. Check that Data and Game Root belong to the intended installation.
- Returning to a previously used target does not check for changes made outside DML. Use **Check Recorded Files** for recorded managed files and **Recovery Details** for unfinished work. Neither is a general repair tool.
