# Droid Mod Loader Roadmap

This roadmap tracks the major direction for Droid Mod Loader.

It is not the task list.

## Planning Rules

- Use this roadmap for major phases and project direction.
- Use `docs/tasks/current-priorities.md` for near-term work.
- Use `docs/tasks/backlog.md` for rough or deferred ideas.
- Use GitHub Issues for scoped implementation tasks.
- Use `docs/decisions.md` for major decisions.
- Do not code directly from a vague roadmap item.
- Convert roadmap items into scoped tasks before implementation.

## Current Core Direction

Droid Mod Loader should become an MO2-style resolver, profile manager, and transactional physical deployer for Android Bethesda modding.

MO2 uses a virtual file system.

Droid Mod Loader uses indexed physical deployment.

The core model is:

1. Installed mods
2. File indexes
3. Resolved game view
4. Deploy plan
5. Physical deploy
6. Verification
7. Diagnostics
8. Recovery

## What already exists in early form

Droid Mod Loader already has early support for:

* profiles
* managed mod folders
* archive import
* a profile-specific remembered Archive Library with search, refresh, folder
  switching, and installation through the existing import pipeline
* ZIP, 7z, and RAR archive handling basics
* basic BAIN and FOMOD installer detection
* mod enable and disable
* mod priority order
* plugin scanning
* plugin enable and disable
* plugin output files
* deployment planning
* deployment journal and recovery work
* baseline and overwrite foundations
* diagnostics and logs
* experimental second screen plugin display

Some of these are still rough. The goal before 1.0 is to make the important parts reliable, not just present.

## Before 1.0

The first stable release needs to be safe enough for real users.

Before 1.0, the main work is:

* safer deployment
* target scoped deploy state
* game folder validation
* unfinished deploy recovery
* better support reports
* Nexus downloads and metadata
* advanced FOMOD support
* better BAIN support
* stronger plugin intelligence
* basic xEdit report bridge
* practical LOOT support
* better conflict view
* better overwrite management
* settings screen
* storage tools
* external SD support
* more tests for risky file logic

## Safer deployment

Droid Mod Loader touches real game files, so file safety comes first.

The app needs to know exactly which game, profile, and target folder it is working with.

Planned work includes:

* target scoped deployment manifests
* target scoped baselines
* per game simulated deploy folders
* full redeploy when the target changes
* clearer deploy reports
* safer recovery tools
* post deploy checks
* better error messages

This is meant to prevent a bad situation where the app thinks files were deployed to a real game folder when they only went to a simulated test folder.

## Profiles and game setup

Profiles are meant to keep mod setups separate.

Planned work includes:

* stronger profile validation
* safer profile switching
* cleaner game target settings
* separate Data and Game Root targets
* better warnings when a target folder is missing
* game folder checks for Skyrim, Fallout: New Vegas, Fallout 3, and Oblivion
* a dedicated Tale of Two Wastelands game/profile definition that reuses the
  Fallout: New Vegas engine family
* profile reports for troubleshooting

The goal is for one setup to stay separate from another.

## Game folder validation

Droid Mod Loader should catch common folder mistakes.

For example:

* Skyrim LE Data should contain `Skyrim.esm`
* Fallout: New Vegas Data should contain `FalloutNV.esm`
* Fallout 3 Data should contain `Fallout3.esm`
* Oblivion Data should contain `Oblivion.esm`

If the user picks the wrong folder, the app should explain what happened instead of failing later.

## Existing manual installs

A lot of users already have manually installed mods.

Droid Mod Loader should not assume it owns those files.

Planned work includes:

* detecting existing files in the Data folder
* treating existing files as protected baseline files
* showing unmanaged plugins and loose files
* letting users manage future DML installs without touching old manual files

The safe default is simple:

Do not move or delete existing manual files unless the user clearly chooses to.

## Downloads and metadata

Droid Mod Loader needs better archive and metadata handling.

Planned work includes:

* extend the released profile-specific Archive Library without replacing its
  read-only folder workflow
* improve archive compatibility and unsupported-variant diagnostics
* add a separate downloads or metadata view only when its responsibility is
  clearly distinct from the Archive Library
* source link tracking
* Nexus metadata where possible
* mod version tracking where possible
* manual metadata editing
* duplicate and update detection
* exported modlists with source links

This matters because a future shared modlist should not just say “install this mod.” It should also help the user find the right file.

## Installer support

Many Bethesda mods use installers.

Advanced FOMOD support is planned before 1.0 because many popular mods need it.

Planned work includes:

* better FOMOD pages and options
* required and optional choices
* recommended choices where possible
* file preview before install
* remembered installer choices
* better BAIN package handling
* clearer unsupported installer warnings
* safer temp file cleanup

The goal is not to magically support every scripted installer immediately. The goal is to support common installers well and fail safely when something is not supported.

