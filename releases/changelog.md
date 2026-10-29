# Droid Mod Loader Changelog

This file records release changes visible to users and known limitations.

## v0.8.0-beta - Unreleased

### Added

- feat: Share Logs includes profile and folder context, recorded deployment and recovery details, recent attempts, and retained diagnostic observations without running new checks. An information icon explains that reports include full folder paths and historical logs.
- feat: Name a mod before installing and choose its files and Data folder in the install dialog. Unrecognized layouts ask before continuing. When the name already exists, add or update files, replace the mod, or cancel. The chosen name applies to DML's mod folder, not game deployment paths.
- feat: Show archive details from the last install attempt without reading the archive again.
- feat: Extract single volume, unencrypted RAR5 archives using standard methods `m0` through `m5`, including solid archives, with bounded dictionary and header processing. Entries use existing path, output and storage checks; unsupported variants are refused.
- feat: Check Game Setup reports expected game filenames, folder access, app and Android versions, API level and storage permission. Missing and unreadable results stay distinct; results in the log do not prove game version or whether the game will run. <!-- commits: 8fa5eafafe6f41b22550269dbc7549a55272c88e -->
- feat: Show Storage Info lists DML's storage locations, backing volumes, available space and a limited archive listing. It does not follow symbolic links or remove files. Incomplete results and unknown space requirements stay clear. <!-- commits: 1bcf2c5f6d7f2531ddef3d5ebcdba2e00ceb220b -->
- feat: The dashboard shows recent archive installs, deployments, full redeployments and plugin output with captured context and details. History retains up to 50 attempts within 256 KiB. Unfinished attempts have an unknown outcome and are never replayed; unavailable history and save failures remain separate from operation results.
- feat: Deployment Summary combines known folder access, pending mod changes, unfinished work and previous file checks. Missing or outdated results stay clear; successful actions and reviewing warnings do not prove files are unchanged. Refreshing does not rebuild saved file checks. <!-- commits: 2975d4fa6e4fa54debc4b5ff6d11789c58919e99 -->
- feat: Report & Diagnostics offers a cancellable SHA-256 check of recorded Data, Game Root and installed mod files without changing them, reporting missing, changed, unreadable or unavailable evidence. Results identify captured inputs and remain historical observations, not live health or compatibility proof; checks never rebuild missing expected hashes. <!-- commits: de2614ec50a76c773008bba4e38c8d6ce75d04c0 -->
- feat: Mods show warnings for missing installation folders or no recognized game data. <!-- commits: 29119e8019f06089ea276fcae8ecaadaaf29ce44 -->
- feat: Notifications inside DML show unfinished deployments and installations and ask you to choose the game folder again when access is lost. <!-- commits: cc38f79ff572825db526f7d41d2c5ba07edc1c4e, 5a6c10c62eafdae4ba2e7d6729f5d5f96a7ca107 -->
- feat: Archive Library offers explicit cleanup of old archive copies, deleting only verified redundant copies while preserving originals and import history. <!-- commits: b8bc5851e9556e3052b337b8b4622a2bd4d214b1 -->
- build: DML Dev installs separately so development builds do not replace the release app. <!-- commits: 4584028f5396c3175799de700f16885b3408fa81 -->
- build: APK build receipts record the source commit, local changes, version, APK hash and signing details. <!-- commits: 6425c1c7a19495ed1db7b57f6ab650486524caaf -->
- feat: Release preparation creates categorized draft notes from unreleased changes, links each entry to its recorded commit, and starts a fresh changelog section. <!-- commits: 19194eef1c34aa4df653e4d47126a18bff4311bf -->

### Changed

- feat: Recovery details report recorded targets, pending install transactions and supported review actions. Retry and rollback remain unavailable without evidence proving a safe recovery. Warning review or dismissal does not repair files or unlock deployment; unresolved journals are preserved.
- refactor: Operation feedback stays in the app instead of using Android Toast popups. The dashboard, Archive Library, and installer show the latest operation status. <!-- commits: 69bcad1cd7846cb3fcea988cfed19bce60c5af37 -->
- feat: First setup, active settings and new profiles select the game's main folder. DML validates it, derives the Data folder and enables physical deployment intent after successful selection. <!-- commits: 6814fdcff26cef81502e515020bd5f2f6a3c6262, 0f864263d685becc1f66c615f4764e2c13450747 -->
- feat: Target diagnostics report the selected target role, canonical path, validation result and structured findings. <!-- commits: 61298620686f65306c3f9dbc564b1210ddae608c -->
- feat: A clean Fallout New Vegas target remains valid for selecting Tale of Two Wastelands before its first deployment. <!-- commits: 61298620686f65306c3f9dbc564b1210ddae608c, 6814fdcff26cef81502e515020bd5f2f6a3c6262 -->
- fix: Archive imports check format and reader availability before making a temporary snapshot. Unknown signatures and formats without a reader are rejected without making a copy or opening an installer session.
- fix: Archive diagnostics distinguish RAR4 and RAR5 while retaining compatibility with existing stored RAR metadata. ZIP and 7z failures provide clearer corruption, memory limit and cleanup diagnostics. <!-- commits: 9fd8eb9f34bbe635f02c8f0b86f921977f9d192d, 00c4de3491d3448d08d7c584ad1a7e11895151df -->

### Fixed

