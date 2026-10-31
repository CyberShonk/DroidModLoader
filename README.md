<p align="center">
  <img src="assets/source/icons/dml_default_round_reference_v10.png" width="128" alt="Droid Mod Loader app icon">
</p>

<h1 align="center">Droid Mod Loader</h1>

<p align="center"><strong>Bethesda mod management with profiles for Android.</strong></p>

Droid Mod Loader, or DML, manages Bethesda game mods for Windows compatibility
environments hosted on Android. It keeps installed mods separate, tracks profiles
and plugin state, and deploys a resolved mod setup to selected game folders.

> [!WARNING]
> DML is beta software that can write to game folders. Back up important game
> files before testing a new build or deployment setup.

## Current support

The current public release is [v0.8.0-beta](https://github.com/CyberShonk/DroidModLoader/releases/tag/v0.8.0-beta).

DML supports profiles for:

- Skyrim Legendary Edition;
- Fallout: New Vegas;
- Fallout 3;
- Oblivion; and
- Tale of Two Wastelands through a Fallout: New Vegas target.

Current capabilities include profiles, direct Archive Library browsing, managed
mod installation, mod enable/disable and priority, Bethesda plugin discovery and
ordering, deployment planning, physical deployment, diagnostics that report the
selected target, and recovery foundations.

DML uses direct filesystem paths and Android all files access for shared storage
workflows. Android 11 or newer is required.

DML installs supported ZIP, 7z, and single volume, unencrypted RAR5 archives,
including supported solid RAR5 archives. Password protected and multipart RAR
archives, and some uncommon compression or encryption variants, are unsupported.
See the [archive limits](docs/user-guide.md#current-limitations) before importing.

## New in v0.8.0-beta

- Name a mod and choose its files and Data folder during installation. For an
  existing mod name, add or update files, replace the mod, or cancel.
- Install supported RAR5 archives and review details from the last install attempt.
- Review pending changes and unfinished work in Deployment Summary, and compare
  recorded Data, Game Root, and installed mod files with Check Recorded Files.
- Use Check Game Setup and Show Storage Info to inspect selected folders,
  permissions, storage locations, and available space.
- Review recorded attempts in Recent Activity and share diagnostic reports with
  Share Logs.

See the [release notes](https://github.com/CyberShonk/DroidModLoader/releases/tag/v0.8.0-beta)
for the full changes and limitations.

## Compatibility environments

DML is intended for Android Windows compatibility setups that expose usable game
folders through shared storage, including GameNative workflows. DML does
not modify or depend on another application's protected private storage.

Android may still block access to another application's protected `Android/data`
directory even when all files access is enabled.

## Get started

1. Download `DroidModLoader-v0.8.0-beta.apk` from the [GitHub release](https://github.com/CyberShonk/DroidModLoader/releases/tag/v0.8.0-beta).
2. Install the APK on your Android device. To update, install over the existing
   release app without uninstalling or clearing app data to retain profiles and
   state managed by DML.
3. Back up important game folders before deployment.
4. Follow the [User Guide](docs/user-guide.md). Choose your game's main folder;
   DML checks it and finds the Data folder.
5. Use [Troubleshooting](docs/troubleshooting.md) if setup, import, plugin, or deployment behavior looks wrong.

## Project links

- [User Guide](docs/user-guide.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Changelog](releases/changelog.md)
- [Contributing](CONTRIBUTING.md)
- [ARM64Nexus community](https://discord.gg/RvTWcEeJ7Z)

## Contributing

Bug reports, compatibility notes, documentation corrections, reproducible test
cases, and focused code changes are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Droid Mod Loader is released under the [MIT License](LICENSE).

## Disclaimer

Droid Mod Loader is an independent project. Users remain responsible for the
licenses and permissions of games, mods, and external tools they use.
