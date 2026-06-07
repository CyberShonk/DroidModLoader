# Droid Mod Loader Git Workflow

This document defines how Git should be used for Droid Mod Loader.

## Core Rule

Keep commits focused.

A commit should represent one clear change.

Good examples:

- Add versioning documentation
- Show real app version in diagnostics
- Hide developer tools behind dev mode
- Add unfinished deployment warning
- Fix mod display name Nexus suffix cleanup

Bad examples:

- fix
- stuff
- changes
- final
- more things

## Default Solo Workflow

For small focused changes, working directly on `main` is acceptable.

Use this loop:

```bash
git status
git pull origin main

# make one focused change

git status
git diff --stat
./gradlew testDebugUnitTest

git add <changed files>
git commit -m "Clear focused message"
git push origin main

git status