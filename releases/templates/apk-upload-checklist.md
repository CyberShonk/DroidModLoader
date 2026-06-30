# APK Upload Checklist

Use this checklist before uploading Droid Mod Loader APKs to Nexus Mods, GitHub Releases, Discord, or other public locations.

## Build Identity

- [ ] `VERSION_NAME` is correct in `version.properties`
- [ ] `VERSION_CODE` is greater than the previous public APK
- [ ] APK manifest version matches `version.properties`
- [ ] App UI displays the same version
- [ ] APK filename includes the version
- [ ] Release notes use the same version
- [ ] Changelog uses the same version

Verify identity with:

```bash
apkanalyzer manifest version-name app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest version-code app/build/outputs/apk/release/app-release.apk
```

## Build and Signing

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./tools/check-project.sh` passes
- [ ] `./gradlew assembleDebug` passes
- [ ] `./gradlew assembleRelease` passes
- [ ] Release APK is signed
- [ ] Signing certificate matches earlier public releases
- [ ] APK signature verification passes
- [ ] APK installs
- [ ] App launches

## Safety

- [ ] Upgrade from the previous public release was tested
- [ ] Existing app state survives the upgrade
- [ ] Tested with a safe target folder
- [ ] Deployment plan reviewed
- [ ] Unmanaged files are not blindly deleted
- [ ] Dangerous paths are blocked or warned
- [ ] Archive cancellation cleanup works
- [ ] Recovery warning behavior checked

## UI

- [ ] Main dashboard readable
- [ ] Portrait layout checked
- [ ] Landscape layout checked
- [ ] Developer Tools hidden unless developer mode is unlocked
- [ ] Recovery Tools visible when needed

## Documentation

- [ ] README current
- [ ] Current-status documents updated
- [ ] Changelog updated
- [ ] Known issues updated
- [ ] Release notes written
- [ ] Upgrade notes written
- [ ] Nexus and GitHub descriptions remain accurate

## Artifacts

- [ ] The tested signed APK is copied without rebuilding
- [ ] APK SHA-256 checksum generated
- [ ] Release filename is correct
- [ ] Debug APK is not included
- [ ] Signing files and credentials are not included
- [ ] Private project context and development logs are not included

## Upload

- [ ] APK scanned locally if desired
- [ ] Exact tested APK uploaded
- [ ] Checksum uploaded or published
- [ ] Release notes pasted
- [ ] Screenshots current enough
- [ ] Downloaded upload was checked after publication
- [ ] Discord or community post prepared if needed
