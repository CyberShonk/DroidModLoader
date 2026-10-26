# Droid Mod Loader User Guide

Droid Mod Loader manages mod archives, installed mods, profiles, plugin state,
and physical deployment for supported Bethesda games on Android.

This guide covers `v0.7.0-beta`. See the [changelog](../releases/changelog.md)
for changes in other versions.

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

A physical setup uses a game `Data` folder and, where required, a Game Root.
DML stores direct filesystem paths for the active profile.

Select the folders for the intended game installation. Folder validation is
limited in this release, so check the paths before deploying.

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

To remove copies retained by older versions, choose **Clean Old DML Copies…**
and confirm **Verify and Clean**. DML checks records from all stored profiles
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

**Recent operations** on the dashboard shows the latest five retained attempts
across profiles. **Details** identifies the captured profile, game, configured
target intent, times, reason and any previous attempt. History retains at most
50 attempts within 256 KiB; it is not current health or recovery authority.
Waiting for installer choices is not success. Unfinished attempts from a prior
session have an unknown outcome and are never replayed. History save failures
do not change the operation result, and unavailable history stays explicit.

**Report & Diagnostics** shows one Deployment Health summary shared with its
details, relevant App Attention notices and support reports. **Refresh Deployment
Evidence** collects current target readiness, recorded pending changes and journal
facts without checking file bytes or rebuilding expected hashes. Missing or stale
source indexes leave pending changes unknown. Details identify each target role,
collection time and reason codes; simulated targets never imply a physical deploy.
Readiness, pending changes and integrity remain separate facts, not a health score.

**Inspect Environment** checks the selected game's setup markers and folder access,
then records app and Android versions, API level and all files access permission.
It checks captured folder choices, not whether they were saved or used for deployment.
Each folder check examines at most 4,096 entries without scanning subfolders.
Incomplete or inaccessible checks remain unavailable, not missing markers. TTW uses
Fallout: New Vegas markers; its own master is optional before initial deployment.
File names do not prove file contents, game version or runtime compatibility.
Launcher and Wine details are not inspected. Results are historical observations
in the log and shared reports; inspect again after changing folders or permissions.

**Inspect Storage** reports write locations, ownership and available capacity in
historical diagnostic logs, which are also included in shared reports. Operation
requirements stay unknown here; deployment preflight sizes its own copy budget.
The managed archive inventory inspects at most 2,000 entries through eight directory
levels, deduplicates known file identities and never follows symbolic links or
removes files. It reports observed logical bytes, not allocated disk size, archive
validity or deletion eligibility. User source libraries and import history are not
inventory. Partial or unavailable facts stay explicit; inspect again after changes.

In **Report & Diagnostics**, choose **Check Recorded Managed Content** to compare
recorded Data, Game Root and installed mod files with their saved SHA-256 hashes.
**Cancel Integrity Check** stops cooperatively and leaves partial results in the
report. Missing or invalid evidence is reported as unavailable, never rebuilt by
this check. Results identify the captured profile, targets, input digest and time;
they are historical observations, not live health, repair or compatibility proof.
After checking, use **Refresh Deployment Evidence** to include the observation in
Deployment Health. Admitted mutations invalidate cached health/check evidence;
profile, target or journal changes cannot revive an old match. Completed or reviewed
operations do not establish integrity. Rerun checks after changes. DML mutations are
excluded during a check, but external
programs can still change files; this is not an atomic filesystem snapshot.

Evidence reads are limited to 8 MiB per metadata file, 50,000 files per target,
and 1,000 recorded installs with 50,000 indexed files in total. Exceeding a limit
leaves the affected evidence unavailable rather than silently truncating a check.
Files not named in recorded evidence are not scanned.

**Recovery Details** identifies recorded deployment targets and pending install
transactions, including missing or unavailable backup evidence. Transaction
inspection stops at 1,000 records; exceeding this limit leaves recovery unknown.
**Mark Warning
Reviewed** is available only for an unfinished journal whose recorded profile,
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
- RAR5 support is limited to single volume, unencrypted archives using standard methods `m0` through `m5` (Store through Best), including solid archives. Limits are a 32 MiB declared dictionary, 4 MiB of metadata input, 8,192 metadata read calls, 4,096 entries and 64 path segments. Output limits and free space checks also apply. A bounded stored quick open index is ignored; canonical archive headers remain authoritative. Links, other service records, unknown sizes and other compression variants are refused.
- RAR archives protected by a password, encrypted RAR archives, and multipart RAR archives are not supported.
- Some uncommon 7z compression or encryption variants may be unsupported.
- Android can block access to another application's protected private storage.
- Game folder validation and guidance are limited. Check that Data and Game Root belong to the intended installation.
- Returning to a previously used target does not automatically prove that every physical managed file is unchanged outside DML; use recovery tools when target state is uncertain.
