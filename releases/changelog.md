# Droid Mod Loader Changelog

This file records release changes visible to users and known limitations.

## v0.8.0-beta - Unreleased

### Changed

- Operation feedback now stays in the app instead of using Android Toast popups. The dashboard, Archive Library, and installer show the latest operation status.
- Physical deployment preflight distinguishes Data and Game Root, validates a bounded game marker set, rejects targets for the wrong game or targets with their roles reversed, and checks that both selected paths belong to the same installation.
- Target diagnostics report the selected target role, canonical path, validation result, and structured findings.
- A clean Fallout New Vegas target remains valid for an initial Tale of Two Wastelands deployment.
- Archive imports identify source format and reader support before creating a new managed archive copy.
- Archive diagnostics distinguish RAR4 and RAR5 while retaining compatibility with existing stored RAR metadata.
- ZIP and 7z reader failures provide clearer corruption, bounded memory, and cleanup diagnostics.

### Fixed

- Rotating the device preserves open panels, installer choices, and operation state instead of restarting the app session.
- Deployable mod payload files are no longer excluded just because their names or extensions resemble documentation. Runtime files such as UIO `supported.txt` now deploy to `Data`, while installer files and unrelated development artifacts remain excluded from physical deployment.
- Physical deployment stops before writes when a selected target belongs to another supported game, uses the wrong role, is obviously too broad, or does not match the selected Data/Game Root installation pair.
- Deployment state is kept separate for each selected Data and Game Root target, so switching installations does not reuse another target's saved deployment state.
- Returning to a previously used target reuses that target's own saved deployment state.
- Unfinished deployment state is no longer silently replaced when another deployment starts.
- Unsupported RAR5 archives and unrecognized signatures no longer create a new managed archive copy or installer session before rejection.
- Controlled archive probe and reader failures no longer produce diagnostics that resemble a crash for expected unsupported input.

### Known Issues

- A white flash can appear when opening dialogs or rotating the device.
- Repeated archive installation attempts can retain duplicate archive copies, including after cancelling installer options.
- Guidance for folder selection and the complete target marker matrix for each distribution are still limited.
- RAR5 is detected but is not supported for installation.
- RAR archives protected by a password, encrypted RAR archives, and multipart RAR archives are not supported.
- Some uncommon 7z compression or encryption variants may remain unsupported.
- Returning to a previously used deployment target does not automatically verify every physical managed file against changes made outside DML.

## v0.7.0-beta - 2026-06-29

See the [release notes](notes/v0.7.0-beta.md) for installation and upgrade guidance.

### Added

- Added Tale of Two Wastelands as a selectable profile with legacy
  plugin ordering based on timestamps.
- Added archive signature detection for ZIP, 7Z, RAR4, and RAR5 archives.
- Added bounded extraction checks for unsafe paths, duplicate destinations,
  case collisions, excessive entries, oversized files, excessive total output,
  long paths, and insufficient storage headroom.
- Added persistent installed mod replacement transactions and automatic recovery
  at startup and profile activation.
- Added cooperative cancellation for archive copying, extraction, preparation,
  and installation.

### Changed

- Replaced production Storage Access Framework paths with one direct filesystem
  backend for Game Root, `Data`, Archive Library, import, scanning, deployment,
  overwrite inspection, and plugin timestamp ordering.
- Archive Library scanning and stored archive metadata now use detected content
  rather than filename extensions.
- Mod replacement now stages complete content and preserves the existing
  installed mod until promotion succeeds.
- Archive import keeps an archive that was already registered when later
  installation is cancelled, while removing copied files that were never
  registered.
- The displayed app version now comes from the APK build configuration instead
  of a hardcoded UI string.

### Fixed

- Prevented failed, cancelled, or partial extraction from becoming a successful
  installed mod state.
- Preserved the previous installed mod when replacement staging or promotion
  fails.
- Recovered retained replacement transactions after interruption.
- Removed partial copied and extracted files during cooperative cancellation
  where cleanup is possible.
- Reported RAR5, encrypted, multipart, corrupt, and unsupported archive failures
  more precisely.
- Recognized supported archives with missing or incorrect filename extensions.

### Known Issues

- RAR5 is detected but is not supported for installation.
- RAR archives protected by a password, encrypted RAR archives, and multipart
  RAR archives are not supported.
- Some uncommon 7Z compression or encryption variants may remain unsupported.
- Android still blocks access to other applications' protected
  `Android/data` directories.
- Game Root and `Data` folder validation still needs stronger guidance
  specific to each game.
- Droid Mod Loader remains beta software; back up important game folders before
  testing deployment.

### Upgrade Notes

- Install this version over `v0.6.0-beta`; do not uninstall the existing app
  first if you want to retain state managed by the app.
- Android 11 and newer require all files access for DML's shared storage
  workflows.
- Existing profiles and unrelated managed state are retained by the migration.
  Legacy URI based Game Root, `Data`, or Archive Library selections require
  explicit reselection as direct filesystem paths.

## v0.6.0-beta - 2026-06-16

### Added

- Added a remembered archive folder browser for top level ZIP, 7Z, and RAR files.
- Added archive search, manual refresh, and folder switching.
- Added Installed and Previously installed archive states that follow the active profile.

### Changed

- Install Mod now asks for an archive folder on first use and opens the remembered folder directly afterward.
- Archive files selected from the folder browser now use the existing archive import and installer pipeline.
- Archives available to install are shown before currently installed archives, with newest files first in each group.
- Main screen and full screen list scroll positions are retained during the current app session.

### Known Issues

- Some 7Z and RAR archives may still fail depending on their compression method or archive format.
- Recovery tools and unfinished deployment warnings still need more polish.
- Droid Mod Loader remains beta software; back up important game folders before testing deployment.

### Upgrade Notes

- Existing users can install this version over `v0.5.5-beta`.
- The first time you tap Install Mod, select the folder where you keep downloaded mod archives.
- DML reads the original archive from that folder and keeps its own managed copy when the mod is installed.
- Existing profiles and installed mod records should remain available after upgrading.

## v0.5.5-beta

Early beta foundations for archive metadata, diagnostics, and launcher branding.

### Known Issues

- Recovery and unfinished deployment handling were still incomplete.
- Back up important game folders before testing deployment features.