## Plugin intelligence

Plugin problems are one of the easiest ways to break a Bethesda modlist.

Before 1.0, Droid Mod Loader will help catch basic plugin issues.

Planned work includes:

* missing master detection
* official plugin rules
* plugin dependency warnings
* disabled source mod warnings
* missing plugin file warnings
* BSA and plugin pairing warnings
* clearer plugin diagnostics
* plugin warnings in support reports
* game-specific activation-file formatting
* timestamp-based plugin ordering for Fallout: New Vegas, Tale of Two
  Wastelands, Fallout 3, and Oblivion
* text-file ordering behavior for Skyrim LE

The goal is to help users understand what is wrong before they launch the game and crash.

## xEdit bridge

Droid Mod Loader is not trying to become a full Android port of xEdit, but a tool with some similar features and concepts.

The first goal is a basic bridge.

Planned work includes:

* let the user run an xEdit-like tool in the app 
* show useful warnings in the app
* include xEdit findings in support reports

This gives users a practical path to use xEdit information without pretending the whole desktop tool has been rebuilt on Android.

## LOOT support

Practical LOOT support is planned before 1.0.

The first version will focus on useful warnings and suggested sorting.

Planned work includes:

* LOOT metadata where practical
* load order suggestions
* plugin warning notes
* missing or dirty plugin metadata where available
* suggested order preview
* apply suggested order with confirmation

The app should explain what it is suggesting before it changes anything.

## Conflict view and overwrite handling

Droid Mod Loader needs to show users what is winning, what is losing, and what changed outside the app.

Planned work includes:

* resolved game view
* file conflict details
* winning mod display
* overwritten file display
* overwrite file view
* create mod from overwrite
* ignore selected overwrite files
* generated output tracking
* safer baseline tools

The goal is to make conflicts understandable without copying a desktop layout that does not work well on touch screens.

## Settings and app cleanup

As the app grows, normal controls and advanced tools need to be separated.

Planned work includes:

* settings screen
* appearance options
* diagnostics settings
* storage settings
* profile settings
* developer tools behind unlock
* recovery tools kept visible where needed
* cleaner dashboard

The main dashboard should stay focused on normal use.

## Storage and external SD support

Large modlists can take a lot of space.

Planned work includes:

* storage manager
* temp file cleanup
* old report cleanup
* archive cache cleanup
* installed mod size view
* external SD support for managed mod folders
* missing storage warnings
* storage health checks

Internal storage should stay the safest default. External SD support should be optional and clear about the risks.

## GameNative support

GameNative is the main test environment for Droid Mod Loader.

Planned work includes:

* guided Game Folder setup with clear Game Root and Data Folder language
* automatic Data detection where the selected installation can be validated
* an advanced Data Folder override for unusual layouts
* GameNative environment notes
* a profile-aware `DML_output` handoff for the active profile only
* game-aware plugin activation and load-order export
* reviewed game configuration presets later, after the output handoff is safe
* GameNative setup reports
* helper tools later

The goal is to make GameNative modding less confusing without depending on private GameNative internals.

## Guides

Droid Mod Loader will eventually include guide style support.

The first guide target is planned around Skyrim Legendary Edition on Snapdragon devices.

A guide should help with:

* required mods
* optional mods
* source links
* expected archive files
* install order
* plugin order
* GameNative settings
* validation checks
* support reports

The goal is to give users a stable starting point instead of making them guess their way through a large setup.

## Collections and shared setups

Collections are planned, but they will be built carefully.

The first goal is a useful checklist that can tell users:

* what mods are expected
* where to get them
* what files are missing
* what plugins should be enabled
* what order is expected
* what warnings matter

This depends on downloads and metadata work first.

More advanced collection features can come later after the app has stronger installer support, plugin checks, and diagnostics.

## Visual style

Planned visual work includes:

* dark mode
* cleaner theme system
* compact rows
* stronger panel layout
* better landscape support
* distinct default look
* game inspired themes later

## Second screen ideas

Second screen support is experimental.

Future ideas include:

* plugin monitor
* operation monitor
* selected mod details
* tester report panel
* GameNative environment panel
* touch shortcut pad

This will stay optional. The normal app should not depend on a second screen.

## After 1.0

After 1.0, the focus can move toward bigger features.

Possible future work includes:

* stronger collection tools
* better guide mode
* deeper Nexus support
* deeper GameNative helper tools
* better xEdit integration
* stronger LOOT integration
* texture budget warnings
* Android and GameNative compatibility scoring
* better large modlist tools
* more public guides

Some of these are intentionally broad for now. The core app needs to be safe and reliable first.

## Current priority

The current priority is still the foundation:

* deployment trust
* recovery tools
* target validation
* diagnostics
* metadata
* installer maturity
* plugin correctness

Everything else builds on that.
