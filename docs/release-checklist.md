# Droid Mod Loader Release Checklist

Use this checklist before uploading an APK publicly.

## Version

- [ ] `versionName` is correct in `app/build.gradle.kts`
- [ ] `versionCode` is incremented if needed
- [ ] `versionName` follows `v0.x.y-beta` before stable 1.0
- [ ] App diagnostics show the real version
- [ ] APK filename includes the version
- [ ] `releases/changelog.md` has an entry for this version

## Code State

- [ ] git status is clean before release build
- [ ] No temporary files are committed
- [ ] No accidental backup files are committed
- [ ] No release APKs are committed
- [ ] No debug-only UI is visible to normal users
- [ ] Developer Tools are hidden unless developer mode is unlocked
- [ ] Recovery Tools are visible when needed

## Build

Run before release:

./gradlew clean
./gradlew assembleDebug
./gradlew assembleRelease

Confirm:

- [ ] Debug build succeeds
- [ ] Release build succeeds
- [ ] APK installs on test device
- [ ] App launches without crash

## Manual Test

Minimum release test:

- [ ] Fresh install works
- [ ] Existing install upgrade works
- [ ] App icon appears correctly
- [ ] Game target selection works
- [ ] Target is remembered
- [ ] Mod import works
- [ ] Mod enable/disable works
- [ ] Plugin scan works
- [ ] Plugin enable/disable works
- [ ] Plugin output export works
- [ ] Deployment plan works
- [ ] Test deployment works on safe folder
- [ ] Diagnostics screen opens
- [ ] Recovery warning behavior is sane
- [ ] No major dashboard text clipping

## File Safety

Confirm before release:

- [ ] App does not blindly delete unmanaged files
- [ ] Dangerous target paths are blocked or warned
- [ ] Deployment plan exists before file changes
- [ ] Journal starts before risky file operations
- [ ] Interrupted deploy state is detectable
- [ ] Recovery path is understandable

## Docs

- [ ] README is current
- [ ] docs/requirements.md is updated where needed
- [ ] docs/testing.md is updated if test coverage changed
- [ ] releases/changelog.md is updated
- [ ] Known issues are listed

## APK Handling

- [ ] Release APK is signed if publishing as release
- [ ] APK filename includes app name and version
- [ ] APK is not committed to Git
- [ ] Upload notes explain what changed

Suggested APK filename:

DroidModLoader-v0.5.4-beta.apk

## Release Notes Format

Use this format:

Version:
Date:
Added:
Changed:
Fixed:
Known Issues:
Upgrade Notes:

## Final Git Check

Run:

git status
git log --oneline -5

Expected:

nothing to commit, working tree clean