- fix: Keep specific archive failure reasons through installation preparation and show clearer messages for unsafe paths, unsupported features and storage shortages. Last attempt details reuse extraction storage observations without another scan; unknown space is not treated as enough space.
- fix: Validate chosen Data folders before installation and copy only the checked file mapping. Conflicting paths, links, changed prepared files and excessive manual folder depth are refused without replacing an existing installation.
- fix: Use clearer button names and messages for imports, file checks and unfinished changes.
- fix: Keep archive rows reachable on short screens while Close and Cancel stay visible.
- fix: Index RAR file headers per volume so extraction does not repeatedly process the full header list. RAR5 metadata read accounting now accommodates 4,096 entries and an optional quick open record; the independent metadata byte and dictionary limits remain unchanged. <!-- commits: 70a55303d32498474b1f1100680a141ffbaf0e19 -->
- fix: Deployment preflight reports capacity by volume, including new backups and restore staging. Copy checks refuse measurable shortages before overwriting targets; unknown capacity remains an explicit warning rather than a space guarantee.
- fix: Archive snapshots and installer copies check measurable free space before opening destination files and during long copies. Unknown capacity still relies on normal filesystem errors; extraction keeps its existing storage checks.
- fix: Archive import and installer choices share one operation attempt. Waiting for choices is not reported as success; cancellation and retries keep distinct outcomes, and failed installer cleanup is reported as a failure.
- fix: Rotating the device preserves open panels, installer choices and operation state instead of restarting the app session. <!-- commits: 31eb08c63b141621d441e62f77f0b6aa2e5d6484 -->
- fix: Replace the light native window background that caused white flashes during launch and Activity recreation. <!-- commits: 6a41efa7442a903d4486500e55d47493f1e6d26f -->
- fix: Refuse folder confirmation after storage access is revoked, and allow retry after access is restored. <!-- commits: 8be4ad61c81218977ead16c17d74797e525a7d47 -->
- fix: Route profile deletion to the intended workflow instead of recursively calling the controller. <!-- commits: 248ed534c87f131df2441c8b06396ab751b6a121 -->
- fix: Preserve deployable mod files even when their names or extensions resemble documentation. Files such as UIO `supported.txt` deploy to `Data`, while installer files and unrelated development artifacts remain excluded. <!-- commits: 79527bed70061cb62aead04be20e42d1c2cf6b0b -->
- fix: Physical deployment validates Data and Game Root roles and known game markers before writes. Wrong games, reversed roles, overly broad folders and paths from different installations are rejected. <!-- commits: 61298620686f65306c3f9dbc564b1210ddae608c -->
- fix: Keep deployment state separate for each selected Data and Game Root target. Switching installations does not reuse another target's state, and returning to an earlier target reuses only that target's recorded state. <!-- commits: 2fdabecdf4ca22f123525801815d077e9b1b6c4e -->
- fix: Preserve identical existing target files instead of overwriting them when DML first manages them. <!-- commits: 9eb5f4a5ede35ca587f0716a4c6c1c4164de2750 -->
- fix: Preserve unfinished deployment journals and refuse corrupt or unreadable critical archive records and journals instead of silently replacing their evidence. <!-- commits: 2fdabecdf4ca22f123525801815d077e9b1b6c4e, 02908386680a42052ed4e2ef58d63670c48aab1c -->
- fix: Refuse conflicting operations and profile, game or target changes while another operation owns the session. <!-- commits: 3cb315c0cb8839976d21be3ed438fb86fc05b1b7 -->
- fix: Share full diagnostic reports as read only file attachments rather than oversized intent text. <!-- commits: 3cb315c0cb8839976d21be3ed438fb86fc05b1b7 -->
- fix: Release temporary import snapshots after success, failure or cancellation, and clear abandoned snapshots at startup without deleting source archives or pending installer content. <!-- commits: bbe5e19d9d2477fc4361d13db663d0d8c4be9f89 -->
- fix: Expected archive probe and reader failures no longer produce diagnostics that resemble a crash. <!-- commits: 9fd8eb9f34bbe635f02c8f0b86f921977f9d192d -->

### Known Issues

- Legacy archive cleanup is not automatic. Files are retained when originals cannot be verified or the copy is still needed by a source or library selection. <!-- commits: b8bc5851e9556e3052b337b8b4622a2bd4d214b1 -->
- Game folder validation checks known executable and master file markers; game store layouts and runtime compatibility are not exhaustively qualified. <!-- commits: 61298620686f65306c3f9dbc564b1210ddae608c, 6814fdcff26cef81502e515020bd5f2f6a3c6262 -->
- RAR5 is limited to a 32 MiB declared dictionary, 4 MiB of metadata input, 8,200 metadata read calls, 4,096 entries and 64 path segments. Metadata byte limits can be reached before the entry limit with long paths. Links, unknown sizes, newer compression versions and service records other than a bounded stored quick open index are not supported.
- Password protected, encrypted and multipart RAR archives are not supported. <!-- commits: dc70fece68f8e5daef06bdbe55d8a0379612827f -->
- Some uncommon 7z compression or encryption variants may remain unsupported. <!-- commits: 00c4de3491d3448d08d7c584ad1a7e11895151df -->
- Returning to a previous deployment target does not automatically verify every managed file against changes made outside DML. <!-- commits: 2fdabecdf4ca22f123525801815d077e9b1b6c4e -->

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
