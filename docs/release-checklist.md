# Droid Mod Loader Release Checklist

## Related Release Files

| File                                           | Purpose                                  |
|------------------------------------------------|------------------------------------------|
| `version.properties`                           | Canonical version name and version code  |
| `tools/prepare-release.sh`                     | Validated release-version preparation    |
| `releases/changelog.md`                        | User-facing release history              |
| `releases/templates/release-notes-template.md` | Reusable public release notes template   |
| `releases/templates/apk-upload-checklist.md`   | Upload checklist for APK publishing      |
| `docs/process/versioning.md`                   | Version naming and version-code rules    |

Use this checklist before uploading an APK publicly.

## Release Preparation

- [ ] Start from clean, synchronized `main`
- [ ] Create `release/<version>`
- [ ] Run `./tools/prepare-release.sh <version> <version-code>`
- [ ] Review the generated `version.properties` change
- [ ] Complete `releases/notes/<version>.md`
- [ ] Update `releases/changelog.md`
- [ ] Update current-status and other affected documentation
- [ ] Commit the reviewed release-preparation changes

## Version

- [ ] `VERSION_NAME` is correct in `version.properties`
- [ ] `VERSION_CODE` is greater than every publicly distributed APK
- [ ] Version name follows `v0.x.y-beta` before stable 1.0
- [ ] App UI and diagnostics use `BuildConfig.VERSION_NAME`
- [ ] Built APK manifest contains the intended version name and code
- [ ] APK filename includes the version
- [ ] Changelog and release notes use the same version

Verify the built APK:

```bash
apkanalyzer manifest version-name app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest version-code app/build/outputs/apk/release/app-release.apk
```

## Code State

- [ ] Git status is clean before the release build
- [ ] Release build is made from the reviewed release commit
- [ ] No temporary files are committed
- [ ] No accidental backup files are committed
- [ ] No release APKs are committed
- [ ] No debug-only UI is visible to normal users
- [ ] Developer Tools are hidden unless developer mode is unlocked
- [ ] Recovery Tools are visible when needed

## Build

Run before release:

```bash
./gradlew clean
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
./tools/check-project.sh
```

Confirm:

- [ ] Unit tests succeed
- [ ] Project check succeeds
- [ ] Debug build succeeds
- [ ] Release build succeeds
- [ ] Release APK is signed with the expected certificate
- [ ] APK signature verification succeeds
- [ ] APK installs on the test device
- [ ] App launches without crashing

## Manual Test

Minimum release test:

- [ ] Fresh install works
- [ ] Upgrade from the current public release works
- [ ] Existing profiles, paths, mods, and plugin state survive upgrade
- [ ] App icon appears correctly
- [ ] Game target selection works
- [ ] Target is remembered
- [ ] Archive folder selection works and persists
- [ ] Archive refresh and folder switching work
- [ ] ZIP, 7Z, and RAR4 files are discovered and install as expected
- [ ] RAR5 and other unsupported variants fail with clear messages
- [ ] Cancellation removes partial import and install state
- [ ] Mod import works through the archive-folder workflow
- [ ] Mod enable/disable works
- [ ] Plugin scan works
- [ ] Plugin enable/disable works
- [ ] Plugin output export works
- [ ] Deployment plan works
- [ ] Test deployment works on a safe folder
- [ ] Diagnostics screen opens
- [ ] Recovery warning behavior is sane
- [ ] Main screens render without obvious layout breakage
- [ ] App still works after force-stop and restart

## File Safety

Confirm before release:

- [ ] App does not blindly delete unmanaged files
- [ ] Dangerous target paths are blocked or warned
- [ ] Deployment plan exists before file changes
- [ ] Journal starts before risky file operations
- [ ] Interrupted deploy state is detectable
- [ ] Interrupted archive replacement is recoverable
- [ ] Recovery path is understandable

## Documentation

- [ ] README is current
- [ ] `CURRENT_STATUS.md` is current
- [ ] `docs/tasks/current-priorities.md` is current
- [ ] `docs/requirements.md` is updated where needed
- [ ] `docs/testing.md` reflects current validation
- [ ] `releases/changelog.md` is updated
- [ ] Release notes are complete
- [ ] Known issues are listed

## APK Handling

- [ ] Release APK is signed
- [ ] Signing certificate matches previous public releases
- [ ] APK filename includes app name and version
- [ ] APK SHA-256 checksum is generated
- [ ] APK is not committed to Git
- [ ] The exact tested APK is the distributed APK
- [ ] Upload notes explain what changed
- [ ] GitHub release notes match the changelog
- [ ] Nexus Mods page is updated for the new version

Suggested APK filename:

```text
DroidModLoader-<version>.apk
```

## Release Notes Format

Use this format:

```text
Version:
Date:
Added:
Changed:
Fixed:
Known Issues:
Upgrade Notes:
```

## Final Git Check

Run:

```bash
git status --short --branch
git log --oneline -5
```

Expected: the release commit is checked out and the working tree is clean.
