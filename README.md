# Droid Mod Loader

Droid Mod Loader is an Android app for managing Bethesda game mods.

The simple version:

You install mods into Droid Mod Loader.
Droid Mod Loader keeps those mods organized.
Then it prepares the right files so your game can use them.

The app is being built for people who want to mod Bethesda games on Android while using Windows container apps like GameNative.

Right now, Android Bethesda modding usually means copying files by hand, guessing where things go, and hoping the game still launches. Droid Mod Loader is meant to make that process safer, clearer, and easier to repeat.

## What this app does

Droid Mod Loader helps with things like:

* Importing mod archives from your Android device
* Keeping each installed mod in its own folder
* Turning mods on and off
* Keeping separate profiles for different setups
* Scanning mod files so the app knows what each mod contains
* Showing plugin files like `.esp`, `.esm`, and `.esl`
* Writing plugin files such as `plugins.txt` and `loadorder.txt`
* Preparing files for a shared game folder
* Helping users understand conflicts and overwrites
* Creating useful reports when something goes wrong

The long term goal is to bring a Mod Organizer/Vortex style experience to Android, but built around how Android storage and Windows container apps actually work.

## Why this exists

Bethesda games can be heavily modded on PC because there are strong tools like Mod Organizer 2, Vortex, xEdit, and LOOT.

Android does not have the same kind of modding setup.

If you are trying to play games like Skyrim or Fallout: New Vegas through a Windows container on Android, you often have to manage files yourself. That can get confusing fast.

Droid Mod Loader exists to help with that.

It is not just an archive extractor. The goal is to build a real Android mod manager that understands installed mods, profiles, conflicts, plugin order, deployment, and diagnostics.

## Who this is for

This project is mainly for:

* people modding Bethesda games on Android
* GameNative users
* Android handheld users
* people testing Windows game containers on ARM devices
* Bethesda modders who want cleaner mobile tools
* users who want less manual file copying

You do not need to understand every technical term to use the app. The app should eventually explain what it is doing and why it matters.

## Target games

The project is focused on Bethesda style modding.

Current and planned targets include:

* Skyrim Legendary Edition
* Fallout: New Vegas
* Fallout 3
* Oblivion
* Skyrim Special Edition
* Fallout 4

Skyrim Legendary Edition and Fallout: New Vegas are the most important early targets.

## GameNative support

GameNative is the main test environment for this project.

The goal is not to modify GameNative directly or depend on private GameNative files. Droid Mod Loader is being built to work with shared folders and exported files that GameNative can use.

Droid Mod Loader should help users with:

* picking the correct game folder
* preparing the Data folder
* handling optional game root files
* exporting plugin order files
* exporting config files later
* creating reports that include GameNative setup notes
* reducing the amount of guessing needed to get a modded game running

In plain English:

Droid Mod Loader prepares the modded game files.
GameNative runs the Windows game.
The two need to work together.

## Important terms

### Mod

A mod is a fan made change for a game. It might add textures, weapons, quests, bug fixes, menus, scripts, or new gameplay features.

### Mod manager

A mod manager helps install, organize, enable, disable, and troubleshoot mods.

### Data folder

Bethesda games usually load most mods from a folder called `Data`.

For example, a Skyrim mod might place files into:

```text
Data/textures
Data/meshes
Data/scripts
Data/SkyUI.esp
```

Droid Mod Loader needs to know where the correct Data folder is so it can prepare files safely.

### Game root folder

Some mods need files outside the Data folder.

Examples include script extenders like SKSE, NVSE, OBSE, or FOSE.

These files often live beside the game executable instead of inside Data. Droid Mod Loader treats this as a more advanced action because putting files in the wrong root folder can cause problems.

### Plugin

A plugin is usually an `.esp`, `.esm`, or `.esl` file.

Plugins tell Bethesda games about new records, quests, items, cells, scripts, and other changes.

### Load order

Load order is the order plugins load in.

The order matters because one plugin can overwrite or depend on another. Bad load order can cause missing content, crashes, broken quests, or strange bugs.

### Profile

A profile is a separate mod setup.

For example, you could have:

* a clean testing profile
* a Skyrim graphics profile
* a Fallout: New Vegas stability profile
* a Tale of Two Wastelands profile

The goal is for each profile to stay separate so one setup does not leak into another.

## Current project status

Droid Mod Loader is in beta development.

Some parts work now. Some parts are still experimental. Some parts are planned but not finished yet.

