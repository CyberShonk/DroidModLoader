<p align="center">
  <img
    src="assets/source/icons/dml_default_round_reference_v10.png"
    width="128"
    alt="Droid Mod Loader app icon"
  >
</p>

<h1 align="center">Droid Mod Loader</h1>

<p align="center">
  <strong>Profile-based Bethesda mod management for Android.</strong>
  <br>
  Organize mods, plugins, deployment, and recovery for games running through
  Android Windows compatibility environments.
</p>

<p align="center">
  <a href="https://github.com/CyberShonk/DroidModLoader/releases">
    <img
      alt="Latest release"
      src="https://img.shields.io/github/v/release/CyberShonk/DroidModLoader?include_prereleases&style=flat-square&label=release&color=b3202a"
    >
  </a>
  <img
    alt="Android 11 or newer"
    src="https://img.shields.io/badge/Android-11%2B-3d8b5f?style=flat-square&logo=android&logoColor=white"
  >
  <a href="https://github.com/CyberShonk/DroidModLoader/actions/workflows/ci.yml">
    <img
      alt="Continuous integration status"
      src="https://github.com/CyberShonk/DroidModLoader/actions/workflows/ci.yml/badge.svg"
    >
  </a>
  <a href="LICENSE">
    <img
      alt="MIT License"
      src="https://img.shields.io/badge/license-MIT-555555?style=flat-square"
    >
  </a>
</p>

<p align="center">
  <a href="https://github.com/CyberShonk/DroidModLoader/releases"><strong>Releases</strong></a>
  ·
  <a href="docs/user-guide.md"><strong>User Guide</strong></a>
  ·
  <a href="https://discord.gg/wJnKUD64Nz"><strong>Discord</strong></a>
  ·
  <a href="ROADMAP.md"><strong>Roadmap</strong></a>
</p>

---

Droid Mod Loader keeps installed mods separate, tracks profiles and plugin
state, and prepares files for a selected game folder. It is intended to replace
repetitive manual copying with a process that is easier to review, repeat, and
recover.

> [!WARNING]
> Droid Mod Loader is beta software that can write to game folders. Back up
> important files before testing a new build or deployment setup.

## What it does

Droid Mod Loader currently includes early support for:

- profile creation and switching;
- browsing a remembered archive folder;
- basic ZIP, 7Z, and RAR archive import;
- separate managed folders for installed mods;
- mod enable, disable, and priority controls;
- `.esm`, `.esp`, and `.esl` discovery;
- plugin activation and ordering for supported games;
- deployment planning, journals, backups, and recovery foundations;
- overwrite, baseline, diagnostics, and logging foundations; and
- an experimental second-screen plugin display.

Archive compatibility is still being improved, especially for newer or unusual
RAR and 7Z variants.

## Games and environments

The main early game targets are:

- Skyrim Legendary Edition;
- Fallout: New Vegas;
- Fallout 3;
- Oblivion; and
- Tale of Two Wastelands setups based on Fallout: New Vegas.

Skyrim Special Edition and Fallout 4 remain longer-term targets.

Droid Mod Loader is intended for games running through Android Windows
compatibility environments such as GameNative, Winlator, GameHub Lite, and
BannerHub. It prepares files those environments can use without modifying or
depending on their private files.

## Storage and profiles

The app uses user-granted all-files access and direct filesystem paths for
shared-storage workflows. Each profile can remember its own game root, `Data`
folder, and archive library.

Profiles keep different mod setups separate, making it easier to test changes or
maintain different setups for the same game.

## Project status

The latest public release is `v0.7.0-beta`, the Reliable Foundation release.
It completes the direct-filesystem transition, strengthens archive detection
and extraction safety, protects existing installed mods during replacement,
and adds cancellation and interrupted-install recovery.

The next development phase will focus on safe setup, stronger game-folder
validation, deployment and recovery polish, and the remaining work required for
a stable 1.0 release.

See [Current Status](CURRENT_STATUS.md),
[Current Priorities](docs/tasks/current-priorities.md), and the
[Roadmap](ROADMAP.md) for details.
- [UI/UX Direction](docs/ui-ux.md)

## Get started

1. Download the latest APK from [Releases](https://github.com/CyberShonk/DroidModLoader/releases).
2. Back up any game folders you plan to use for testing.
3. Read the [User Guide](docs/user-guide.md) before selecting storage or deploying mods.

## Documentation

- [User Guide](docs/user-guide.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Glossary](docs/glossary.md)
- [Current Status](CURRENT_STATUS.md)
- [Roadmap](ROADMAP.md)
- [Changelog](releases/changelog.md)
- [Documentation Index](docs/index.md)

## Community and support

Support, testing, project discussion, and development updates are handled mainly
through the [Droid Mod Loader Discord server](https://discord.gg/wJnKUD64Nz).

Useful bug reports include the Android device and version, the game and
compatibility environment, the selected folders, installed mods, reproduction
steps, and any relevant screenshots or logs.

Project support links: [Ko-fi](https://ko-fi.com/D1D81ZHV4E) ·
[Patreon](https://patreon.com/SeanBottoms)

## Contributing

Useful contributions include bug reports, compatibility notes, documentation
fixes, reproducible test cases, safer file-handling ideas, and Bethesda modding
knowledge.

Keep changes focused and include enough information for another person to review
and test them. See the [documentation index](docs/index.md) for architecture,
testing, workflow, and release references.

## License

Droid Mod Loader is released under the [MIT License](LICENSE).

## Disclaimer

Droid Mod Loader is an independent project. Users are responsible for following
the permissions, licenses, and distribution terms for any games, mods, or
third-party tools they use.

This project does not claim ownership of Bethesda game assets, third-party mods,
GameNative, Winlator, xEdit, LOOT, Nexus Mods, or other community tools.
