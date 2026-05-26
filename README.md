# DroidModLoader

DroidModLoader is an Android based mod manager for Bethesda games, designed for shared storage game installs and Windows container environments. The project is focused on making mobile modding more organized, easier, and more scalable for users who want a practical way to build and maintain modded game setups on Android.

The primary development and testing target for this project is GameNative, which will be used to validate modlists, mod structure, deployment behavior, and compatibility in real world use.

---

## Overview

Modding Bethesda games on Android currently involves a lot of manual file handling, trial and error, and limited visibility into what is actually happening inside a setup. Desktop mod managers solve many of these problems on PC, but equivalent tooling for Android and container based workflows is non-existant.

DroidModLoader is being built to fill that gap.

The goal is not just to extract archives, but to provide a structured mod management layer that helps users install mods cleanly, understand conflicts, manage profiles, and build stable foundations for long term modded playthroughs.

---

## Project Goals

DroidModLoader is being developed around a few core goals:

- make large mod setups easier to manage on Android
- support shared-storage game installs used with Windows container apps
- reduce confusion around mod conflicts and file overwrites
- improve visibility into installed files and mod structure
- support reusable profiles and cleaner organization across game installs
- provide a more realistic path to Bethesda modding on mobile hardware

---
## Roadmap

Droid Mod Loader has a public roadmap covering deployment reliability, existing manual modlist adoption, GameNative integration, diagnostics, performance, themes, collections, and advanced plugin/installer support.

See the full roadmap here: [ROADMAP.md](ROADMAP.md)

---

## Target Games

The project is designed primarily around Bethesda style mod layouts, with support goals including:

- **Skyrim Legendary Edition**
- **Skyrim Special Edition**
- **Fallout: New Vegas**
- **Fallout 4**
- **Oblivion**

While the broader long term focus includes multiple games, early workflow design and compatibility planning are centered on the kinds of file structures and mod management patterns common to Bethesda titles.

---

## GameNative Support

A major part of DroidModLoader’s development is direct support for **GameNative**.

GameNative will serve as the main test environment for verifying that:

- mod installs are structured correctly
- shared storage deployment works as intended
- generated setups are usable in practice
- modded game environments behave reliably enough for real testing

This project is being built with actual use in mind, not just theoretical support. The Android management workflow and the Windows container runtime need to work together cleanly, and GameNative is the environment being used to prove this concept.

---

## Core Features

### Mod Installation

DroidModLoader is being designed to:

- import mod archives from device storage
- extract archives into managed mod directories
- keep each installed mod isolated in its own folder
- support loose file scanning after extraction
- prepare installed content for use in a shared game environment

### File Scanning and Conflict Detection

A core part of the app is visibility into files and conflicts. Planned capabilities include:

- path normalization for consistent file comparison
- recursive scanning of extracted mod contents
- file indexing for installed mods
- detection of overlapping files between mods
- hashing support where useful for faster comparison and validation

### Profile Management

The project is intended to support profile based modlist configurations so users can:

- maintain separate mod setups per game
- enable or disable mods by profile
- experiment without rebuilding everything from scratch
- keep cleaner separation between different playthroughs or test environments

### Performance Oriented Workflow

DroidModLoader is being built with large mod lists in mind. Long term performance goals include:

- avoiding unnecessary rescans
- using indexing and caching where practical
- supporting smarter incremental updates
- reducing the cost of rebuilding or validating large setups

### Load Order and Plugin Workflow

The project also aims to reduce confusion around Bethesda plugin handling by providing:

- better visibility into plugin activation and ordering
- support for plugin related output where appropriate
- guidance around files such as `plugins.txt` and `loadorder.txt`
- future tooling to simplify setup inside Windows container environments

---

## Beta Companion Guide

When DroidModLoader reaches beta, the project will also introduce a **Viva New Vegas style modding guide for Skyrim Legendary Edition**.

This guide will focus on creating a stable, well structured baseline to mod from rather than encouraging users to jump straight into large or unstable setups. The intent is to provide a practical starting point that reflects what actually works in this windows conrainer environment.

### Why Skyrim Legendary Edition?

The initial guide is being planned around **Skyrim Legendary Edition** because it offers more performance headroom on slower chips than more demanding Bethesda titles. That makes it a better baseline for early Android container based modding work, especially when testing on hardware with tighter performance limits.

### Guide Goals

The beta guide is intended to emphasize:

- a clean and stable base setup
- practical compatibility focused mod choices
- a reliable starting point for future expansion
- easier troubleshooting through a more controlled baseline
- a modding path that is more realistic for lower end or slower ARM64 devices

---

## Compatibility Roadmap

Initial compatibility work for the Skyrim Legendary Edition guide will focus on **Snapdragon devices**.

This narrower starting point is intentional. It allows testing to be done against a more controlled set of variables, including:

- specific mod combinations
- expected game behavior
- Turnip driver compatibility
- container stability and performance

Once compatibility is better understood on Snapdragon hardware, the project can expand toward a broader focus on other **ARM64 chips**.

The goal is to establish a dependable baseline first, then widen support from there.

---

## Development Direction

DroidModLoader is being built incrementally, with backend systems taking priority before heavier UI expansion.

Current development is centered on foundational components such as:

- path normalization
- archive extraction
- recursive file scanning
- metadata indexing
- install structure validation

---

## Why This Project Exists

Mobile and container based Bethesda modding still has major tooling gaps.

Many users attempting these workflows end up manually copying files, guessing at folder structure, troubleshooting blind conflicts, and rebuilding setups from scratch when something goes wrong. DroidModLoader exists to reduce that friction and make the process more understandable for both new and experienced users.

The long term aim is a mod manager that is practical, transparent, and grounded in real-world Android modding constraints.

---

## Community and Support

Support, discussion, testing, and project updates will primarily be hosted through Discord.

**Discord Server:** https://discord.gg/Q9dM262KRc

This community will serve as the main place for:

- support requests
- project feedback
- testing discussion
- compatibility findings
- development updates

---

## Community Attribution

Special thanks to Deno and his Discord community that contributes testing, feedback, troubleshooting, and shared modding knowledge. Community input plays a major role in shaping the direction of this project.

---

## Project Status

DroidModLoader is currently in active development.

The features and systems described in this README reflect the intended direction of the project and may evolve as testing continues and design decisions are refined.

---

## Contributing

Feedback is especially valuable from users with experience in:

- Bethesda modding workflows
- Android file handling
- Windows container gaming environments
- load order and plugin management
- file conflict analysis
- mobile performance testing on ARM64 devices

Bug reports, technical suggestions, and compatibility findings are all useful.

---

## License and attribution

Droid Mod Loader is released under the MIT License.

You are welcome to fork, modify, and build from this project. Please keep the
license notice intact and credit Droid Mod Loader / CyberShonk when sharing
derivative work.

---

## Disclaimer

DroidModLoader is an independent project. Users are responsible for complying with the permissions, licenses, and distribution terms associated with any third party mods they install or manage.

This project does not claim ownership over third party mods, game assets, or external community tools.

---

## Tip
If you'd like to support me here's my 
Ko-fi: https://ko-fi.com/seansboottom