Current focus areas include:

* safer file deployment
* better profile isolation
* better recovery tools
* better diagnostics
* better plugin handling
* better installer support
* GameNative focused testing

This is still early software. Back up important game folders before testing.

## What works now

The app already has early support for:

* profile creation and switching
* mod import
* ZIP, 7z, and RAR archive handling basics. RAR5 support is still being worked on.
* managed mod folders
* mod enable and disable
* mod priority order
* plugin scanning
* plugin enable and disable
* plugin output files
* deployment planning basics
* deployment journal and recovery work
* baseline and overwrite foundations
* diagnostics and logs
* experimental second screen plugin display

Some of these systems are still being improved.

## What is still being built

Major work still planned before a stable 1.0 release includes:

* stronger deploy safety
* better unfinished deploy recovery
* game folder validation
* support report export
* Nexus downloads and mod metadata
* advanced FOMOD installer support
* better BAIN installer support
* stronger plugin intelligence
* basic xEdit report bridge
* practical LOOT support
* better conflict view
* better overwrite management
* settings screen
* storage manager
* external SD support
* cleaner public guides
* more tests for risky file logic


## Roadmap

The full roadmap is here:

[ROADMAP.md](ROADMAP.md)

The short version is:

* make deployment safe
* make profiles reliable
* make installed mods easier to understand
* make plugin problems easier to see
* make real mod archives install correctly
* make GameNative setup less confusing
* make support reports useful
* then build collections, guides, and larger ecosystem features

## Project Vision

The deeper project direction is documented here:

- [Project Vision](docs/vision.md)

## Installer support

Many Bethesda mods use installers such as FOMOD or BAIN.

Droid Mod Loader already has early installer analysis, but this needs to get much stronger before 1.0.

Advanced FOMOD support is important because many popular mods use it. The goal is to support common FOMOD installers, remember user choices, preview what will be installed, and fail safely when an installer uses unsupported logic.

## Plugin intelligence, xEdit, and LOOT

Plugin handling is one of the biggest parts of Bethesda modding.

Droid Mod Loader will be able to help with basic plugin problems such as:

* missing masters
* disabled source mods
* missing plugin files
* official plugin rules
* plugin dependency warnings
* BSA and plugin pairing warnings
* load order issues
* useful plugin diagnostics

Practical LOOT support is planned before 1.0. The goal is useful warnings and suggested sorting.

## Planned guide support

Droid Mod Loader will eventually support guide style modding.

The first guide target is planned around Skyrim Legendary Edition on Snapdragon and Turnip based Android devices. Created by me.

The goal is to give users a stable starting point instead of throwing them into a giant modlist with no idea what went wrong.

A guide should eventually help with:

* required mods
* optional mods
* source links
* install order
* plugin order
* GameNative settings
* expected files
* validation checks
* support reports

## Community and support

Support, testing, project discussion, and updates are mainly handled through Discord.

Discord Server:

https://discord.gg/Q9dM262KRc

Good feedback includes:

* what device you are using
* what game you are testing
* what GameNative setup you are using
* what mods you installed
* what went wrong
* screenshots if useful
* logs or support reports when available

## Community attribution

Special thanks to Deno and his Discord community for testing, feedback, troubleshooting, and shared modding knowledge.

This project is shaped heavily by real testing and real user problems.

## Support the project

Droid Mod Loader is a personal project built around Android modding, GameNative testing, and a lot of trial and error.

If you enjoy the project and want to support the time that goes into it, my Ko-fi is here:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/D1D81ZHV4E)

No pressure. Testing the app, reporting bugs, sharing notes, and helping other users already helps a lot.

If you want access to my secrets join the Patreon: https://patreon.com/SeanBottoms

## Contributing

Useful contributions include:

* bug reports
* tester feedback
* compatibility notes
* documentation fixes
* clear reproduction steps
* ideas for safer file handling
* GameNative setup findings
* Bethesda modding knowledge

If you fork or modify the project, please keep the license notice intact and credit Droid Mod Loader / CyberShonk when sharing derivative work.

## License

Droid Mod Loader is released under the MIT License.

See:

[LICENSE](LICENSE)

## Disclaimer

Droid Mod Loader is an independent project.

Users are responsible for following the permissions, licenses, and distribution terms for any mods, games, or third party tools they use.

This project does not claim ownership over third party mods, Bethesda game assets, GameNative, xEdit, LOOT, Nexus Mods, or community modding tools.
